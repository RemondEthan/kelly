package com.mordor.kelly.service;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * 测试用的 WebSocket 桩。
 *
 * <p>记录调用次数，不做任何实际的 IO。用于验证 {@link ImClient.SessionListener}
 * 在过期连接上的行为——过期回调应当只记录调用，不触碰客户端状态。</p>
 */
final class StubWebSocket implements WebSocket {

    /** request 调用次数 */
    int requestCount;
    /** abort 调用次数 */
    int abortCount;

    @Override
    public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public void request(long n) {
        requestCount++;
    }

    @Override
    public String getSubprotocol() {
        return "";
    }

    @Override
    public boolean isOutputClosed() {
        return false;
    }

    @Override
    public boolean isInputClosed() {
        return false;
    }

    @Override
    public void abort() {
        abortCount++;
    }
}
