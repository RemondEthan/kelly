package com.mordor.kelly.ui.chat;

import com.mordor.kelly.common.Diagnostics;
import com.mordor.kelly.kelsy.KelsyPaths;
import com.mordor.kelly.kelsy.KelsyRoomSettings;
import com.mordor.kelly.kelsy.KelsyRuntime;
import com.mordor.kelly.kelsy.KelsySendRouter;
import com.mordor.kelly.kelsy.model.AssistantMessage;
import com.mordor.kelly.kelsy.model.MessageBlock;
import com.mordor.kelly.kelsy.service.AssistantService;
import com.mordor.kelly.kelsy.service.CitationTurn;
import com.mordor.kelly.kelsy.service.FindQuery;
import com.mordor.kelly.kelsy.service.KnowledgePathExtractor;
import com.mordor.kelly.kelsy.service.KnowledgeStore;
import com.mordor.kelly.kelsy.service.LocalEvidence;
import com.mordor.kelly.kelsy.todo.ReminderBatch;
import com.mordor.kelly.kelsy.todo.ReminderFormat;
import com.mordor.kelly.kelsy.todo.TodoCard;
import com.mordor.kelly.kelsy.todo.TodoReminderService;
import com.mordor.kelly.kelsy.todo.TodoScanner;
import com.mordor.kelly.kelsy.todo.TodoStatus;
import com.mordor.kelly.model.AppState;
import com.mordor.kelly.model.Message;
import com.mordor.kelly.model.RoomMember;
import com.mordor.kelly.model.Sender;
import com.mordor.kelly.service.AvatarService;
import com.mordor.kelly.service.ChatHistory;
import com.mordor.kelly.service.CryptoService;
import com.mordor.kelly.service.ImageAssembler;
import com.mordor.kelly.service.ImageDraft;
import com.mordor.kelly.service.ImageWire;
import com.mordor.kelly.service.ImClient;
import com.mordor.kelly.service.MediaStore;
import com.mordor.kelly.service.PreviewJpeg;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.IntegerBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.scene.image.Image;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 聊天业务控制器：管理消息列表、处理 IM 事件、协调 Kelsy 助手交互。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>将 {@link ImClient} 的事件（消息、图片、加入/离开等）转换为 {@link Message} 列表</li>
 *   <li>处理消息发送（文本/图片），包括加密转发和 Kelsy 路由</li>
 *   <li>协调 Kelsy 助手的流式回复、工具调用和引用显示</li>
 *   <li>管理聊天历史的加载、分页和内存缓存</li>
 *   <li>处理待办事项提醒的定时触发</li>
 * </ul>
 *
 * <h3>JavaFX 属性</h3>
 * <p>使用 {@link BooleanProperty} 和 {@link ObjectProperty} 实现响应式数据流：
 * UI 层通过绑定这些属性自动更新，无需手动刷新。</p>
 *
 * <h3>线程模型</h3>
 * <p>ImClient 事件在后台线程触发，通过 {@link Platform#runLater} 切换到 FX 应用线程处理。
 * 所有 UI 更新必须在 FX 线程执行。</p>
 */
public class ChatController {

    /**
     * 消息发送接口：抽象底层 IM 客户端的发送操作，便于测试注入。
     */
    public interface PeerSender {
        /** 发送文本消息 */
        void sendChat(String text) throws Exception;

        /** 发送图片消息（元数据 + 分片数据） */
        default void sendImagePayload(String metaPlaintext, List<String> chunkPlaintexts) throws Exception {
            throw new IllegalStateException("图片发送未接入");
        }
    }

    /** 应用全局状态（用户名、在线状态、头像等） */
    private final AppState state;
    /** IM 配对码（用于标识聊天房间） */
    private final String imCode;
    /** 当前用户名 */
    private final String username;
    /** 聊天历史管理器（加密存储） */
    private final ChatHistory history;
    /** 消息发送器（IM 客户端封装） */
    private final PeerSender peerSender;
    /** 媒体文件存储管理器 */
    private final MediaStore mediaStore;
    /** 接收中的图片分片组装器：mediaId → ImageAssembler */
    private final Map<String, ImageAssembler> incomingImages = new LinkedHashMap<>();
    /** 接收中的图片元数据：mediaId → Meta */
    private final Map<String, ImageWire.Meta> incomingMeta = new LinkedHashMap<>();
    /** Kelsy 房间配置（启用状态、头像、昵称） */
    private final KelsyRoomSettings settings;
    /** Kelsy 运行时实例（管理 API key、助手服务等） */
    private KelsyRuntime runtime;
    /** Kelsy 是否正在处理请求（防止重复提交） */
    private final AtomicBoolean kelsyBusy = new AtomicBoolean();
    /** 实时助手消息（流式生成中） */
    private final ObjectProperty<AssistantMessage> liveAssistant = new SimpleObjectProperty<>();
    /** 知识库面板是否可见 */
    private final BooleanProperty knowledgeVisible = new SimpleBooleanProperty(false);
    /** 助手思考过程是否可见 */
    private final BooleanProperty thinkingVisible = new SimpleBooleanProperty(true);
    /** Kelsy 助手是否启用 */
    private final BooleanProperty kelsyEnabled = new SimpleBooleanProperty(false);
    /** 知识库内存使用是否超限警告 */
    private final BooleanProperty memoryWarn = new SimpleBooleanProperty(false);
    /** 引用回合管理器：跟踪助手回复中的工具调用和引用 */
    private final CitationTurn citations = new CitationTurn();
    /** 引用源变更回调 */
    private Consumer<List<String>> onCitationSources = paths -> {};
    /** 打开知识库文件回调 */
    private Consumer<String> onOpenKnowledge = path -> {};
    /** 刷新知识库回调 */
    private Runnable onRefreshKnowledge = () -> {};

    /** 消息列表（ObservableList：列表变化时 UI 自动更新） */
    private final ObservableList<Message> messages = FXCollections.observableArrayList();
    /** 房间成员列表 */
    private final ObservableList<RoomMember> members = FXCollections.observableArrayList();
    /** 人类成员数量绑定（排除 Kelsy 助手） */
    private final IntegerBinding humanCount = Bindings.createIntegerBinding(this::countHumans, members);
    /** 对方头像映射：userId → Image */
    private final ObservableMap<Integer, Image> peerAvatars = FXCollections.observableHashMap();
    /** 在线成员映射：userId → username */
    private final Map<Integer, String> peers = new LinkedHashMap<>();
    /** 用户名到用户 ID 的反向映射 */
    private final Map<String, Integer> lastSeenIds = new LinkedHashMap<>();
    /** 历史加载期间到达的实时消息（加载完成后合并） */
    private final List<Message> liveDuringLoad = new ArrayList<>();
    /** 历史消息是否已加载完成 */
    private boolean historyReady;
    /** 是否处于"跟随最新消息"模式 */
    private boolean followingLatest = true;
    /** 是否正在加载更早的历史消息 */
    private boolean loadingOlder;
    /** 是否已无更早的历史消息 */
    private boolean noMoreOlder;
    /** 是否已真正加载过更早历史（防止布局误触发的 setAll 清空实时消息） */
    private boolean loadedOlder;
    /** 是否为脱机模式（不连接 IM 服务器） */
    private final boolean offline;
    /** 延迟处理的待办事项路径（助手回复完成后再显示引用） */
    private List<String> deferredTodoPaths;
    /** 待办事项提醒服务 */
    private TodoReminderService reminders;
    /** 提醒定时器线程池 */
    private ScheduledExecutorService reminderClock;
    /** 下一次提醒的定时任务 */
    private ScheduledFuture<?> reminderTick;

    public ChatController(AppState state) {
        this(state, createHistory(state));
    }

    ChatController(AppState state, ChatHistory history) {
        this.state = state;
        this.offline = state.offline();
        this.username = state.username();
        this.history = history;
        this.mediaStore = MediaStore.defaultStore();
        this.settings = new KelsyRoomSettings();
        this.runtime = null;
        if (state.offline()) {
            this.imCode = OFFLINE_IM_CODE;
            this.peerSender = text -> {
                throw new IllegalStateException("offline");
            };
        } else {
            this.imCode = state.client().imCode();
            this.peerSender = new PeerSender() {
                @Override
                public void sendChat(String text) {
                    state.client().sendChat(text);
                }

                @Override
                public void sendImagePayload(String metaPlaintext, List<String> chunkPlaintexts) {
                    state.client().sendImage(metaPlaintext, chunkPlaintexts);
                }
            };
            state.client().addListener(event -> {
                Diagnostics.log("chat", "queue %s fx=%s", eventName(event), Platform.isFxApplicationThread());
                Platform.runLater(() -> {
                    long t0 = System.nanoTime();
                    onEvent(event);
                    Diagnostics.log("chat", "apply %s %dms peers=%s",
                            eventName(event), Diagnostics.elapsedMs(t0), peers.values());
                });
            });
            peers.putAll(state.client().roster());
            state.client().avatars().forEach((id, png) ->
                    AvatarService.fromPngBytes(png).ifPresent(img -> peerAvatars.put(id, img)));
            state.client().roster().forEach((id, name) -> lastSeenIds.put(name, id));
        }
        if (this.settings.enabled(this.imCode)) {
            try {
                this.runtime = KelsyRuntime.shared(this.username);
            } catch (UnsupportedOperationException ignored) {
                this.runtime = null;
            }
        }
        refreshPeers();
        Diagnostics.log("chat", "controller ready roster=%s header=%s offline=%s",
                peers.values(), state.peerDisplayProperty().get(), state.offline());
        history.loadInitialAsync(loaded -> Platform.runLater(() -> onHistoryLoaded(loaded)));
    }

    ChatController(String imCode, String username, ChatHistory history,
                   PeerSender peerSender, KelsyRoomSettings settings, KelsyRuntime runtime) {
        this(imCode, username, history, peerSender, settings, runtime, false);
    }

    ChatController(String imCode, String username, ChatHistory history,
                   PeerSender peerSender, KelsyRoomSettings settings, KelsyRuntime runtime,
                   boolean offline) {
        this(imCode, username, history, peerSender, settings, runtime, offline, MediaStore.defaultStore());
    }

    ChatController(String imCode, String username, ChatHistory history,
                   PeerSender peerSender, KelsyRoomSettings settings, KelsyRuntime runtime,
                   boolean offline, MediaStore mediaStore) {
        this.state = null;
        this.imCode = imCode;
        this.username = username;
        this.history = history;
        this.peerSender = peerSender;
        this.mediaStore = mediaStore == null ? MediaStore.defaultStore() : mediaStore;
        this.settings = settings;
        this.runtime = runtime;
        this.offline = offline;
        refreshPeers();
    }

    private boolean offline() {
        return state != null ? state.offline() : offline;
    }

    private static ChatHistory createHistory(AppState state) {
        if (state.offline()) {
            return new ChatHistory(
                    ChatHistory.defaultFile(OFFLINE_IM_CODE),
                    CryptoService.forArchive(OFFLINE_ARCHIVE_PASSWORD, OFFLINE_IM_CODE));
        }
        ImClient client = state.client();
        return new ChatHistory(
                ChatHistory.defaultFile(client.imCode()),
                CryptoService.forArchive(client.password(), client.imCode()));
    }

    public ObservableList<Message> getMessages() {
        return messages;
    }

    public ObservableList<RoomMember> getMembers() {
        return members;
    }

    public AppState getState() {
        return state;
    }

    public ObservableMap<Integer, Image> peerAvatars() {
        return peerAvatars;
    }

    public Integer rememberedUserId(String username) {
        return username == null ? null : lastSeenIds.get(username);
    }

    public String imCode() {
        return imCode;
    }

    public IntegerBinding humanCountProperty() {
        return humanCount;
    }

    public ObjectProperty<AssistantMessage> liveAssistantProperty() {
        return liveAssistant;
    }

    public BooleanProperty knowledgeVisibleProperty() {
        return knowledgeVisible;
    }

    public BooleanProperty thinkingVisibleProperty() {
        return thinkingVisible;
    }

    public BooleanProperty kelsyEnabledProperty() {
        return kelsyEnabled;
    }

    public boolean kelsyEnabled() {
        return kelsyEnabled.get();
    }

    public KnowledgeStore knowledgeStore() {
        return runtime == null ? null : runtime.store(username);
    }

    public BooleanProperty memoryWarnProperty() {
        return memoryWarn;
    }

    public void setOnCitationSources(Consumer<List<String>> onCitationSources) {
        this.onCitationSources = onCitationSources == null ? paths -> {
        } : onCitationSources;
    }

    public void setOnOpenKnowledge(Consumer<String> onOpenKnowledge) {
        this.onOpenKnowledge = onOpenKnowledge == null ? path -> {
        } : onOpenKnowledge;
    }

    public void setOnRefreshKnowledge(Runnable onRefreshKnowledge) {
        this.onRefreshKnowledge = onRefreshKnowledge == null ? () -> {
        } : onRefreshKnowledge;
    }

    public void openKnowledge(String path) {
        knowledgeVisible.set(true);
        onOpenKnowledge.accept(path);
    }

    public void refreshKnowledge() {
        onRefreshKnowledge.run();
        refreshMemoryWarn();
    }

    public Image avatarOfSecretary() {
        return AvatarService.load(settings.avatarPath(imCode)).orElse(null);
    }

    public Image avatarOf(String username) {
        if (state != null && username != null && username.equals(state.username())) {
            return state.avatar();
        }
        if (username != null && username.equals(this.username)) {
            return state != null ? state.avatar() : null;
        }
        Integer id = lastSeenIds.get(username);
        return id == null ? null : peerAvatars.get(id);
    }

    public record SendResult(boolean accepted, String hint) {
        public static SendResult ok() {
            return new SendResult(true, null);
        }

        public static SendResult reject(String hint) {
            return new SendResult(false, hint);
        }
    }

    public static final String BUSY_HINT = "秘书还在回复";
    public static final String OFFLINE_REJECT_HINT = "脱机登录，消息无法发送";
    public static final String IMAGE_TOO_LARGE_HINT = "图片超过 20MB，无法发送";
    public static final String IMAGE_BAD_HINT = "无法处理这张图片";
    public static final String OFFLINE_IM_CODE = "__offline__";
    public static final String OFFLINE_ARCHIVE_PASSWORD = "offline";

    public SendResult send(String content) {
        if (content == null || content.isBlank()) {
            return SendResult.reject(null);
        }
        boolean enabled = settings.enabled(imCode);
        boolean configured = enabled && runtime != null && runtime.hasApiKey();
        var route = KelsySendRouter.route(
                enabled, kelsyBusy.get(), configured, content, settings.nickname(imCode));
        return switch (route.kind()) {
            case PEER -> {
                if (offline()) {
                    yield SendResult.reject(OFFLINE_REJECT_HINT);
                }
                yield sendPeer(content);
            }
            case BUSY -> SendResult.reject(BUSY_HINT);
            case UNCONFIGURED -> {
                addSystem("尚未配置秘书 API key：" + (runtime == null
                        ? KelsyPaths.defaults().config()
                        : runtime.paths().config()));
                yield SendResult.ok();
            }
            case EMPTY_BODY, SLASH_ERROR -> {
                addSystem(route.error());
                yield SendResult.ok();
            }
            case FIND -> {
                addSelf(content);
                runFind(route.outgoing());
                yield SendResult.ok();
            }
            case ASK -> {
                addSelf(content);
                startAsk(route.outgoing());
                yield SendResult.ok();
            }
        };
    }

    public SendResult sendImage(ImageDraft draft) {
        if (draft == null || draft.bytes().length == 0) {
            return SendResult.reject(IMAGE_BAD_HINT);
        }
        if (!ImageWire.acceptableSize(draft.bytes().length)) {
            return SendResult.reject(IMAGE_TOO_LARGE_HINT);
        }
        if (offline()) {
            return SendResult.reject(OFFLINE_REJECT_HINT);
        }
        try {
            byte[] preview = PreviewJpeg.encode(draft.bytes());
            String mediaId = UUID.randomUUID().toString();
            MediaStore.Stored stored = mediaStore.save(imCode, mediaId, draft.bytes(), draft.mime(), preview);
            ImageWire.Meta meta = new ImageWire.Meta(
                    1,
                    mediaId,
                    draft.caption(),
                    draft.mime(),
                    draft.bytes().length,
                    ImageWire.sha256Hex(draft.bytes()),
                    ImageWire.b64(preview));
            List<byte[]> parts = ImageWire.split(draft.bytes());
            List<String> chunks = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                chunks.add(ImageWire.encodeChunk(new ImageWire.Chunk(
                        mediaId, i, parts.size(), ImageWire.b64(parts.get(i)))));
            }
            peerSender.sendImagePayload(ImageWire.encodeMeta(meta), chunks);
            followingLatest = true;
            noMoreOlder = false;
            addMessage(Message.image(
                    mediaId,
                    Sender.SELF,
                    draft.caption(),
                    LocalDateTime.now(),
                    username,
                    mediaId,
                    stored.previewRel(),
                    stored.originalRel()));
            return SendResult.ok();
        } catch (Exception e) {
            addSystem("发送失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            return SendResult.ok();
        }
    }

    public Path mediaFile(String rel) {
        if (rel == null || rel.isBlank()) {
            return null;
        }
        return mediaStore.resolve(imCode, rel);
    }

    public Path mediaDir() {
        return mediaStore.dir(imCode);
    }

    public String secretaryNickname() {
        return settings.nickname(imCode);
    }

    public void enableKelsy(String avatarPath) {
        enableKelsy(avatarPath, RoomMember.SECRETARY_NAME);
    }

    public void enableKelsy(String avatarPath, String nickname) {
        settings.enable(imCode, avatarPath, nickname);
        if (runtime == null) {
            runtime = KelsyRuntime.shared(username);
        }
        kelsyEnabled.set(true);
        refreshPeers();
    }

    public void disableKelsy() {
        settings.disable(imCode);
        knowledgeVisible.set(false);
        kelsyEnabled.set(false);
        refreshPeers();
    }

    private SendResult sendPeer(String content) {
        try {
            peerSender.sendChat(content);
            addSelf(content);
        } catch (Exception e) {
            addSystem("发送失败: " + (e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
        return SendResult.ok();
    }

    private void addSelf(String content) {
        followingLatest = true;
        noMoreOlder = false;
        addMessage(new Message(
                UUID.randomUUID().toString(),
                Sender.SELF,
                content,
                LocalDateTime.now(),
                username));
    }

    private void startAsk(String outgoing) {
        citations.beginAsk();
        kelsyBusy.set(true);
        AssistantService assistant = runtime.ensureAssistant();
        if (assistant == null) {
            kelsyBusy.set(false);
            return;
        }
        AssistantMessage reply = AssistantMessage.streaming(Sender.ASSISTANT);
        liveAssistant.set(reply);
        assistant.chat(outgoing, new AssistantService.ReplyHandler() {
            @Override
            public void onTextDelta(String delta) {
                onFx(() -> reply.append(delta));
            }

            @Override
            public void onTextEnd() {
                onFx(reply::finish);
            }

            @Override
            public void onThinkingDelta(String delta) {
                onFx(() -> reply.appendThinking(delta));
            }

            @Override
            public void onThinkingEnd() {
                onFx(reply::finishThinking);
            }

            @Override
            public void onToolCall(String name, String argsPreview) {
                onFx(() -> {
                    citations.beginTool(name);
                    if (argsPreview != null && !argsPreview.isBlank()) {
                        citations.appendToolArgs(argsPreview);
                    }
                    reply.addTool(0, name, argsPreview);
                });
            }

            @Override
            public void onToolArgs(String name, String delta) {
                onFx(() -> {
                    citations.appendToolArgs(delta);
                    for (MessageBlock b : reply.blocks()) {
                        if (b.kind() == MessageBlock.Kind.TOOL && name.equals(b.toolName())) {
                            b.appendArgs(delta);
                            break;
                        }
                    }
                    KnowledgePathExtractor.first(citations.toolArgs()).ifPresent(path -> {
                        for (MessageBlock b : reply.blocks()) {
                            if (b.kind() == MessageBlock.Kind.TOOL && name.equals(b.toolName())) {
                                b.openPathProperty().set(path);
                            }
                        }
                    });
                });
            }

            @Override
            public void onToolResult(String name, String summary) {
                onFx(() -> {
                    citations.appendToolResult(summary);
                    KnowledgePathExtractor.first(citations.toolText()).ifPresent(path -> {
                        for (MessageBlock b : reply.blocks()) {
                            if (b.kind() == MessageBlock.Kind.TOOL && name.equals(b.toolName())) {
                                if (b.openPathProperty().get().isBlank()) {
                                    b.openPathProperty().set(path);
                                }
                            }
                        }
                    });
                });
            }

            @Override
            public void onComplete() {
                onFx(() -> {
                    reply.finish();
                    liveAssistant.set(null);
                    persistAssistant(reply.content());
                    kelsyBusy.set(false);
                    citations.addRetrievalText(reply.content());
                    addLocalEvidence(outgoing);
                    boolean retrieved = citations.commitIfRetrieved();
                    Diagnostics.warn("cite", "retrieved=%s shown=%s outgoing=%s",
                            retrieved, citations.shown(), outgoing);
                    if (retrieved) {
                        knowledgeVisible.set(true);
                        onCitationSources.accept(citations.shown());
                        openKnowledge(citations.evidencePath());
                    }
                    applyDeferredTodosIfNeeded(retrieved);
                    refreshKnowledge();
                });
            }

            @Override
            public void onError(Throwable error) {
                onFx(() -> {
                    reply.append("\n[出错] " + rootMessage(error));
                    reply.finish();
                    liveAssistant.set(null);
                    persistAssistant(reply.content());
                    kelsyBusy.set(false);
                });
            }
        });
    }

    private void persistAssistant(String content) {
        Message message = new Message(
                UUID.randomUUID().toString(),
                Sender.ASSISTANT,
                content == null ? "" : content,
                LocalDateTime.now(),
                settings.nickname(imCode));
        history.appendAsync(message);
        if (!historyReady) {
            liveDuringLoad.add(message);
        }
        // 助手回复始终显示在聊天中，不受 followingLatest 影响
        // MessageListView 会根据 followingLatest 决定是否自动滚动到底部
        messages.add(message);
        evictFromHead();
    }

    void setAsking(boolean asking) {
        kelsyBusy.set(asking);
    }

    void applyReminder(ReminderBatch batch, LocalDate today) {
        if (batch == null || batch.todos().isEmpty()) {
            return;
        }
        persistAssistant(ReminderFormat.encode(batch.todos(), today));
        List<String> paths = batch.todos().stream().map(TodoCard::relativePath).toList();
        if (kelsyBusy.get()) {
            deferredTodoPaths = paths;
            return;
        }
        showTodoSources(paths);
    }

    void finishAskWithoutRetrieval() {
        kelsyBusy.set(false);
        applyDeferredTodosIfNeeded(false);
    }

    void attachReminders(TodoReminderService service) {
        stopReminders();
        this.reminders = service;
    }

    void startReminders(TodoReminderService service) {
        attachReminders(service);
        fireRemindersAt(LocalDateTime.now());
        reminderClock = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kelly-todo-reminder");
            t.setDaemon(true);
            return t;
        });
        scheduleNext(LocalDateTime.now());
    }

    void fireRemindersAt(LocalDateTime now) {
        if (reminders == null || now == null) {
            return;
        }
        reminders.evaluate(now).ifPresent(batch -> {
            reminders.commit(batch, now.toLocalDate());
            applyReminder(batch, now.toLocalDate());
        });
    }

    public void close() {
        stopReminders();
        history.close();
    }

    public void stopReminders() {
        if (reminderTick != null) {
            reminderTick.cancel(false);
            reminderTick = null;
        }
        if (reminderClock != null) {
            reminderClock.shutdownNow();
            reminderClock = null;
        }
        reminders = null;
    }

    private void addLocalEvidence(String outgoing) {
        KnowledgeStore store = knowledgeStore();
        if (store == null || outgoing == null || outgoing.isBlank()) {
            return;
        }
        if (LocalEvidence.mentionsTodos(outgoing)) {
            citations.addPaths(TodoScanner.list(store.workspace()).stream()
                    .filter(card -> card.status() == TodoStatus.OPEN)
                    .map(TodoCard::relativePath)
                    .toList());
        }
        if (LocalEvidence.mentionsMeetings(outgoing)) {
            citations.addPaths(store.cardPaths("knowledge/meetings"));
        }
        if (LocalEvidence.mentionsDecisions(outgoing)) {
            citations.addPaths(store.cardPaths("knowledge/decisions"));
        }
        citations.addPaths(store.cardsContaining(LocalEvidence.terms(outgoing)));
    }

    private void showTodoSources(List<String> paths) {
        citations.replaceShown(paths);
        knowledgeVisible.set(true);
        onCitationSources.accept(citations.shown());
        openKnowledge(citations.firstShown());
    }

    private void applyDeferredTodosIfNeeded(boolean retrieved) {
        List<String> deferred = deferredTodoPaths;
        deferredTodoPaths = null;
        if (!retrieved && deferred != null && !deferred.isEmpty()) {
            showTodoSources(deferred);
        }
    }

    private void scheduleNext(LocalDateTime now) {
        if (reminderClock == null) {
            return;
        }
        LocalDateTime next = TodoReminderService.nextClock(now);
        long delay = Duration.between(now, next).toMillis();
        reminderTick = reminderClock.schedule(() -> onFx(() -> {
            LocalDateTime t = LocalDateTime.now();
            fireRemindersAt(t);
            scheduleNext(t);
        }), Math.max(delay, 0), TimeUnit.MILLISECONDS);
    }

    private void refreshMemoryWarn() {
        KnowledgeStore store = knowledgeStore();
        if (store != null) {
            memoryWarn.set(store.memoryBytes() > KnowledgeStore.MEMORY_WARN_BYTES);
        }
    }

    /** 有 FX 工具箱则切回应用线程；单测未启动工具箱时就地执行。 */
    private static void onFx(Runnable action) {
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (IllegalStateException ignored) {
            action.run();
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cur = error;
        while (cur != null && cur.getCause() != null && cur != cur.getCause()) {
            cur = cur.getCause();
        }
        if (cur == null) {
            return "未知错误";
        }
        String message = cur.getMessage();
        return message == null || message.isBlank() ? cur.toString() : message;
    }

    private void runFind(String query) {
        if (runtime == null) {
            return;
        }
        List<KnowledgeStore.Hit> hits = runtime.store(username)
                .search(FindQuery.parse(query, LocalDate.now()));
        citations.beginAsk();
        citations.addPaths(hits.stream().map(KnowledgeStore.Hit::relativePath).toList());
        if (citations.commitIfRetrieved()) {
            knowledgeVisible.set(true);
            onCitationSources.accept(citations.shown());
            openKnowledge(citations.evidencePath());
        }
        addSystem(formatFind(hits));
    }

    private static String formatFind(List<KnowledgeStore.Hit> hits) {
        if (hits.isEmpty()) {
            return "知识库中没有匹配";
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeStore.Hit hit : hits) {
            sb.append(hit.relativePath()).append(':').append(hit.line())
                    .append(' ').append(hit.snippet()).append('\n');
        }
        return sb.toString().strip();
    }

    public void requestOlder() {
        if (loadingOlder || noMoreOlder || messages.isEmpty()) {
            return;
        }
        loadingOlder = true;
        followingLatest = false;
        String firstId = messages.get(0).id();
        history.loadOlderThanAsync(firstId, ChatHistory.PAGE_SIZE, older -> onFx(() -> {
            loadingOlder = false;
            applyOlderPage(older);
        }));
    }

    /** 单测灌入当前可见窗口，不写系统提示。 */
    void showSnapshot(List<Message> snapshot) {
        messages.setAll(snapshot);
        evictFromHead();
        historyReady = true;
    }

    void applyOlderPage(List<Message> older) {
        if (older == null || older.isEmpty()) {
            noMoreOlder = true;
            return;
        }
        loadedOlder = true;
        followingLatest = false;
        noMoreOlder = older.size() < ChatHistory.PAGE_SIZE;
        messages.addAll(0, older);
        while (messages.size() > ChatHistory.MEMORY_CAP) {
            messages.remove(messages.size() - 1);
        }
    }

    public void followLatest() {
        if (followingLatest) {
            return;
        }
        followingLatest = true;
        noMoreOlder = false;
        if (!ScrollFollowPolicy.shouldReloadFromDisk(loadedOlder)) {
            Diagnostics.log("chat", "followLatest resume without setAll (no older page loaded)");
            return;
        }
        loadedOlder = false;
        history.loadNewestAsync(ChatHistory.MEMORY_CAP, newest -> onFx(() -> {
            if (newest.isEmpty() && !messages.isEmpty()) {
                Diagnostics.warn("chat", "followLatest skipped empty disk over %d live msgs", messages.size());
                return;
            }
            messages.setAll(newest);
            evictFromHead();
        }));
    }

    public void stopFollowing() {
        followingLatest = false;
    }

    public boolean followingLatest() {
        return followingLatest;
    }

    void onEvent(ImClient.Event event) {
        switch (event) {
            case ImClient.Event.Chat(String username, String plaintext) ->
                    addMessage(new Message(
                            UUID.randomUUID().toString(),
                            Sender.PEER,
                            plaintext,
                            LocalDateTime.now(),
                            username));
            case ImClient.Event.Image(String username, String plaintext) ->
                    onPeerImage(username, plaintext);
            case ImClient.Event.ImageChunk(String username, String plaintext) ->
                    onPeerImageChunk(plaintext);
            case ImClient.Event.PeerJoined(int userId, String username) -> {
                lastSeenIds.put(username, userId);
                boolean firstSeen = peers.put(userId, username) == null;
                refreshPeers();
                if (firstSeen) {
                    addSystem(username + " 已加入");
                }
            }
            case ImClient.Event.PeerLeft(int userId, String username) -> {
                peers.remove(userId);
                refreshPeers();
                addSystem(username + " 已离开");
            }
            case ImClient.Event.PeerAvatar(int userId, String username, byte[] png) -> {
                lastSeenIds.put(username, userId);
                Optional<Image> img = AvatarService.fromPngBytes(png);
                if (img.isPresent()) {
                    peerAvatars.put(userId, img.get());
                    Diagnostics.log("chat", "peer avatar userId=%d user=%s", userId, username);
                } else {
                    Diagnostics.warn("chat", "peer avatar decode failed userId=%d user=%s bytes=%d",
                            userId, username, png == null ? 0 : png.length);
                }
            }
            case ImClient.Event.Closed(String reason) -> {
                state.setOnline(false);
                addSystem(reason);
            }
            case ImClient.Event.ServerError(String message) ->
                    addSystem("[错误] " + message);
            case ImClient.Event.DecryptFailed(String username) ->
                    addSystem("无法解密 " + username + " 的消息（口令是否一致？）");
            case ImClient.Event.Registered ignored -> {
                // 登录阶段已处理
            }
        }
    }

    private void refreshPeers() {
        if (state != null && !state.offline()) {
            if (peers.isEmpty()) {
                state.setPeerDisplay("等待对方");
            } else {
                state.setPeerDisplay(String.join(", ", peers.values()));
            }
        }
        List<RoomMember> next = new ArrayList<>();
        next.add(new RoomMember(RoomMember.SELF_ID, username, true));
        peers.forEach((id, name) -> next.add(new RoomMember(id, name, false)));
        if (settings.enabled(imCode)) {
            next.add(1, RoomMember.kelsy(settings.nickname(imCode)));
        }
        members.setAll(next);
        kelsyEnabled.set(settings.enabled(imCode));
        refreshMemoryWarn();
    }

    private int countHumans() {
        int n = 0;
        for (RoomMember member : members) {
            if (!member.isKelsy()) {
                n++;
            }
        }
        return n;
    }

    private static String eventName(ImClient.Event event) {
        return switch (event) {
            case ImClient.Event.PeerJoined(int userId, String username) ->
                    "PeerJoined(" + userId + "," + username + ")";
            case ImClient.Event.PeerLeft(int userId, String username) ->
                    "PeerLeft(" + userId + "," + username + ")";
            case ImClient.Event.Chat(String username, String plaintext) ->
                    "Chat(" + username + ",len=" + plaintext.length() + ")";
            case ImClient.Event.Closed(String reason) -> "Closed(" + reason + ")";
            case ImClient.Event.ServerError(String message) -> "ServerError(" + message + ")";
            case ImClient.Event.DecryptFailed(String username) -> "DecryptFailed(" + username + ")";
            case ImClient.Event.Registered(int userId, String ignored) -> "Registered(" + userId + ")";
            case ImClient.Event.PeerAvatar(int userId, String username, byte[] png) ->
                    "PeerAvatar(" + userId + "," + username + ",len=" + png.length + ")";
            case ImClient.Event.Image(String username, String plaintext) ->
                    "Image(" + username + ",len=" + plaintext.length() + ")";
            case ImClient.Event.ImageChunk(String username, String plaintext) ->
                    "ImageChunk(" + username + ",len=" + plaintext.length() + ")";
        };
    }

    private void onPeerImage(String username, String plaintext) {
        try {
            ImageWire.Meta meta = ImageWire.parseMeta(plaintext);
            byte[] jpeg = ImageWire.unb64(meta.previewJpeg());
            mediaStore.writePreview(imCode, meta.id(), jpeg);
            incomingMeta.put(meta.id(), meta);
            addMessage(Message.image(
                    meta.id(),
                    Sender.PEER,
                    meta.caption(),
                    LocalDateTime.now(),
                    username,
                    meta.id(),
                    MediaStore.previewName(meta.id()),
                    MediaStore.originalName(meta.id(), meta.mime())));
        } catch (Exception e) {
            Diagnostics.warn("chat", "peer image meta failed: %s", e.toString());
        }
    }

    private void onPeerImageChunk(String plaintext) {
        try {
            ImageWire.Chunk chunk = ImageWire.parseChunk(plaintext);
            ImageAssembler asm = incomingImages.computeIfAbsent(
                    chunk.id(), id -> new ImageAssembler(Math.max(1, chunk.n())));
            if (!asm.offer(chunk.i(), ImageWire.unb64(chunk.data()))) {
                return;
            }
            incomingImages.remove(chunk.id());
            ImageWire.Meta meta = incomingMeta.remove(chunk.id());
            byte[] original = asm.bytes();
            if (meta != null && !meta.sha256().equals(ImageWire.sha256Hex(original))) {
                Diagnostics.warn("chat", "peer image sha mismatch id=%s", chunk.id());
                return;
            }
            String mime = meta == null ? "image/png" : meta.mime();
            mediaStore.writePart(imCode, chunk.id(), original);
            mediaStore.commitOriginal(imCode, chunk.id(), mime);
        } catch (Exception e) {
            Diagnostics.warn("chat", "peer image chunk failed: %s", e.toString());
        }
    }

    void onHistoryLoaded(List<Message> loaded) {
        List<Message> merged = new ArrayList<>(loaded);
        for (Message live : liveDuringLoad) {
            boolean already = false;
            for (Message existing : merged) {
                if (existing.id().equals(live.id())) {
                    already = true;
                    break;
                }
            }
            if (!already) {
                merged.add(live);
            }
        }
        messages.setAll(merged);
        evictFromHead();
        historyReady = true;
        if (!offline()) {
            if (peers.isEmpty()) {
                addSystem("已加入房间，等待对方连接");
            } else {
                addSystem("已与 " + String.join(", ", peers.values()) + " 连接");
            }
        }
        Diagnostics.log("chat", "history loaded n=%d unlocked=%s", loaded.size(), history.isUnlocked());
    }

    private void addMessage(Message message) {
        history.appendAsync(message);
        if (!historyReady) {
            liveDuringLoad.add(message);
            messages.add(message);
            return;
        }
        if (!followingLatest) {
            return;
        }
        if (loadedOlder) {
            loadedOlder = false;
            noMoreOlder = false;
            history.loadNewestAsync(ChatHistory.MEMORY_CAP, newest -> onFx(() -> {
                List<Message> next = new ArrayList<>(newest);
                boolean present = false;
                for (Message existing : next) {
                    if (existing.id().equals(message.id())) {
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    next.add(message);
                }
                messages.setAll(next);
                evictFromHead();
            }));
            return;
        }
        messages.add(message);
        evictFromHead();
    }

    private void evictFromHead() {
        while (messages.size() > ChatHistory.MEMORY_CAP) {
            messages.remove(0);
        }
    }

    private void addSystem(String text) {
        addMessage(new Message(
                UUID.randomUUID().toString(),
                Sender.SYSTEM,
                text,
                LocalDateTime.now()));
    }
}
