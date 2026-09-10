package com.mordor.kelly.kelsy.service;

public final class SlashCommands {

    public record Result(boolean send, boolean find, String outgoing, String error) {
        public static Result send(String outgoing) {
            return new Result(true, false, outgoing, null);
        }

        public static Result reject(String error) {
            return new Result(false, false, "", error);
        }

        public static Result find(String query) {
            return new Result(false, true, query, null);
        }
    }

    private SlashCommands() {
    }

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
