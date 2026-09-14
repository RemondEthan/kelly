/**
 * 斜杠命令解析器。
 *
 * <p>处理用户输入的 /command 格式命令，将命令转换为 AI 助手可理解的指令。
 * 斜杠命令提供快捷操作，无需用户手动编写复杂的提示词。
 *
 * <p>支持的命令：
 * <ul>
 *   <li><b>/note 内容</b> - 归档笔记：将内容按会议/决策卡片格式归档到知识库</li>
 *   <li><b>/today</b> - 今日汇总：用记忆搜索汇总今天已归档的工作</li>
 *   <li><b>/tidy</b> - 整理索引：整理 MEMORY.md，将流水记录迁移到卡片</li>
 *   <li><b>/find 关键词</b> - 知识搜索：在知识库中搜索相关内容</li>
 * </ul>
 *
 * <p>命令处理结果类型：
 * <ul>
 *   <li><b>send</b> - 转换为 AI 指令，发送给助手处理</li>
 *   <li><b>find</b> - 知识库搜索命令，由搜索引擎处理</li>
 *   <li><b>reject</b> - 命令格式错误，返回错误提示</li>
 * </ul>
 *
 * <p>命令转换示例：
 * <pre>
 *   "/note 明天下午3点产品评审会"
 *   → "请将以下内容按会议/决定卡片归档。先抽槽（类型、谁、日期、主题、结论、待办）..."
 *
 *   "/find 项目进度 2026-03"
 *   → 触发知识库搜索，关键词 ["项目", "进度"]，日期 2026-03
 * </pre>
 */
package com.mordor.kelly.kelsy.service;

public final class SlashCommands {

    /**
     * 命令解析结果。
     *
     * @param send     是否应该发送给 AI 助手（普通指令）
     * @param find     是否为知识库搜索命令
     * @param outgoing 转换后的指令文本
     * @param error    错误提示（命令格式错误时）
     */
    public record Result(boolean send, boolean find, String outgoing, String error) {
        /** 创建发送给 AI 的结果 */
        public static Result send(String outgoing) {
            return new Result(true, false, outgoing, null);
        }

        /** 创建拒绝（格式错误）的结果 */
        public static Result reject(String error) {
            return new Result(false, false, "", error);
        }

        /** 创建知识库搜索的结果 */
        public static Result find(String query) {
            return new Result(false, true, query, null);
        }
    }

    /** 私有构造函数，防止实例化 */
    private SlashCommands() {
    }

    /**
     * 解析用户输入的文本，识别并转换斜杠命令。
     *
     * <p>非斜杠开头的文本直接作为普通消息发送。
     *
     * @param raw 用户原始输入
     * @return 命令解析结果
     */
    public static Result parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (!text.startsWith("/")) {
            return Result.send(text);
        }
        int space = text.indexOf(' ');
        String cmd = (space < 0 ? text : text.substring(0, space)).toLowerCase();
        String rest = space < 0 ? "" : text.substring(space + 1).strip();
        return switch (cmd) {
            case "/note" -> rest.isEmpty()
                    ? Result.reject("请写上要记的内容")
                    : Result.send("请将以下内容按会议/决定卡片归档。先抽槽（类型、谁、日期、主题、结论、待办）。"
                            + "不齐先澄清，不要 memory_save、不要写卡片、不要说已经记下。"
                            + "齐了再：用文件系统工具写 knowledge/meetings/ 或 knowledge/decisions/ 卡片（含别名字段），"
                            + "KNOWLEDGE.md 加一行，再用 memory_save 写日记和 MEMORY.md 指针（必须带谁、主题词、别名、卡片路径）。"
                            + "不是会议/决定则仍按一条一事 memory_save。原文：\n" + rest);
            case "/today" -> Result.send(
                    "请用 memory_search / memory_get 汇总今天已归档的工作，列出条目并注明来源路径。");
            case "/tidy" -> Result.send(
                    "MEMORY.md 可能过长。请按 AGENTS.md：把流水迁到卡片或专题页的短指针，"
                            + "指针须带谁、主题词、别名和路径。不删除 knowledge/meetings 或 decisions 里的卡片。"
                            + "只许用 memory_save，不要 write_file。");
            case "/find" -> rest.isEmpty()
                    ? Result.reject("用法：/find 关键词，可加 2026-03 或 半年前")
                    : Result.find(rest);
            default -> Result.send(text);
        };
    }
}
