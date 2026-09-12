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
import com.mordor.kelly.service.ImClient;
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
 * 聊天业务：把 ImClient 事件转成消息列表，发送时走加密转发。
 */
public class ChatController {

    public interface PeerSender {
        void sendChat(String text) throws Exception;
    }

    private final AppState state;
    private final String imCode;
    private final String username;
    private final ChatHistory history;
    private final PeerSender peerSender;
    private final KelsyRoomSettings settings;
    private KelsyRuntime runtime;
    private final AtomicBoolean kelsyBusy = new AtomicBoolean();
    private final ObjectProperty<AssistantMessage> liveAssistant = new SimpleObjectProperty<>();
    private final BooleanProperty knowledgeVisible = new SimpleBooleanProperty(false);
    private final BooleanProperty thinkingVisible = new SimpleBooleanProperty(true);
    private final BooleanProperty kelsyEnabled = new SimpleBooleanProperty(false);
    private final BooleanProperty memoryWarn = new SimpleBooleanProperty(false);
    private final CitationTurn citations = new CitationTurn();
    private Consumer<List<String>> onCitationSources = paths -> {
    };
    private Consumer<String> onOpenKnowledge = path -> {
    };
    private Runnable onRefreshKnowledge = () -> {
    };
    private final ObservableList<Message> messages = FXCollections.observableArrayList();
    private final ObservableList<RoomMember> members = FXCollections.observableArrayList();
    private final IntegerBinding humanCount = Bindings.createIntegerBinding(this::countHumans, members);
    private final ObservableMap<Integer, Image> peerAvatars = FXCollections.observableHashMap();
    private final Map<Integer, String> peers = new LinkedHashMap<>();
    private final Map<String, Integer> lastSeenIds = new LinkedHashMap<>();
    private final List<Message> liveDuringLoad = new ArrayList<>();
    private boolean historyReady;
    private boolean followingLatest = true;
    private boolean loadingOlder;
    private boolean noMoreOlder;
    /** 仅在 requestOlder 真正插入过更早消息后为 true；误 unfollow 再 follow 时禁止 setAll。 */
    private boolean loadedOlder;
    private final boolean offline;
    private List<String> deferredTodoPaths;
    private TodoReminderService reminders;
    private ScheduledExecutorService reminderClock;
    private ScheduledFuture<?> reminderTick;

    public ChatController(AppState state) {
        this(state, createHistory(state));
    }

    ChatController(AppState state, ChatHistory history) {
        this.state = state;
        this.offline = state.offline();
        this.username = state.username();
        this.history = history;
        this.settings = new KelsyRoomSettings();
        this.runtime = null;
        if (state.offline()) {
            this.imCode = OFFLINE_IM_CODE;
            this.peerSender = text -> {
                throw new IllegalStateException("offline");
            };
        } else {
            this.imCode = state.client().imCode();
            this.peerSender = state.client()::sendChat;
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
        this.state = null;
        this.imCode = imCode;
        this.username = username;
        this.history = history;
        this.peerSender = peerSender;
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
        history.loadOlderThanAsync(firstId, ChatHistory.PAGE_SIZE, older -> Platform.runLater(() -> {
            loadingOlder = false;
            if (older.isEmpty()) {
                noMoreOlder = true;
                return;
            }
            loadedOlder = true;
            noMoreOlder = older.size() < ChatHistory.PAGE_SIZE;
            messages.addAll(0, older);
            while (messages.size() > ChatHistory.MEMORY_CAP) {
                messages.remove(messages.size() - 1);
            }
        }));
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
        history.loadNewestAsync(ChatHistory.MEMORY_CAP, newest -> Platform.runLater(() -> {
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
        };
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
