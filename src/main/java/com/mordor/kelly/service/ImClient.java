package com.mordor.kelly.service;

import com.mordor.kelly.common.Diagnostics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 对 KServer 的 WebSocket 会话：握手后发 register，收到 registered 再派生密钥。
 * 每 5 秒发 ping + 加密保活（服务端约 90 秒无入站即踢）。
 */
public final class ImClient implements WebSocket.Listener {

    public static final String KEEPALIVE = "\u0001";

    public sealed interface Event {
        record Registered(int userId, String padding) implements Event {}
        record Chat(String username, String plaintext) implements Event {}
        record PeerJoined(int userId, String username) implements Event {}
        record PeerLeft(int userId, String username) implements Event {}
        record ServerError(String message) implements Event {}
        record Closed(String reason) implements Event {}
        record DecryptFailed(String username) implements Event {}
        record PeerAvatar(int userId, String username, byte[] png) implements Event {}
        record Image(String username, String plaintext) implements Event {}
        record ImageChunk(String username, String plaintext) implements Event {}
    }

    private final SavedUserIdService savedUserIds;
    private final CryptoService crypto = new CryptoService();
    private final CopyOnWriteArrayList<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<Integer, String> roster = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, byte[]> avatars = new ConcurrentHashMap<>();
    private final StringBuilder textBuf = new StringBuilder();
    private final ScheduledExecutorService keepalive =
            Executors.newSingleThreadScheduledExecutor(r -> daemon("kelly-keepalive", r));

    private final ExecutorService httpExec =
            Executors.newCachedThreadPool(r -> daemon("kelly-http", r));
    private final ExecutorService sendExec =
            Executors.newSingleThreadExecutor(r -> daemon("kelly-ws-send", r));

    private final HttpClient http = HttpClient.newBuilder()
            .executor(httpExec)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private WebSocket socket;
    private String username = "";
    private String password = "";
    private String imCode = "";
    private volatile String avatarPlaintext;
    private volatile boolean registered;
    private volatile boolean closed = true;
    private CompletableFuture<Void> handshake = new CompletableFuture<>();
    private ScheduledFuture<?> keepaliveTask;

    public ImClient() {
        this(new SavedUserIdService());
    }

    ImClient(SavedUserIdService savedUserIds) {
        this.savedUserIds = savedUserIds;
    }

    public String imCode() {
        return imCode;
    }

    public String password() {
        return password;
    }

    public void addListener(Consumer<Event> listener) {
        listeners.add(listener);
    }

    public Map<Integer, String> roster() {
        return Map.copyOf(roster);
    }

    public Map<Integer, byte[]> avatars() {
        return Map.copyOf(avatars);
    }

    public void setAvatarPlaintext(String base64Png) {
        this.avatarPlaintext = base64Png;
        if (registered && base64Png != null && !base64Png.isBlank()) {
            publishAvatar();
        }
    }

    public CompletableFuture<Void> connect(String host, int port, String imCode,
                                           String password, String username) {
        close();
        this.closed = false;
        this.username = username;
        this.password = password;
        this.imCode = imCode;
        this.registered = false;
        this.handshake = new CompletableFuture<>();
        roster.clear();
        avatars.clear();

        URI uri = URI.create("ws://" + host + ":" + port + "/");
        Diagnostics.log("ws", "connect %s user=%s", uri, username);
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .buildAsync(uri, this)
                .thenAccept(ws -> {
                    this.socket = ws;
                    Diagnostics.log("ws", "socket open, enqueue register");
                    int claimed = savedUserIds.get(imCode, username);
                    enqueueSend(Protocol.register(imCode, username, claimed), false);
                })
                .exceptionally(ex -> {
                    Diagnostics.error("ws", "connect failed: %s", unwrap(ex).toString());
                    handshake.completeExceptionally(unwrap(ex));
                    return null;
                });

        return handshake.orTimeout(12, TimeUnit.SECONDS);
    }

    public void sendChat(String plaintext) {
        sendEncrypted("text", plaintext);
    }

    public void sendImage(String metaPlaintext, List<String> chunkPlaintexts) {
        sendEncrypted("image", metaPlaintext);
        if (chunkPlaintexts == null) {
            return;
        }
        for (String chunk : chunkPlaintexts) {
            sendEncrypted("image_chunk", chunk);
        }
    }

    public void close() {
        Diagnostics.log("ws", "close requested registered=%s", registered);
        closed = true;
        stopKeepalive();
        registered = false;
        roster.clear();
        avatars.clear();
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            CompletableFuture<Void> closed = new CompletableFuture<>();
            sendExec.execute(() -> {
                long t0 = System.nanoTime();
                try {
                    ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
                    Diagnostics.log("ws", "sendClose done %dms", Diagnostics.elapsedMs(t0));
                } catch (Exception e) {
                    Diagnostics.error("ws", "sendClose failed: %s", e.toString());
                } finally {
                    closed.complete(null);
                }
            });
            try {
                closed.get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // 退出路径：尽力发送 close，超时也继续
            }
        }
        if (!handshake.isDone()) {
            handshake.completeExceptionally(new IllegalStateException("连接已关闭"));
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        Diagnostics.log("ws", "onOpen");
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textBuf.append(data);
        if (last) {
            String raw = textBuf.toString();
            textBuf.setLength(0);
            long t0 = System.nanoTime();
            handleRaw(raw);
            long ms = Diagnostics.elapsedMs(t0);
            if (ms >= 50) {
                Diagnostics.log("ws", "handleRaw slow %dms len=%d", ms, raw.length());
            }
        }
        webSocket.request(1);
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        Diagnostics.log("ws", "onClose code=%d reason=%s", statusCode, reason);
        stopKeepalive();
        registered = false;
        String msg = reason == null || reason.isBlank() ? "连接已断开" : reason;
        if (!handshake.isDone()) {
            handshake.completeExceptionally(new IllegalStateException(msg));
        }
        emit(new Event.Closed(msg));
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        Diagnostics.error("ws", "onError: %s", error == null ? "null" : error.toString());
        if (!handshake.isDone()) {
            handshake.completeExceptionally(unwrap(error));
        }
        emit(new Event.Closed(error.getMessage() == null ? "WebSocket 错误" : error.getMessage()));
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        webSocket.request(1);
        return WebSocket.Listener.super.onBinary(webSocket, data, last);
    }

    private void handleRaw(String raw) {
        Protocol.Incoming msg = Protocol.parse(raw);
        Diagnostics.log("ws", "recv type=%s user=%s id=%d listeners=%d roster=%d",
                msg.type(), msg.username(), msg.userId(), listeners.size(), roster.size());
        switch (msg.type()) {
            case "registered" -> {
                if (closed) {
                    return;
                }
                crypto.initialize(password, msg.padding());
                registered = true;
                savedUserIds.put(this.imCode, this.username, msg.userId());
                startKeepalive();
                publishAvatar();
                Diagnostics.log("ws", "handshake complete userId=%d roster=%s",
                        msg.userId(), roster.values());
                handshake.complete(null);
                emit(new Event.Registered(msg.userId(), msg.padding()));
            }
            case "avatar" -> {
                if (!crypto.isReady()) {
                    Diagnostics.warn("ws", "drop avatar, crypto not ready");
                    return;
                }
                try {
                    String plain = crypto.decrypt(msg.content());
                    byte[] png = Base64.getDecoder().decode(plain);
                    avatars.put(msg.userId(), png);
                    Diagnostics.log("ws", "recv avatar userId=%d user=%s bytes=%d",
                            msg.userId(), msg.username(), png.length);
                    emit(new Event.PeerAvatar(msg.userId(), msg.username(), png));
                } catch (Exception e) {
                    Diagnostics.warn("ws", "avatar decode failed user=%s: %s", msg.username(), e.toString());
                }
            }
            case "text" -> {
                if (!crypto.isReady()) {
                    Diagnostics.warn("ws", "drop text, crypto not ready");
                    return;
                }
                try {
                    String plain = crypto.decrypt(msg.content());
                    if (KEEPALIVE.equals(plain)) {
                        return;
                    }
                    emit(new Event.Chat(msg.username(), plain));
                } catch (CryptoService.CryptoException e) {
                    emit(new Event.DecryptFailed(msg.username()));
                }
            }
            case "image" -> handleEncrypted(msg, Event.Image::new);
            case "image_chunk" -> handleEncrypted(msg, Event.ImageChunk::new);
            case "peer_connected" -> {
                roster.put(msg.userId(), msg.username());
                emit(new Event.PeerJoined(msg.userId(), msg.username()));
            }
            case "peer_disconnected" -> {
                roster.remove(msg.userId());
                emit(new Event.PeerLeft(msg.userId(), msg.username()));
            }
            case "error" -> {
                Diagnostics.error("ws", "server error: %s", msg.message());
                if (!handshake.isDone()) {
                    handshake.completeExceptionally(new IllegalStateException(msg.message()));
                }
                emit(new Event.ServerError(msg.message()));
            }
            default -> Diagnostics.warn("ws", "unknown type ignored: %s", msg.type());
        }
    }

    private void publishAvatar() {
        String plain = avatarPlaintext;
        if (!registered || !crypto.isReady() || plain == null || plain.isBlank()) {
            return;
        }
        String cipher = crypto.encrypt(plain);
        Diagnostics.log("ws", "publish avatar cipherLen=%d", cipher.length());
        enqueueSend(Protocol.avatar(cipher), true);
    }

    private void sendPing() {
        sendExec.execute(() -> {
            WebSocket ws = socket;
            if (ws == null || closed) {
                return;
            }
            try {
                ws.sendPing(ByteBuffer.wrap(new byte[]{1})).join();
            } catch (Exception e) {
                Diagnostics.warn("ws", "ping failed: %s", e.toString());
            }
        });
    }

    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return WebSocket.Listener.super.onPing(webSocket, message);
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return WebSocket.Listener.super.onPong(webSocket, message);
    }

    private void handleEncrypted(Protocol.Incoming msg, BiFunction<String, String, Event> factory) {
        if (!crypto.isReady()) {
            Diagnostics.warn("ws", "drop %s, crypto not ready", msg.type());
            return;
        }
        try {
            String plain = crypto.decrypt(msg.content());
            emit(factory.apply(msg.username(), plain));
        } catch (CryptoService.CryptoException e) {
            emit(new Event.DecryptFailed(msg.username()));
        }
    }

    private void sendEncrypted(String type, String plaintext) {
        if (!registered || !crypto.isReady()) {
            throw new IllegalStateException("尚未注册成功");
        }
        String cipher = crypto.encrypt(plaintext);
        String payload = switch (type) {
            case "image" -> Protocol.image(cipher, username);
            case "image_chunk" -> Protocol.imageChunk(cipher, username);
            default -> Protocol.text(cipher, username);
        };
        enqueueSend(payload, true);
    }

    private void enqueueSend(String payload, boolean requireRegistered) {
        sendExec.execute(() -> {
            WebSocket ws = socket;
            if (ws == null || closed || (requireRegistered && !registered)) {
                Diagnostics.warn("ws", "send dropped socket=%s closed=%s registered=%s",
                        ws != null, closed, registered);
                return;
            }
            long t0 = System.nanoTime();
            try {
                Diagnostics.log("ws", "sendText begin len=%d", payload.length());
                ws.sendText(payload, true).join();
                Diagnostics.log("ws", "sendText done %dms", Diagnostics.elapsedMs(t0));
            } catch (Exception e) {
                Diagnostics.error("ws", "sendText failed after %dms: %s", Diagnostics.elapsedMs(t0), e.toString());
            }
        });
    }

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private void startKeepalive() {
        stopKeepalive();
        keepaliveTask = keepalive.scheduleAtFixedRate(() -> {
            try {
                if (registered) {
                    sendPing();
                    sendEncrypted("text", KEEPALIVE);
                }
            } catch (Exception ignored) {
                // 保活失败由关闭回调处理
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void stopKeepalive() {
        if (keepaliveTask != null) {
            keepaliveTask.cancel(false);
            keepaliveTask = null;
        }
    }

    private void emit(Event event) {
        Diagnostics.log("ws", "emit %s listeners=%d", event.getClass().getSimpleName(), listeners.size());
        for (Consumer<Event> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                Diagnostics.error("ws", "listener failed %s: %s", event.getClass().getSimpleName(), e.toString());
            }
        }
    }

    private static Throwable unwrap(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur != cur.getCause()) {
            cur = cur.getCause();
        }
        return cur;
    }
}
