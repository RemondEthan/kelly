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
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;

/**
 * 在本进程内跑 AgentScope 的 HarnessAgent。
 */
public final class LocalAssistantService implements AssistantService {

    public static final String SYS_PROMPT =
            "你是 Tars，用户的个人工作助理。长期规则以工作区 AGENTS.md 为准。";

    // 固定会话 id，让记忆与会话历史跨重启延续
    private static final String SESSION_ID = "main";

    private final HarnessAgent agent;
    private final RuntimeContext context;

    private LocalAssistantService(HarnessAgent agent, RuntimeContext context) {
        this.agent = agent;
        this.context = context;
    }

    public static LocalAssistantService create(KelsyConfig config, String userId) {
        Path workspace = config.workspacePath();
        WorkspaceSeeder.seed(workspace);
        WorkspaceSeeder.seed(KnowledgeStore.knowledgeRoot(workspace, userId));
        Model model = ModelFactory.create(config.model());

        HarnessAgent agent = HarnessAgent.builder()
                .name("tars")
                .sysPrompt(SYS_PROMPT)
                .model(model)
                .workspace(config.workspacePath())
                .disableShellTool()
                .disableDynamicSkills()
                .disableSubagents()
                .disableDynamicSubagents()
                .maxIters(20)
                .build();

        RuntimeContext context = RuntimeContext.builder()
                .sessionId(SESSION_ID)
                .userId(userId)
                .build();

        return new LocalAssistantService(agent, context);
    }

    @Override
    public void chat(String text, ReplyHandler handler) {
        agent.streamEvents(text, context)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> dispatch(event, handler),
                        handler::onError,
                        handler::onComplete);
    }

    @Override
    public void close() {
        agent.close();
    }

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
