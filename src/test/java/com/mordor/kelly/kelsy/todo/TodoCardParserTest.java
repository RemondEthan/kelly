package com.mordor.kelly.kelsy.todo;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoCardParserTest {

    @Test
    void parsesRequiredFields() {
        var card = TodoCardParser.parse("""
                # 待办 · 申请 licence

                - 截止：2026-09-10
                - 状态：open
                - 标题：申请 licence
                """, "knowledge/todos/2026-09-10-申请-licence.md").orElseThrow();
        assertEquals("申请 licence", card.title());
        assertEquals(LocalDate.of(2026, 9, 10), card.due());
        assertEquals(TodoStatus.OPEN, card.status());
        assertEquals("knowledge/todos/2026-09-10-申请-licence.md", card.relativePath());
    }

    @Test
    void titleFallsBackToHeadingThenSlug() {
        var fromHeading = TodoCardParser.parse("""
                # 待办 · 发周报
                - 截止：2026-09-12
                - 状态：open
                """, "knowledge/todos/2026-09-12-发周报.md").orElseThrow();
        assertEquals("发周报", fromHeading.title());

        var fromFile = TodoCardParser.parse("""
                - 截止：2026-09-12
                - 状态：closed
                """, "knowledge/todos/2026-09-12-misc.md").orElseThrow();
        assertEquals("misc", fromFile.title());
        assertEquals(TodoStatus.CLOSED, fromFile.status());
    }

    @Test
    void acceptsDueWithTimeAndIgnoresOriginal() {
        var card = TodoCardParser.parse("""
                # 待办 · 做完周报

                - 截止：2026-09-07 17:00
                - 状态：open
                - 标题：做完周报
                - 原文：下周一我下午5:00之前要把周报做完。
                """, "knowledge/todos/2026-09-07-做完周报.md").orElseThrow();
        assertEquals(LocalDate.of(2026, 9, 7), card.due());
        assertEquals("做完周报", card.title());
        assertEquals(TodoStatus.OPEN, card.status());
    }

    @Test
    void skipsMissingDueOrBadDate() {
        assertTrue(TodoCardParser.parse("- 状态：open\n", "knowledge/todos/a.md").isEmpty());
        assertTrue(TodoCardParser.parse("- 截止：10月\n- 状态：open\n", "knowledge/todos/a.md").isEmpty());
        assertTrue(TodoCardParser.parse(null, "knowledge/todos/a.md").isEmpty());
    }
}
