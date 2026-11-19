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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * WebSocket IM 客户端 - 核心网络层
 *
 * 负责与 KServer 服务器进行实时通信。主要功能包括：
 * 1. 建立 WebSocket 连接并完成握手注册
 * 2. 加密/解密消息（使用 AES-256-GCM）
 * 3. 维护在线用户列表（roster）
 * 4. 缓存用户头像
 * 5. 保活机制防止连接超时
 * 6. 事件分发系统（通过监听器模式）
 * 7. 断线后由用户手动触发重连
 *
 * <h3>连接代（epoch）隔离</h3>
 * <p>每次建连分配一个单调递增的 epoch，并把 {@link WebSocket.Listener} 绑定到
 * 该次连接的内部类 {@link SessionListener}。旧连接的迟到回调（onClose/onError/onText）
 * 会发现自己的 epoch 已不是当前 epoch，直接丢弃，不触碰新连接的状态。
 * 这解决了共享监听器时旧连接 {@code onClose} 停掉新连接保活任务的竞态。</p>
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
public final class ImClient {

    /**
     * 保活消息标识符
     * 使用 Unicode 控制字符 \u0001 作为保活消息的明文标识
     * 接收到此消息时会静默丢弃，不触发 Chat 事件
     */
    public static final String KEEPALIVE = "\u0001";

    /**
     * 链路状态枚举。
     *
     * <p>描述当前 WebSocket 链路的三个阶段。纯手动重连方案下没有自动重试，
     * 因此不需要 RETRY_WAIT 之类的等待态。</p>
     */
    public enum LinkState {
        /** 未连接（初始状态，或连接已断开） */
        DISCONNECTED,
        /** 正在建连/握手中 */
        CONNECTING,
        /** 已注册，链路可用 */
        CONNECTED
    }

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
         * 链路状态变化事件
         * 当连接状态在 DISCONNECTED / CONNECTING / CONNECTED 之间迁移时触发
         * @param state 新的链路状态
         */
        record LinkStateChanged(LinkState state) implements Event {}

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
    private volatile WebSocket socket;

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
     * 服务器地址
     * 断线后重连需要复用，因此必须留存
     */
    private volatile String host = "";

    /**
     * 服务器端口
     * 断线后重连需要复用，因此必须留存
     */
    private volatile int port;

    /**
     * 连接代计数器
     * 每次建连取一个自增的值作为该次连接的 epoch
     */
    private final AtomicLong epochSeq = new AtomicLong();

    /**
     * 当前生效的连接代
     * 旧连接的迟到回调发现自己的 epoch 与此不符即丢弃
     */
    private volatile long epoch;

    /**
     * 当前链路状态
     */
    private volatile LinkState linkState = LinkState.DISCONNECTED;

    /**
     * 链路存活看门狗阈值（毫秒）。
     *
     * <p>客户端每 5 秒发一个 WS ping，服务端按 RFC 6455 必回 pong；
     * 连续漏掉 3 个 pong 即判定链路已死。阈值取 20 秒。</p>
     */
    static final long LINK_IDLE_TIMEOUT_MS = 20_000;

    /**
     * 最近一次收到入站帧的时间戳（{@code System.nanoTime()}，单调时钟）。
     *
     * <p>WS IO 线程写入、保活线程读取，用 volatile 保证可见性。</p>
     */
    private volatile long lastInboundNanos = System.nanoTime();

    private volatile long lastKeepaliveTickNanos = System.nanoTime();
    private volatile long watchdogWindowStartNanos = lastKeepaliveTickNanos;

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
     * 获取当前链路状态。
     * @return 链路状态
     */
    public LinkState linkState() {
        return linkState;
    }

    /**
     * 当前链路是否可用（已注册）。
     * @return 已连接返回 true
     */
    public boolean isConnected() {
        return linkState == LinkState.CONNECTED;
    }

    /**
     * 切换链路状态并广播状态变化事件。
     *
     * <p>状态未变化时不重复广播，避免 UI 抖动。</p>
     *
     * @param next 新的链路状态
     */
    private void setState(LinkState next) {
        if (linkState == next) {
            return;
        }
        linkState = next;
        Diagnostics.log("ws", "link state -> %s", next);
        emit(new Event.LinkStateChanged(next));
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
     * 1. 记录服务器地址与凭据，供断线后重连复用
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
        this.host = host == null ? "" : host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.imCode = imCode;
        return openConnection();
    }

    /**
     * 重新连接到服务器。
     *
     * <p>复用 {@link #connect(String, int, String, String, String)} 记录下来的服务器地址
     * 与凭据，在同一个客户端实例上重新建连。因此加密密钥、头像、聊天历史都不需要迁移。</p>
     *
     * <p>没有记录过服务器地址时（例如从未成功连接过）立即失败。</p>
     *
     * @return 握手完成的 Future，超时12秒
     */
    public CompletableFuture<Void> reconnect() {
        if (host.isBlank() || port <= 0) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("未记录服务器地址，无法重连"));
        }
        Diagnostics.log("ws", "reconnect %s:%d user=%s", host, port, username);
        return openConnection();
    }

    /**
     * 真正建连的内部方法。
     *
     * <p>登录和手动重连共用此路径：分配新的 epoch、释放旧 socket、
     * 清空幽灵成员与头像、发出 register 并等待 registered。</p>
     *
     * @return 握手完成的 Future，超时12秒
     */
    private CompletableFuture<Void> openConnection() {
        final long myEpoch = epochSeq.incrementAndGet();
        this.epoch = myEpoch;
        // 换连接：释放旧 socket，但保留凭据与加密配置
        closeSocket();
        this.closed = false;
        this.registered = false;
        this.handshake = new CompletableFuture<>();
        roster.clear();
        avatars.clear();
        setState(LinkState.CONNECTING);

        URI uri = URI.create("ws://" + host + ":" + port + "/");
        Diagnostics.log("ws", "connect %s user=%s epoch=%d", uri, username, myEpoch);
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .buildAsync(uri, new SessionListener(myEpoch))
                .thenAccept(ws -> {
                    if (myEpoch != epoch) {
                        // 已被更新的连接取代，直接丢弃这个半开连接
                        Diagnostics.warn("ws", "drop stale socket epoch=%d current=%d", myEpoch, epoch);
                        ws.abort();
                        return;
                    }
                    this.socket = ws;
                    Diagnostics.log("ws", "socket open, enqueue register");
                    // 获取之前保存的用户ID（如果有）
                    int claimed = savedUserIds.get(imCode, username);
                    enqueueSend(Protocol.register(imCode, username, claimed), false);
                })
                .exceptionally(ex -> {
                    Diagnostics.error("ws", "connect failed: %s", unwrap(ex).toString());
                    if (myEpoch == epoch) {
                        handshake.completeExceptionally(unwrap(ex));
                    }
                    return null;
                });

        CompletableFuture<Void> result = handshake.orTimeout(12, TimeUnit.SECONDS);
        result.whenComplete((value, error) -> {
            if (error == null || myEpoch != epoch) {
                return;
            }
            // 握手失败（连接被拒 / 超时 / 服务端报错）：释放半开连接，状态回到断开
            Diagnostics.warn("ws", "handshake failed epoch=%d: %s", myEpoch, rootMessage(error));
            // 先置 closed：迟到的 registered 帧不得把状态翻回 CONNECTED
            closed = true;
            closeSocket();
            setState(LinkState.DISCONNECTED);
        });
        return result;
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
     * 关闭连接（用户主动断开 / 应用退出）
     * 关闭流程：
     * 1. 设置关闭标志
     * 2. 停止保活任务
     * 3. 清空在线用户列表和头像缓存
     * 4. 发送 WebSocket 关闭帧
     * 5. 等待关闭完成（最多1秒）
     * 6. 如果握手未完成，标记为失败
     *
     * <p>口令等凭据<b>不</b>在此清除：{@link #reconnect()} 需要复用它们重新派生密钥，
     * 否则重连后双方密钥不一致，消息将无法解密。凭据随本实例一起消亡——
     * 应用退出或切换用户时实例被丢弃，JVM 随之回收。
     * Java String 不可变，无法擦除堆内残留，这一点在设计文档中已明确。</p>
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
        // 让在途的旧连接回调立刻失效：它们的 epoch 会与当前 epoch 不一致
        epoch = epochSeq.incrementAndGet();
        setState(LinkState.DISCONNECTED);
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
     * 释放当前 WebSocket 连接，但保留凭据与加密配置。
     *
     * <p>用于换连接（{@link #openConnection()} 开头）和握手失败后释放半开连接。
     * <b>非阻塞</b>：close 帧交给 sendExec 线程发送，不等待完成。
     * 这条路径会运行在 WebSocket 的 IO 线程上，阻塞等待会死锁。</p>
     */
    private void closeSocket() {
        WebSocket ws = socket;
        socket = null;
        if (ws == null) {
            return;
        }
        sendExec.execute(() -> {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception e) {
                Diagnostics.warn("ws", "sendClose failed: %s", e.toString());
            }
        });
    }

    /**
     * 单次连接的 WebSocket 监听器。
     *
     * <p>每个连接一个实例，持有自己的 epoch 与分片缓冲区。旧连接的迟到回调
     * （onClose/onError/onText）会发现自己的 epoch 已过期，直接丢弃，
     * 不会停掉新连接的保活任务，也不会污染新连接的消息缓冲。</p>
     */
    final class SessionListener implements WebSocket.Listener {
        /** 本次连接的 epoch */
        private final long sessionEpoch;
        /** 本次连接自己的分片缓冲区 */
        private final StringBuilder text = new StringBuilder();

        SessionListener(long sessionEpoch) {
            this.sessionEpoch = sessionEpoch;
        }

        /** 本次连接是否已被更新的连接取代 */
        private boolean stale() {
            return sessionEpoch != epoch;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            if (stale()) {
                Diagnostics.warn("ws", "stale onOpen epoch=%d current=%d", sessionEpoch, epoch);
                webSocket.abort();
                return;
            }
            Diagnostics.log("ws", "onOpen epoch=%d", sessionEpoch);
            touchInbound();
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (stale()) {
                Diagnostics.warn("ws", "stale onText epoch=%d current=%d", sessionEpoch, epoch);
                webSocket.abort();
                return CompletableFuture.completedFuture(null);
            }
            touchInbound();
            text.append(data);
            if (last) {
                String raw = text.toString();
                text.setLength(0);
                long t0 = System.nanoTime();
                handleRaw(raw);
                long ms = Diagnostics.elapsedMs(t0);
                if (ms >= 50) {
                    Diagnostics.log("ws", "handleRaw slow %dms len=%d", ms, raw.length());
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (stale()) {
                // 旧连接断开：不碰新连接的任何状态
                Diagnostics.log("ws", "stale onClose ignored epoch=%d current=%d", sessionEpoch, epoch);
                return CompletableFuture.completedFuture(null);
            }
            Diagnostics.log("ws", "onClose code=%d reason=%s epoch=%d", statusCode, reason, sessionEpoch);
            handleSessionLost(reason == null || reason.isBlank() ? "连接已断开" : reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (stale()) {
                Diagnostics.log("ws", "stale onError ignored epoch=%d current=%d", sessionEpoch, epoch);
                return;
            }
            Diagnostics.error("ws", "onError: %s", error == null ? "null" : error.toString());
            handleSessionLost(error == null || error.getMessage() == null
                    ? "WebSocket 错误" : error.getMessage());
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (stale()) {
                webSocket.abort();
                return CompletableFuture.completedFuture(null);
            }
            touchInbound();
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            if (stale()) {
                return CompletableFuture.completedFuture(null);
            }
            touchInbound();
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            if (stale()) {
                return CompletableFuture.completedFuture(null);
            }
            touchInbound();
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 记录一次入站帧：刷新链路存活时间戳（看门狗的信号源）。
     */
    private void touchInbound() {
        lastInboundNanos = System.nanoTime();
    }

    /**
     * 测试接缝：当前生效的连接代。
     *
     * @return 当前 epoch
     */
    long currentEpoch() {
        return epoch;
    }

    /**
     * 测试接缝：为指定 epoch 创建一个会话监听器。
     *
     * @param epoch 连接代
     * @return 会话监听器
     */
    SessionListener newSessionListener(long epoch) {
        return new SessionListener(epoch);
    }

    /**
     * 测试接缝：模拟已注册的活跃链路（看门狗只在该状态下检查）。
     *
     * <p>与真实路径保持一致：registered=true 时加密服务必须已初始化
     * （生产代码中两者在 registered 帧处理里同时置位）。</p>
     *
     * @param epoch 连接代
     */
    void markConnectedForTest(long epoch) {
        this.epoch = epoch;
        this.linkState = LinkState.CONNECTED;
        this.registered = true;
        crypto.initialize("test-secret", "|test|" + imCode);
        touchInbound();
        resetWatchdogWindow();
    }

    void backdateKeepaliveTickForTest(long millisAgo) {
        lastKeepaliveTickNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(millisAgo);
    }

    /**
     * 测试接缝：把入站时间戳回拨到指定毫秒之前。
     *
     * @param millisAgo 距今的毫秒数
     */
    void backdateInboundForTest(long millisAgo) {
        lastInboundNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(millisAgo);
        watchdogWindowStartNanos = lastInboundNanos;
    }

    /**
     * 测试接缝：模拟一次建连的开始——设定当前连接代并进入 CONNECTING 状态。
     *
     * @param epoch 连接代
     */
    void beginConnectionForTest(long epoch) {
        this.epoch = epoch;
        setState(LinkState.CONNECTING);
    }

    /**
     * 处理当前连接的丢失（正常关闭或异常）。
     *
     * <p>停止保活、清除注册态、通知监听器，并把链路状态置为 DISCONNECTED，
     * 让 UI 出现「重连」按钮。</p>
     *
     * @param reason 断开原因
     */
    private void handleSessionLost(String reason) {
        stopKeepalive();
        registered = false;
        closed = true;
        socket = null;
        if (!handshake.isDone()) {
            handshake.completeExceptionally(new IllegalStateException(reason));
        }
        setState(LinkState.DISCONNECTED);
        emit(new Event.Closed(reason));
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
                touchInbound(); // 链路存活时钟从注册成功这一刻起算
                savedUserIds.put(this.imCode, this.username, msg.userId());
                startKeepalive();
                publishAvatar();
                Diagnostics.log("ws", "handshake complete userId=%d roster=%s",
                        msg.userId(), roster.values());
                setState(LinkState.CONNECTED);
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
        resetWatchdogWindow();
        keepaliveTask = keepalive.scheduleWithFixedDelay(() -> {
            try {
                runKeepaliveTick();
            } catch (Exception ignored) {
                // 保活失败由关闭回调处理
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void resetWatchdogWindow() {
        long now = System.nanoTime();
        lastKeepaliveTickNanos = now;
        watchdogWindowStartNanos = now;
    }

    /**
     * 保活定时器的一次心跳：先做链路存活检查，再发 ping 与加密保活帧。
     *
     * <p>复用 {@link #keepalive} 调度器的单线程，不新增线程。
     * 抛出的异常由调度器的 try/catch 吞掉，保证定时任务不被取消。</p>
     */
    void runKeepaliveTick() {
        if (!registered) {
            return;
        }
        long now = System.nanoTime();
        if (TimeUnit.NANOSECONDS.toMillis(now - lastKeepaliveTickNanos) >= LINK_IDLE_TIMEOUT_MS) {
            // A stalled scheduler did not send regular probes. Give the next probe time to return.
            watchdogWindowStartNanos = now;
        }
        lastKeepaliveTickNanos = now;
        checkLinkIdle();
        if (linkState != LinkState.CONNECTED) {
            return; // 刚被看门狗判定死亡，本轮不再发送
        }
        sendPing();
        sendEncrypted("text", KEEPALIVE);
    }

    /**
     * 链路存活检查：持续探测期间，超过阈值未收到任何入站帧即判定链路已死。
     *
     * <p>只在 CONNECTED 状态检查——握手阶段有自己的 12 秒超时，
     * CONNECTING 期间服务端尚未应答，不适用静默判定。</p>
     *
     * <p>判定后走 {@link #handleSessionLost(String)}，与正常断线同一条路径：
     * 链路置为 DISCONNECTED，聊天窗口出现「重连」按钮。</p>
     */
    void checkLinkIdle() {
        if (linkState != LinkState.CONNECTED) {
            return;
        }
        long now = System.nanoTime();
        long idleMs = TimeUnit.NANOSECONDS.toMillis(
                Math.min(now - lastInboundNanos, now - watchdogWindowStartNanos));
        if (idleMs < LINK_IDLE_TIMEOUT_MS) {
            return;
        }
        Diagnostics.warn("ws", "link idle %dms (>= %dms), treating link as dead",
                idleMs, LINK_IDLE_TIMEOUT_MS);
        handleSessionLost("连接超时");
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

    /**
     * 提取异常链中最底层的错误消息。
     *
     * @param ex 异常对象
     * @return 错误消息，缺失时返回类名
     */
    private static String rootMessage(Throwable ex) {
        Throwable root = unwrap(ex);
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
