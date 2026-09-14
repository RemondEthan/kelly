/**
 * AI 助手服务接口 —— UI 与 Agent 之间的抽象边界。
 *
 * <p>这是 Kelsy 子系统的核心接口，定义了 UI 层与 AI Agent 之间的契约。
 * 实现类负责具体的 Agent 调度和模型交互，UI 层不感知底层细节。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>解耦</b> - 接口不引用任何 AgentScope 类型，便于替换实现（本地/远程）</li>
 *   <li><b>流式回调</b> - 通过 {@link ReplyHandler} 逐步返回 AI 回复的各个部分</li>
 *   <li><b>线程模型</b> - 回调发生在后台线程，调用方需自行切换到 UI 线程</li>
 * </ul>
 *
 * <p>回调事件流（按时序）：
 * <pre>
 *   chat(text, handler)
 *     ├── onThinkingDelta(...)  ← 可选：AI 思考过程增量
 *     ├── onThinkingEnd()       ← 可选：思考结束
 *     ├── onTextDelta(...)      ← 文本回复增量（流式）
 *     ├── onTextEnd()           ← 文本回复结束
 *     ├── onToolCall(...)       ← 可选：工具调用开始
 *     ├── onToolArgs(...)       ← 可选：工具参数增量
 *     ├── onToolResult(...)     ← 可选：工具返回结果
 *     ├── onComplete()         ← 本次对话完成
 *     └── onError(...)         ← 发生错误
 * </pre>
 *
 * <p>典型用法：
 * <pre>
 *   assistantService.chat("今天天气怎么样", new AssistantService.ReplyHandler() {
 *       public void onTextDelta(String delta) {
 *           // 在 UI 线程中追加文本到消息气泡
 *       }
 *       public void onComplete() {
 *           // 标记消息完成
 *       }
 *       public void onError(Throwable error) {
 *           // 显示错误提示
 *       }
 *   });
 * </pre>
 */
package com.mordor.kelly.kelsy.service;

/**
 * UI 与 Agent 之间的边界。实现可以是本地内嵌，也可以换成远程 HTTP/WS，
 * UI 层不感知。因此这里不出现任何 AgentScope 类型。
 *
 * <p>回调发生在后台线程，调用方负责切回 UI 线程。
 */
public interface AssistantService extends AutoCloseable {

    /**
     * 发送用户消息给 AI 助手，通过回调逐步返回回复。
     *
     * @param text    用户输入的文本内容
     * @param handler 回调处理器，接收流式回复的各个部分
     */
    void chat(String text, ReplyHandler handler);

    /**
     * 关闭助手服务，释放 AgentScope 运行时资源。
     */
    @Override
    void close();

    /**
     * 助手回复回调处理器。
     *
     * <p>定义了 AI 回复过程中各阶段的回调方法。
     * 实现类只需覆写感兴趣的方法，其他方法有默认空实现。
     *
     * <p>所有回调方法都在后台线程调用。
     */
    interface ReplyHandler {

        /**
         * 收到文本回复增量。
         * 每次调用时追加 delta 到当前消息中即可。
         *
         * @param delta 文本增量片段
         */
        void onTextDelta(String delta);

        /**
         * 文本回复结束。
         * 可选回调，用于标记文本内容已完整。
         */
        default void onTextEnd() {
        }

        /**
         * 收到思考过程增量。
         * 可选回调，AI 的内部推理过程会通过此方法返回。
         *
         * @param delta 思考过程增量片段
         */
        default void onThinkingDelta(String delta) {
        }

        /**
         * 思考过程结束。
         * 可选回调。
         */
        default void onThinkingEnd() {
        }

        /**
         * AI 开始调用工具。
         *
         * @param name        工具名称（如 "read_file"、"memory_search"）
         * @param argsPreview 工具参数预览文本
         */
        void onToolCall(String name, String argsPreview);

        /**
         * 工具调用参数增量。
         * 可选回调，逐步返回工具调用的参数。
         *
         * @param name  工具名称
         * @param delta 参数增量片段
         */
        default void onToolArgs(String name, String delta) {
        }

        /**
         * 工具调用返回结果。
         * 可选回调，工具执行完成后返回结果摘要。
         *
         * @param name    工具名称
         * @param summary 结果摘要文本
         */
        default void onToolResult(String name, String summary) {
        }

        /**
         * 本次对话完成（所有内容已返回）。
         */
        void onComplete();

        /**
         * 发生错误。
         *
         * @param error 异常对象
         */
        void onError(Throwable error);
    }
}
