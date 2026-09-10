package com.mordor.kelly.kelsy.service;

/**
 * UI 与 Agent 之间的边界。实现可以是本地内嵌，也可以换成远程 HTTP/WS，
 * UI 层不感知。因此这里不出现任何 AgentScope 类型。
 *
 * <p>回调发生在后台线程，调用方负责切回 UI 线程。
 */
public interface AssistantService extends AutoCloseable {

    void chat(String text, ReplyHandler handler);

    @Override
    void close();

    interface ReplyHandler {

        void onTextDelta(String delta);

        default void onTextEnd() {
        }

        default void onThinkingDelta(String delta) {
        }

        default void onThinkingEnd() {
        }

        void onToolCall(String name, String argsPreview);

        default void onToolArgs(String name, String delta) {
        }

        default void onToolResult(String name, String summary) {
        }

        void onComplete();

        void onError(Throwable error);
    }
}
