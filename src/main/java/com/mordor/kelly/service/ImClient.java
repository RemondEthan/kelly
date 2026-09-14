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
 * WebSocket IM 客户端 - 核心网络层
 *
 * 本类实现了 WebSocket.Listener 接口，负责与 KServer 服务器进行实时通信。
 * 主要功能包括：
 * 1. 建立 WebSocket 连接并完成握手注册
 * 2. 加密/解密消息（使用 AES-256-GCM）
 * 3. 维护在线用户列表（roster）
 * 4. 缓存用户头像
 * 5. 保活机制防止连接超时
 * 6. 事件分发系统（通过监听器模式）
 *
 * 线程安全设计：
 * - 使用 ConcurrentHashMap 存储在线用户和头像
 * - 使用 CopyOnWriteArrayList 存储监听器，支持并发访问
 * - 使用 ScheduledExecutorService 进行保活任务调度
 * - 使用专用的 sendExec 线程池处理消息发送，避免并发写入
 */

/**
 * 对 KServer 的 WebSocket 会话：握手后发 register，收到 registered 再派生密钥。
 * 每 5 秒发 ping + 加密保活（服务端约 90 秒无入站即踢）。
 */
public final class ImClient implements WebSocket.Listener {

    /**
     * 保活消息标识符
     * 使用 Unicode 控制字符 \u0001 作为保活消息的明文标识
     * 接收到此消息时会静默丢弃，不触发 Chat 事件
     */
    public static final String KEEPALIVE = "\u0001";

    /**
     * 事件密封接口 - 定义所有可能的事件类型
     * 使用 Java 17+ 的密封接口（sealed interface）和记录类（record）
     * 限制事件类型只能是下面定义的几种，提高类型安全性
     */
    public sealed interface Event {
        /**
         * 注册成功事件
         * 当客户端成功向服务器注册后触发
         * @param userId 服务器分配的用户ID
         * @param padding 用于密钥派生的填充字符串
         */
        record Registered(int userId, String padding) implements Event {}

        /**
         * 聊天消息事件
         * 收到其他用户发送的文本消息时触发
         * @param username 发送者用户名
         * @param plaintext 解密后的明文消息
         */
        record Chat(String username, String plaintext) implements Event {}

        /**
         * 用户加入事件
         * 当其他用户连接到同一聊天室时触发
         * @param userId 用户ID
         * @param username 用户名
         */
        record PeerJoined(int userId, String username) implements Event {}

        /**
         * 用户离开事件
         * 当其他用户断开连接时触发
         * @param userId 用户ID
         * @param username 用户名
         */
        record PeerLeft(int userId, String username) implements Event {}

        /**
         * 服务器错误事件
         * 当服务器返回错误消息时触发
         * @param message 错误消息内容
         */
        record ServerError(String message) implements Event {}

        /**
         * 连接关闭事件
         * 当 WebSocket 连接断开时触发
         * @param reason 关闭原因
         */
        record Closed(String reason) implements Event {}

        /**
         * 解密失败事件
         * 当无法解密收到的消息时触发（可能是密码不匹配）
         * @param username 发送者用户名
         */
        record DecryptFailed(String username) implements Event {}

        /**
         * 用户头像事件
         * 收到其他用户的头像数据时触发
         * @param userId 用户ID
         * @param username 用户名
         * @param png 头像的 PNG 图片字节数组
         */
        record PeerAvatar(int userId, String username, byte[] png) implements Event {}

        /**
         * 图片消息事件
         * 收到图片元数据时触发
         * @param username 发送者用户名
         * @param plaintext 解密后的图片元数据（JSON格式）
         */
        record Image(String username, String plaintext) implements Event {}

        /**
         * 图片分片事件
         * 收到图片分片数据时触发
         * @param username 发送者用户名
         * @param plaintext 解密后的分片数据（JSON格式）
         */
        record ImageChunk(String username, String plaintext) implements Event {}
    }

    /**
     * 用户ID持久化服务
     * 用于记住服务器分配的用户ID，避免每次连接都重新分配
     */
    private final SavedUserIdService savedUserIds;

    /**
     * 加密服务
     * 负责消息的加密和解密（AES-256-GCM）
     */
    private final CryptoService crypto = new CryptoService();

    /**
     * 事件监听器列表
     * 使用 CopyOnWriteArrayList 实现线程安全
     * 支持在迭代过程中添加或删除监听器
     */
    private final CopyOnWriteArrayList<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

    /**
     * 在线用户列表（花名册）
     * 键：用户ID，值：用户名
     * 使用 ConcurrentHashMap 保证线程安全
     */
    private final ConcurrentHashMap<Integer, String> roster = new ConcurrentHashMap<>();

    /**
     * 用户头像缓存
     * 键：用户ID，值：头像的 PNG 图片字节数组
     * 使用 ConcurrentHashMap 保证线程安全
     */
    private final ConcurrentHashMap<Integer, byte[]> avatars = new ConcurrentHashMap<>();

    /**
     * 文本消息缓冲区
     * 用于处理分片传输的文本消息
     * 当收到完整的文本消息（last=true）时，一次性处理
     */
    private final StringBuilder textBuf = new StringBuilder();

    /**
     * 保活任务调度器
     * 单线程调度器，负责定期发送保活消息
     * 使用守护线程，不会阻止 JVM 退出
     */
    private final ScheduledExecutorService keepalive =
            Executors.newSingleThreadScheduledExecutor(r -> daemon("kelly-keepalive", r));

    /**
     * HTTP 客户端线程池
     * 用于 WebSocket 连接建立
     * 使用缓存线程池，空闲线程会自动回收
     */
    private final ExecutorService httpExec =
            Executors.newCachedThreadPool(r -> daemon("kelly-http", r));

    /**
     * 消息发送线程池
     * 单线程执行器，确保消息按顺序发送
     * 避免并发写入导致的消息乱序
     */
    private final ExecutorService sendExec =
            Executors.newSingleThreadExecutor(r -> daemon("kelly-ws-send", r));

    /**
     * HTTP 客户端
     * 用于建立 WebSocket 连接
     * 配置了 8 秒的连接超时
     */
    private final HttpClient http = HttpClient.newBuilder()
            .executor(httpExec)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /**
     * WebSocket 连接对象
     * 用于发送和接收消息
     * 在连接断开时会被设置为 null
     */
    private WebSocket socket;

    /**
     * 当前用户名
     * 用于发送消息时标识发送者
     */
    private String username = "";

    /**
     * 当前密码
     * 用于派生加密密钥
     */
    private String password = "";

    /**
     * 当前聊天室标识码
     * 用于区分不同的聊天室
     */
    private String imCode = "";

    /**
     * 本地用户的头像数据（Base64编码的PNG）
     * 使用 volatile 保证多线程可见性
     */
    private volatile String avatarPlaintext;

    /**
     * 注册状态标志
     * true 表示已成功向服务器注册
     * 使用 volatile 保证多线程可见性
     */
    private volatile boolean registered;

    /**
     * 连接关闭状态标志
     * true 表示连接已关闭或正在关闭
     * 使用 volatile 保证多线程可见性
     */
    private volatile boolean closed = true;

    /**
     * 握手完成的 Future
     * 用于等待注册完成
     * 注册成功时 complete(null)，失败时 completeExceptionally()
     */
    private CompletableFuture<Void> handshake = new CompletableFuture<>();

    /**
     * 保活任务句柄
     * 用于取消保活任务
     */
    private ScheduledFuture<?> keepaliveTask;

    /**
     * 默认构造方法
     * 创建默认的 SavedUserIdService 实例
     */
    public ImClient() {
        this(new SavedUserIdService());
    }

    /**
     * 带依赖注入的构造方法
     * 用于测试时注入模拟的 SavedUserIdService
     * @param savedUserIds 用户ID持久化服务
     */
    ImClient(SavedUserIdService savedUserIds) {
        this.savedUserIds = savedUserIds;
    }

    /**
     * 获取当前聊天室标识码
     * @return 聊天室标识码
     */
    public String imCode() {
        return imCode;
    }

    /**
     * 获取当前密码
     * @return 密码字符串
     */
    public String password() {
        return password;
    }

    /**
     * 添加事件监听器
     * 当有事件发生时，所有注册的监听器都会被通知
     * @param listener 事件监听器，接收 Event 对象
     */
    public void addListener(Consumer<Event> listener) {
        listeners.add(listener);
    }

    /**
     * 获取当前在线用户列表的副本
     * 返回不可变的 Map 副本，避免并发修改问题
     * @return 用户ID到用户名的映射
     */
    public Map<Integer, String> roster() {
        return Map.copyOf(roster);
    }

    /**
     * 获取当前头像缓存的副本
     * 返回不可变的 Map 副本，避免并发修改问题
     * @return 用户ID到头像数据的映射
     */
    public Map<Integer, byte[]> avatars() {
        return Map.copyOf(avatars);
    }

    /**
     * 设置本地用户的头像数据
     * 如果已注册且数据有效，会立即发布到服务器
     * @param base64Png Base64编码的PNG头像数据
     */
    public void setAvatarPlaintext(String base64Png) {
        this.avatarPlaintext = base64Png;
        if (registered && base64Png != null && !base64Png.isBlank()) {
            publishAvatar();
        }
    }

    /**
     * 连接到服务器
     * 连接流程：
     * 1. 关闭现有连接
     * 2. 建立新的 WebSocket 连接
     * 3. 连接成功后发送 register 消息
     * 4. 等待服务器返回 registered 消息
     * 5. 初始化加密密钥
     * 6. 启动保活机制
     *
     * @param host 服务器地址
     * @param port 服务器端口
     * @param imCode 聊天室标识码
     * @param password 密码，用于派生加密密钥
     * @param username 用户名
     * @return 握手完成的 Future，超时12秒
     */
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
                    // 获取之前保存的用户ID（如果有）
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

    /**
     * 发送聊天消息
     * 消息会被加密后发送到服务器
     * @param plaintext 明文消息内容
     */
    public void sendChat(String plaintext) {
        sendEncrypted("text", plaintext);
    }

    /**
     * 发送图片消息
     * 先发送图片元数据，然后发送所有分片
     * @param metaPlaintext 图片元数据（JSON格式，包含id、caption、mime等）
     * @param chunkPlaintexts 图片分片数据列表（可为null）
     */
    public void sendImage(String metaPlaintext, List<String> chunkPlaintexts) {
        sendEncrypted("image", metaPlaintext);
        if (chunkPlaintexts == null) {
            return;
        }
        for (String chunk : chunkPlaintexts) {
            sendEncrypted("image_chunk", chunk);
        }
    }

    /**
     * 关闭连接
     * 关闭流程：
     * 1. 设置关闭标志
     * 2. 停止保活任务
     * 3. 清空在线用户列表和头像缓存
     * 4. 发送 WebSocket 关闭帧
     * 5. 等待关闭完成（最多1秒）
     * 6. 如果握手未完成，标记为失败
     */
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

    /**
     * WebSocket 连接打开回调
     * 当 WebSocket 连接成功建立时调用
     * 请求接收第一条消息
     * @param webSocket WebSocket 连接对象
     */
    @Override
    public void onOpen(WebSocket webSocket) {
        Diagnostics.log("ws", "onOpen");
        webSocket.request(1);
    }

    /**
     * WebSocket 文本消息回调
     * 处理接收到的文本消息
     * 支持分片传输：当 last=false 时缓冲数据，last=true 时一次性处理完整消息
     * @param webSocket WebSocket 连接对象
     * @param data 消息数据
     * @param last 是否是消息的最后一片
     * @return 处理完成的 Future
     */
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

    /**
     * WebSocket 连接关闭回调
     * 当 WebSocket 连接断开时调用
     * 停止保活任务，清理状态，通知所有监听器
     * @param webSocket WebSocket 连接对象
     * @param statusCode 关闭状态码
     * @param reason 关闭原因
     * @return 处理完成的 Future
     */
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

    /**
     * WebSocket 错误回调
     * 当发生错误时调用
     * 如果握手未完成，标记为失败
     * 通知所有监听器
     * @param webSocket WebSocket 连接对象
     * @param error 错误对象
     */
    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        Diagnostics.error("ws", "onError: %s", error == null ? "null" : error.toString());
        if (!handshake.isDone()) {
            handshake.completeExceptionally(unwrap(error));
        }
        emit(new Event.Closed(error.getMessage() == null ? "WebSocket 错误" : error.getMessage()));
    }

    /**
     * WebSocket 二进制消息回调
     * 当前实现不处理二进制消息，直接请求下一条消息
     * @param webSocket WebSocket 连接对象
     * @param data 二进制数据
     * @param last 是否是消息的最后一片
     * @return 处理完成的 Future
     */
    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        webSocket.request(1);
        return WebSocket.Listener.super.onBinary(webSocket, data, last);
    }

    /**
     * 处理原始消息
     * 解析 JSON 消息，根据消息类型分发处理
     * 消息类型包括：
     * - registered: 注册成功
     * - avatar: 用户头像
     * - text: 聊天消息
     * - image: 图片元数据
     * - image_chunk: 图片分片
     * - peer_connected: 用户加入
     * - peer_disconnected: 用户离开
     * - error: 服务器错误
     *
     * @param raw 原始 JSON 字符串
     */
    private void handleRaw(String raw) {
        Protocol.Incoming msg = Protocol.parse(raw);
        Diagnostics.log("ws", "recv type=%s user=%s id=%d listeners=%d roster=%d",
                msg.type(), msg.username(), msg.userId(), listeners.size(), roster.size());
        switch (msg.type()) {
            case "registered" -> {
                if (closed) {
                    return;
                }
                // 使用服务器返回的 padding 初始化加密密钥
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
                    // 忽略保活消息
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

    /**
     * 发布本地用户头像
     * 加密头像数据并发送到服务器
     * 服务器会广播给其他在线用户
     */
    private void publishAvatar() {
        String plain = avatarPlaintext;
        if (!registered || !crypto.isReady() || plain == null || plain.isBlank()) {
            return;
        }
        String cipher = crypto.encrypt(plain);
        Diagnostics.log("ws", "publish avatar cipherLen=%d", cipher.length());
        enqueueSend(Protocol.avatar(cipher), true);
    }

    /**
     * 发送 WebSocket ping 消息
     * 用于检测连接是否存活
     * ping 消息内容为单个字节 {1}
     */
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

    /**
     * WebSocket ping 回调
     * 收到 ping 消息时请求下一条消息
     * @param webSocket WebSocket 连接对象
     * @param message ping 消息内容
     * @return 处理完成的 Future
     */
    @Override
    public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return WebSocket.Listener.super.onPing(webSocket, message);
    }

    /**
     * WebSocket pong 回调
     * 收到 pong 消息时请求下一条消息
     * @param webSocket WebSocket 连接对象
     * @param message pong 消息内容
     * @return 处理完成的 Future
     */
    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
        webSocket.request(1);
        return WebSocket.Listener.super.onPong(webSocket, message);
    }

    /**
     * 处理加密消息的通用方法
     * 解密消息内容，然后调用工厂函数创建对应的事件对象
     * @param msg 服务器消息
     * @param factory 事件工厂函数，接收用户名和明文，返回 Event 对象
     */
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

    /**
     * 加密并发送消息
     * 根据消息类型构造对应的协议消息
     * @param type 消息类型（text/image/image_chunk）
     * @param plaintext 明文消息内容
     */
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

    /**
     * 入队发送消息
     * 将消息发送任务提交到发送线程池
     * 确保消息按顺序发送
     * @param payload 待发送的 JSON 字符串
     * @param requireRegistered 是否要求已注册状态
     */
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

    /**
     * 创建守护线程
     * 守护线程不会阻止 JVM 退出
     * @param name 线程名称
     * @param r 线程执行的任务
     * @return 配置好的守护线程
     */
    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * 启动保活机制
     * 每 5 秒发送一次 ping 消息和加密的保活消息
     * 服务器约 90 秒无入站消息会踢人
     * 所以 5 秒间隔确保连接不会超时
     */
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

    /**
     * 停止保活任务
     * 取消正在执行的保活任务
     */
    private void stopKeepalive() {
        if (keepaliveTask != null) {
            keepaliveTask.cancel(false);
            keepaliveTask = null;
        }
    }

    /**
     * 发送事件到所有监听器
     * 遍历所有注册的监听器，逐个调用
     * 如果某个监听器抛出异常，会记录错误但不影响其他监听器
     * @param event 要发送的事件对象
     */
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

    /**
     * 解包异常链
     * 获取异常链中最底层的根异常
     * @param ex 异常对象
     * @return 根异常
     */
    private static Throwable unwrap(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur != cur.getCause()) {
            cur = cur.getCause();
        }
        return cur;
    }
}
