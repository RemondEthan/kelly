/**
 * 本地 AI 助手服务实现 —— 基于 AgentScope 框架。
 *
 * <p>这是 Kelsy 子系统的核心实现类，在本进程内运行 AgentScope 的 HarnessAgent，
 * 通过 OpenAI 兼容 API 与大语言模型交互。
 *
 * <p>AgentScope 概念说明：
 * <ul>
 *   <li><b>HarnessAgent</b> - AgentScope 中的"线束代理"，是 AI Agent 的运行容器。
 *       它封装了模型调用、工具调度、上下文管理等核心能力。</li>
 *   <li><b>RuntimeContext</b> - 运行时上下文，包含会话 ID、用户 ID 等信息，
 *       用于跨重启保持会话历史和记忆。</li>
 *   <li><b>AgentEvent</b> - Agent 事件流，包含文本增量、思考过程、工具调用等事件。
 *       通过 Reactor 的 Flux 流式返回。</li>
 * </ul>
 *
 * <p>AI Agent 工作流程：
 * <pre>
 *   1. 用户输入 → chat(text, handler)
 *   2. HarnessAgent.streamEvents() → 创建 Reactor Flux 事件流
 *   3. 事件流在后台线程执行（Schedulers.boundedElastic）
 *   4. dispatch() 将 AgentScope 事件转换为 ReplyHandler 回调：
 *      - TextBlockDeltaEvent    → onTextDelta()
 *      - ThinkingBlockDeltaEvent → onThinkingDelta()
 *      - ToolCallStartEvent     → onToolCall()
 *      - ToolCallDeltaEvent     → onToolArgs()
 *      - ToolResultTextDeltaEvent → onToolResult()
 *   5. 事件流结束 → onComplete()
 * </pre>
 *
 * <p>会话管理：
 * <ul>
 *   <li>使用固定会话 ID "main"，确保记忆和会话历史跨重启延续</li>
 *   <li>每个用户有独立的 RuntimeContext（通过 userId 区分）</li>
 * </ul>
 *
 * <p>安全限制（通过 HarnessAgent.builder 配置）：
 * <ul>
 *   <li>禁用 Shell 工具（disableShellTool）</li>
 *   <li>禁用动态技能（disableDynamicSkills）</li>
 *   <li>禁用子代理（disableSubagents）</li>
 *   <li>最大迭代次数限制为 20（maxIters）</li>
 * </ul>
 */
package com.mordor.kelly.kelsy.service;

import com.mordor.kelly.kelsy.config.KelsyConfig;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.List;

/**
 * 在本进程内跑 AgentScope 的 HarnessAgent。
 */
public final class LocalAssistantService implements AssistantService {

    /**
     * 系统提示词：定义 AI 助手的身份和基本行为。
     * "Tars" 是助手的名称，长期规则以工作区中的 AGENTS.md 文件为准。
     */
    public static final String SYS_PROMPT =
            "你是 Tars，用户的个人工作助理。长期规则以工作区 AGENTS.md 为准。";

    /** 固定会话 ID，让记忆与会话历史跨重启延续 */
    private static final String SESSION_ID = "main";

    /** AgentScope 线束代理实例，负责 AI 模型调用和工具调度 */
    private final HarnessAgent agent;

    /** AgentScope 运行时上下文，包含会话和用户信息 */
    private final RuntimeContext context;

    /** 与 {@link KnowledgeSearchTool} 共用的用户知识库；仅 {@code ownsStore} 时由本类关闭 */
    private final KnowledgeStore store;
    private final boolean ownsStore;

    /**
     * 私有构造函数，通过 {@link #create} 工厂方法创建。
     *
     * @param agent     HarnessAgent 实例
     * @param context   运行时上下文
     * @param store     知识库（与 knowledge_search 同一实例）
     * @param ownsStore true 时 {@link #close()} 关闭 store
     */
    private LocalAssistantService(HarnessAgent agent, RuntimeContext context, KnowledgeStore store,
                                  boolean ownsStore) {
        this.agent = agent;
        this.context = context;
        this.store = store;
        this.ownsStore = ownsStore;
    }

    /**
     * 创建本地助手服务实例。
     *
     * <p>初始化流程：
     * <ol>
     *   <li>播种共享工作空间模板，再播种用户知识库根</li>
     *   <li>Harness 工作区指向用户知识根（与 FTS / memory 同一棵树）</li>
     *   <li>通过 {@link ModelFactory} 创建 AI 模型实例</li>
     *   <li>构建 HarnessAgent（配置名称、系统提示词、模型、安全限制等）</li>
     *   <li>创建 RuntimeContext（会话 ID + 用户 ID）</li>
     * </ol>
     *
     * @param config Kelsy 配置（包含模型设置和工作空间路径）
     * @param userId 用户标识
     * @return 新的 LocalAssistantService 实例
     */
    public static LocalAssistantService create(KelsyConfig config, String userId) {
        WorkspaceSeeder.seed(config.workspacePath());
        Path knowledgeRoot = KnowledgeStore.bootstrap(config.workspacePath(), userId);
        return create(config, userId, new KnowledgeStore(knowledgeRoot), true);
    }

    /**
     * 使用运行时已打开的知识库，避免同一 {@code .kelly-index.db} 上再开一条连接。
     */
    public static LocalAssistantService create(KelsyConfig config, String userId, KnowledgeStore store) {
        WorkspaceSeeder.seed(config.workspacePath());
        Path knowledgeRoot = KnowledgeStore.bootstrap(config.workspacePath(), userId);
        return create(config, userId, store, false);
    }

    private static LocalAssistantService create(KelsyConfig config, String userId, KnowledgeStore store,
                                                boolean ownsStore) {
        Path workspace = config.workspacePath();
        Path knowledgeRoot = KnowledgeStore.knowledgeRoot(workspace, userId);
        Model model = ModelFactory.create(config.model());

        ToolsConfig toolsConfig = new ToolsConfig();
        toolsConfig.setDeny(List.of("memory_search"));
        KnowledgeStore knowledge = store != null ? store : new KnowledgeStore(knowledgeRoot);
        // 前置断言：Java 端知识库根必须等于 <workspace>/<userId>，便于任何后续
        // 重构一眼能看清不变量。HarnessAgent 拿到的是裸 workspace（见下方注释），
        // 它的 USER 级命名空间会把 userId 补上，三方最终对齐到同一个 <root>/<userId>/。
        Path storeRoot = knowledge.workspace().toAbsolutePath().normalize();
        Path expectedRoot = knowledgeRoot.toAbsolutePath().normalize();
        if (!storeRoot.equals(expectedRoot)) {
            throw new IllegalStateException(
                    "KnowledgeStore root does not match user knowledge root. "
                                    + "store=" + storeRoot + " expected=" + expectedRoot
                                    + " userId=" + userId);
        }
        boolean closeStore = ownsStore || store == null;
        // 注意：把 workspace 设为「裸工作目录」（不含 userId），让 AgentScope 自己按
        // RuntimeContext.userId 做 USER 级命名空间。Java 端再做一次 <root>/<userId>/
        // 预嵌套，会变成 <root>/<userId>/<userId>/，重蹈「文件不存在」+「目录套娃」。
        // 同时 KnowledgeStore 仍以 knowledgeRoot 为根，三方（Java 读取 / AgentScope 写入 /
        // todo 扫描）最终都落在 <root>/<userId>/ 下。
        HarnessAgent agent = HarnessAgent.builder()
                .name("tars")
                .sysPrompt(SYS_PROMPT)
                .model(model)
                .workspace(workspace)
                .toolsConfig(toolsConfig)
                .disableShellTool()        // 禁用 Shell 命令执行（安全考虑）
                .disableDynamicSkills()    // 禁用动态技能加载
                .disableSubagents()        // 禁用子代理调用
                .disableDynamicSubagents() // 禁用动态子代理
                .maxIters(20)              // 限制最大迭代次数（防止无限循环）
                .build();
        agent.getToolkit().registerAgentTool(new KnowledgeSearchTool(knowledge));
        IndexingAgentTool.install(agent.getToolkit(), knowledge);

        RuntimeContext context = RuntimeContext.builder()
                .sessionId(SESSION_ID)
                .userId(userId)
                .build();

        return new LocalAssistantService(agent, context, knowledge, closeStore);
    }

    /**
     * 发送用户消息给 AI 助手，通过回调返回流式回复。
     *
     * <p>底层使用 AgentScope 的 {@code streamEvents()} 方法创建 Reactor Flux 事件流，
     * 在后台弹性线程池中执行。每个 AgentEvent 会被转换为对应的 ReplyHandler 回调。
     *
     * @param text    用户输入的文本
     * @param handler 回调处理器
     */
    @Override
    public void chat(String text, ReplyHandler handler) {
        agent.streamEvents(text, context)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> dispatch(event, handler),
                        handler::onError,
                        handler::onComplete);
    }

    /**
     * 关闭助手服务，释放 HarnessAgent 和 AgentScope 运行时资源。
     */
    @Override
    public void close() {
        try {
            agent.close();
        } finally {
            if (ownsStore && store != null) {
                store.close();
            }
        }
    }

    /**
     * 将 AgentScope 事件分发为 ReplyHandler 回调。
     *
     * <p>事件映射：
     * <ul>
     *   <li>TextBlockDeltaEvent → onTextDelta（文本增量）</li>
     *   <li>TextBlockEndEvent → onTextEnd（文本结束）</li>
     *   <li>ThinkingBlockDeltaEvent → onThinkingDelta（思考增量）</li>
     *   <li>ThinkingBlockEndEvent → onThinkingEnd（思考结束）</li>
     *   <li>ToolCallStartEvent → onToolCall（工具调用开始）</li>
     *   <li>ToolCallDeltaEvent → onToolArgs（工具参数增量）</li>
     *   <li>ToolResultTextDeltaEvent → onToolResult（工具结果增量）</li>
     *   <li>ToolResultEndEvent → 忽略（工具结果结束）</li>
     * </ul>
     *
     * @param event   AgentScope 事件
     * @param handler 回调处理器
     */
    private static void dispatch(AgentEvent event, ReplyHandler handler) {
        switch (event) {
            case TextBlockDeltaEvent e -> handler.onTextDelta(e.getDelta());
            case TextBlockEndEvent e -> handler.onTextEnd();
            case ThinkingBlockDeltaEvent e -> handler.onThinkingDelta(e.getDelta());
            case ThinkingBlockEndEvent e -> handler.onThinkingEnd();
            case ToolCallStartEvent e -> handler.onToolCall(e.getToolCallName(), "");
            case ToolCallDeltaEvent e -> handler.onToolArgs(e.getToolCallName(), e.getDelta());
            case ToolResultTextDeltaEvent e -> handler.onToolResult(e.getToolCallName(), e.getDelta());
            case ToolResultEndEvent ignored -> {
            }
            default -> {
            }
        }
    }
}
