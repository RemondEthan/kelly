package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardFieldsTest {

    @Test
    void parsesMeetingCard() {
        var f = CardFields.parse(
                "knowledge/meetings/2026-09-04-客户XX-交付licence.md",
                """
                # 会议 · 客户XX · 交付 licence
                - 日期：2026-09-04
                - 类型：会议
                - 谁：客户XX
                - 主题：交付是否需要 licence
                - 别名：licence, license, 许可证
                """);
        assertEquals("会议", f.type());
        assertEquals("客户XX", f.who());
        assertEquals("2026-09-04", f.date());
        assertEquals("licence, license, 许可证", f.aliases());
        assertEquals("会议 · 客户XX · 交付 licence", f.title());
    }

    @Test
    void todoUsesDeadlineAndStatus() {
        var f = CardFields.parse(
                "knowledge/todos/2026-03-20-周报.md",
                """
                # 待办 · 周报
                - 截止：2026-03-20
                - 状态：open
                """);
        assertEquals("2026-03-20", f.date());
        assertEquals("open", f.status());
    }

    @Test
    void missingFieldsAreEmpty() {
        var f = CardFields.parse("knowledge/inbox/note.md", "随便一句话");
        assertEquals("", f.type());
        assertEquals("note", f.title());
    }
}
