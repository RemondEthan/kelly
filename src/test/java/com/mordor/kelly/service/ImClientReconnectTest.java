package com.mordor.kelly.service;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImClient 断线重连的单元测试。
 *
 * <p>重点验证「连接代（epoch）隔离」：旧连接的迟到回调不得影响新连接的状态。
 * 这正是共享单个 WebSocket.Listener 时会出现的竞态——旧连接的 onClose
 * 会停掉新连接的保活任务，导致新连接必然超时掉线。</p>
 */
class ImClientReconnectTest {

    /** 记录收到的客户端事件 */
    private static final class Recorder {
        final List<ImClient.Event> events = new ArrayList<>();

        ImClient client() {
            ImClient client = new ImClient();
            client.addListener(events::add);
            return client;
        }

        boolean hasClosed() {
            return events.stream().anyMatch(e -> e instanceof ImClient.Event.Closed);
        }

        boolean hasRegistered() {
            return events.stream().anyMatch(e -> e instanceof ImClient.Event.Registered);
        }

        List<ImClient.LinkState> states() {
            List<ImClient.LinkState> result = new ArrayList<>();
            for (ImClient.Event event : events) {
                if (event instanceof ImClient.Event.LinkStateChanged changed) {
                    result.add(changed.state());
                }
            }
            return result;
        }
    }

    @Test
    void staleSessionCloseDoesNotTouchCurrentConnection() {
        Recorder recorder = new Recorder();
        ImClient client = recorder.client();

        // 旧连接（epoch=1）建立后，新连接（epoch=2）接管
        ImClient.SessionListener oldSession = client.newSessionListener(1L);
        client.beginConnectionForTest(2L);

        // 旧连接迟到地断开
        StubWebSocket socket = new StubWebSocket();
        oldSession.onClose(socket, 1000, "old closed");

        // 新连接不应被影响：状态仍是 CONNECTING（尚未 registered），且没有 Closed 事件
        assertEquals(ImClient.LinkState.CONNECTING, client.linkState());
        assertFalse(recorder.hasClosed(), "旧连接的 onClose 不应产生 Closed 事件");
        assertEquals(0, socket.abortCount, "过期的 onClose 不应操作 socket");
    }

    @Test
    void staleSessionErrorIsIgnored() {
        Recorder recorder = new Recorder();
        ImClient client = recorder.client();

        ImClient.SessionListener stale = client.newSessionListener(1L);
        client.beginConnectionForTest(2L);

        stale.onError(null, new IllegalStateException("boom"));

        assertEquals(ImClient.LinkState.CONNECTING, client.linkState());
        assertFalse(recorder.hasClosed());
    }

    @Test
    void staleSessionTextIsDropped() {
        Recorder recorder = new Recorder();
        ImClient client = recorder.client();

        ImClient.SessionListener stale = client.newSessionListener(1L);
        client.beginConnectionForTest(2L);

        // 旧连接的分片数据不应进入消息处理
        StubWebSocket socket = new StubWebSocket();
        stale.onText(socket, "{\"type\":\"registered\"", false);

        assertFalse(recorder.hasRegistered(), "旧连接的文本消息不应被处理");
        assertEquals(1, socket.abortCount, "过期的 onText 应终止旧连接");
    }

    @Test
    void currentSessionCloseEmitsClosedAndDisconnected() {
        Recorder recorder = new Recorder();
        ImClient client = recorder.client();

        client.beginConnectionForTest(2L);
        ImClient.SessionListener current = client.newSessionListener(2L);

        current.onClose(new StubWebSocket(), 1006, "server gone");

        assertEquals(ImClient.LinkState.DISCONNECTED, client.linkState());
        assertTrue(recorder.hasClosed(), "当前连接断开应产生 Closed 事件");
        assertTrue(recorder.states().contains(ImClient.LinkState.DISCONNECTED));
    }

    @Test
    void epochIncreasesAcrossConnections() {
        Recorder recorder = new Recorder();
        ImClient client = recorder.client();

        client.beginConnectionForTest(1L);
        long first = client.currentEpoch();
        client.beginConnectionForTest(2L);
        long second = client.currentEpoch();

        assertNotEquals(first, second, "每次建连应分配不同的 epoch");
    }

    @Test
    void reconnectWithoutServerAddressFails() {
        ImClient client = new ImClient();
        CompletableFuture<Void> future = client.reconnect();
        assertTrue(future.isCompletedExceptionally(),
                "未记录服务器地址时重连应立即失败");
    }

    @Test
    void reconnectReusesRecordedAddress() {
        ImClient client = new ImClient();

        // 连到一个不存在的端口：握手会失败，但地址已被记录
        client.connect("127.0.0.1", 1, "ROOM", "pw", "ada");

        CompletableFuture<Void> future = client.reconnect();
        assertFalse(rootMessage(future).contains("未记录服务器地址"),
                "重连不应因缺少地址而失败");
        client.close();
    }

    @Test
    void closeKeepsPasswordForReconnect() {
        ImClient client = new ImClient();
        client.connect("127.0.0.1", 1, "ROOM", "secret", "ada");
        assertEquals("secret", client.password());

        client.close();

        // 口令必须保留：重连要靠它重新派生密钥，
        // 否则重连后双方密钥不一致，消息将无法解密。
        assertEquals("secret", client.password(),
                "关闭后必须保留口令，否则重连后密钥不一致");
    }

    @Test
    void closeStopsFurtherReconnects() {
        ImClient client = new ImClient();
        client.connect("127.0.0.1", 1, "ROOM", "secret", "ada");
        client.close();

        assertEquals(ImClient.LinkState.DISCONNECTED, client.linkState());
        assertFalse(client.isConnected());
    }

    @Test
    void pingPongFromCurrentSessionIsAccepted() {
        Recorder recorder = new Recorder();
        ImClient client = recorder.client();
        client.beginConnectionForTest(5L);
        ImClient.SessionListener current = client.newSessionListener(5L);
        StubWebSocket socket = new StubWebSocket();

        current.onPing(socket, ByteBuffer.wrap(new byte[]{1}));
        current.onPong(socket, ByteBuffer.wrap(new byte[]{1}));

        assertEquals(ImClient.LinkState.CONNECTING, client.linkState());
        assertEquals(2, socket.requestCount);
    }

    // ─── 看门狗：链路存活检查 ───────────────────────────────

    @Test
    void idleWithinThresholdKeepsLinkAlive() {
        Recorder recorder = new Recorder();
        ImClient client = recorder.client();
        client.markConnectedForTest(3L);
        // 阈值内（少 1 秒）：正常收到 pong 的状态
        client.backdateInboundForTest(ImClient.LINK_IDLE_TIMEOUT_MS - 1_000);

        client.runKeepaliveTick();

        assertEquals(ImClient.LinkState.CONNECTED, client.linkState(), "阈值内不应判定死亡");
        assertFalse(recorder.hasClosed());
    }

    @Test
    void idleBeyondThresholdDeclaresLinkDead() {
        Recorder recorder = new Recorder();
        ImClient client = recorder.client();
        client.markConnectedForTest(3L);
        // 阈值外（多 1 秒）：连续漏掉 3 个 pong 以上
        client.backdateInboundForTest(ImClient.LINK_IDLE_TIMEOUT_MS + 1_000);

        client.runKeepaliveTick();

        assertEquals(ImClient.LinkState.DISCONNECTED, client.linkState(),
                "静默超阈值应判定链路已死");
        assertTrue(recorder.hasClosed(), "应产生 Closed 事件以触发重连按钮");
        assertTrue(recorder.events.stream()
                        .filter(e -> e instanceof ImClient.Event.Closed)
                        .map(e -> ((ImClient.Event.Closed) e).reason())
                        .anyMatch(r -> r.contains("连接超时")),
                "断开原因应为连接超时");
    }

    @Test
    void watchdogIgnoredWhileHandshaking() {
        Recorder recorder = new Recorder();
        ImClient client = recorder.client();
        // CONNECTING 期间：握手有自己的 12 秒超时，看门狗不适用
        client.beginConnectionForTest(3L);
        client.backdateInboundForTest(ImClient.LINK_IDLE_TIMEOUT_MS + 5_000);

        client.runKeepaliveTick();

        assertEquals(ImClient.LinkState.CONNECTING, client.linkState(),
                "握手阶段不应由看门狗判死");
        assertFalse(recorder.hasClosed());
    }

    @Test
    void watchdogIgnoredWhenDisconnected() {
        Recorder recorder = new Recorder();
        ImClient client = recorder.client();
        client.markConnectedForTest(3L);
        client.close();
        client.backdateInboundForTest(ImClient.LINK_IDLE_TIMEOUT_MS + 5_000);

        client.runKeepaliveTick();

        // 关闭后 registered=false，tick 直接返回；不应再发 Closed
        assertEquals(ImClient.LinkState.DISCONNECTED, client.linkState());
        assertFalse(recorder.events.stream()
                        .filter(e -> e instanceof ImClient.Event.Closed)
                        .map(e -> ((ImClient.Event.Closed) e).reason())
                        .anyMatch(r -> r.contains("连接超时")),
                "已主动关闭的连接不应再触发看门狗判死");
    }

    @Test
    void inboundFrameRefreshesIdleClock() {
        Recorder recorder = new Recorder();
        ImClient client = recorder.client();
        client.markConnectedForTest(3L);
        // 先回拨到超时，再模拟收到一帧（pong）
        client.backdateInboundForTest(ImClient.LINK_IDLE_TIMEOUT_MS + 1_000);
        ImClient.SessionListener current = client.newSessionListener(3L);
        current.onPong(new StubWebSocket(), ByteBuffer.wrap(new byte[]{1}));

        client.runKeepaliveTick();

        assertEquals(ImClient.LinkState.CONNECTED, client.linkState(),
                "收到入站帧后看门狗应重新计时");
    }

    private static String rootMessage(CompletableFuture<Void> future) {
        try {
            future.join();
            return "";
        } catch (Exception e) {
            Throwable cur = e;
            while (cur.getCause() != null && cur != cur.getCause()) {
                cur = cur.getCause();
            }
            String message = cur.getMessage();
            return message == null ? "" : message;
        }
    }
}
