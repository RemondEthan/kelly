package com.mordor.kelly.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ImClient 断线重连的集成测试。
 *
 * <p>起一个真实的 kserver，连接后杀掉服务端，再手动触发 reconnect()，
 * 重启服务端，验证客户端能重新注册并恢复加密通信。</p>
 *
 * <p>需要服务端二进制；找不到时整类跳过。</p>
 */
class ImClientReconnectIT {

    /** 服务端端口 */
    private static final int PORT = 13011;

    static boolean serverBinaryExists() {
        return serverBinary() != null;
    }

    @Test
    @EnabledIf("serverBinaryExists")
    void reconnectsAfterServerRestart() throws Exception {
        Path binary = serverBinary();
        Process server = startServer(binary);
        try {
            ImClient client = new ImClient();
            CompletableFuture<String> chat = new CompletableFuture<>();
            client.addListener(event -> {
                if (event instanceof ImClient.Event.Chat received) {
                    chat.complete(received.plaintext());
                }
            });

            client.connect("127.0.0.1", PORT, "ITROOM", "shared", "Alice")
                    .orTimeout(5, TimeUnit.SECONDS).join();
            assertTrue(client.isConnected(), "首次连接应成功");
            assertEquals(ImClient.LinkState.CONNECTED, client.linkState());

            // 杀掉服务端：客户端应检测到断开
            server.destroyForcibly();
            server.waitFor(5, TimeUnit.SECONDS);
            awaitState(client, ImClient.LinkState.DISCONNECTED, 10_000);

            // 重启服务端
            Process restarted = startServer(binary);
            try {
                // 手动触发重连
                client.reconnect().orTimeout(15, TimeUnit.SECONDS).join();

                assertTrue(client.isConnected(), "重连应成功");
                assertEquals(ImClient.LinkState.CONNECTED, client.linkState());

                // 重连后仍能正常收发加密消息
                ImClient peer = new ImClient();
                peer.connect("127.0.0.1", PORT, "ITROOM", "shared", "Bob")
                        .orTimeout(5, TimeUnit.SECONDS).join();
                try {
                    peer.sendChat("hello after reconnect");
                    assertEquals("hello after reconnect", chat.get(10, TimeUnit.SECONDS));
                } finally {
                    peer.close();
                }
            } finally {
                restarted.destroyForcibly();
            }
        } finally {
            server.destroyForcibly();
        }
    }

    @Test
    @EnabledIf("serverBinaryExists")
    void reconnectFailsWhenServerIsDown() throws Exception {
        Path binary = serverBinary();
        Process server = startServer(binary);
        ImClient client = new ImClient();
        try {
            client.connect("127.0.0.1", PORT, "ITROOM", "shared", "Alice")
                    .orTimeout(5, TimeUnit.SECONDS).join();
            server.destroyForcibly();
            server.waitFor(5, TimeUnit.SECONDS);
            awaitState(client, ImClient.LinkState.DISCONNECTED, 10_000);

            // 服务端不在时重连应失败，客户端保持 DISCONNECTED
            try {
                client.reconnect().orTimeout(15, TimeUnit.SECONDS).join();
            } catch (Exception expected) {
                // 预期失败
            }
            // join() 返回时 whenComplete 回调可能尚未跑完，轮询等待状态落定
            awaitState(client, ImClient.LinkState.DISCONNECTED, 5_000);
            assertEquals(ImClient.LinkState.DISCONNECTED, client.linkState(),
                    "重连失败后应保持断开状态");
        } finally {
            client.close();
            server.destroyForcibly();
        }
    }

    @Test
    @EnabledIf("serverBinaryExists")
    void reconnectKeepsKeyConsistentWithPeer() throws Exception {
        Path binary = serverBinary();
        Process server = startServer(binary);
        try {
            ImClient alice = new ImClient();
            CompletableFuture<String> inbox = new CompletableFuture<>();
            alice.addListener(event -> {
                if (event instanceof ImClient.Event.Chat chat) {
                    inbox.complete(chat.plaintext());
                }
            });
            ImClient bob = new ImClient();
            try {
                alice.connect("127.0.0.1", PORT, "KEYROOM", "shared", "Alice")
                        .orTimeout(5, TimeUnit.SECONDS).join();
                bob.connect("127.0.0.1", PORT, "KEYROOM", "shared", "Bob")
                        .orTimeout(5, TimeUnit.SECONDS).join();

                // alice 断开再重连：重连后双方密钥必须仍然一致
                alice.close();
                alice.reconnect().orTimeout(15, TimeUnit.SECONDS).join();
                assertTrue(alice.isConnected(), "重连应成功");

                // bob 向 alice 发消息：能解开说明密钥一致
                bob.sendChat("secret after reconnect");
                assertEquals("secret after reconnect", inbox.get(10, TimeUnit.SECONDS),
                        "重连后双方密钥必须一致，消息应能解密");
            } finally {
                bob.close();
                alice.close();
            }
        } finally {
            server.destroyForcibly();
        }
    }

    /** 等待客户端进入指定链路状态 */
    private static void awaitState(ImClient client, ImClient.LinkState expected, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (client.linkState() == expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待链路状态 " + expected + " 超时，当前为 " + client.linkState());
    }

    /** 启动服务端进程 */
    private static Process startServer(Path binary) throws Exception {
        Process process = new ProcessBuilder(binary.toString(), String.valueOf(PORT))
                .redirectErrorStream(true)
                .start();
        // 等待端口就绪
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", PORT)) {
                if (socket.isConnected()) {
                    return process;
                }
            } catch (Exception notYet) {
                Thread.sleep(50);
            }
        }
        process.destroyForcibly();
        throw new AssertionError("服务端启动超时");
    }

    /** 查找服务端二进制 */
    private static Path serverBinary() {
        Path[] candidates = {
                Path.of("server/build/kserver"),
                Path.of("server/dist/kserver"),
                Path.of("server/cmake-build-debug/kserver"),
                Path.of("../kelly-server/build/kserver"),
                Path.of("../kelly-server/dist/kserver")
        };
        for (Path p : candidates) {
            if (Files.isExecutable(p)) {
                return p.toAbsolutePath();
            }
        }
        String env = System.getenv("KSERVER_BIN");
        if (env != null && !env.isBlank() && Files.isExecutable(Path.of(env))) {
            return Path.of(env).toAbsolutePath();
        }
        return null;
    }
}
