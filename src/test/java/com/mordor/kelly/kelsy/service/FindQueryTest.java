package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindQueryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 2);

    @Test
    void halfYearAgoWindow() {
        var q = FindQuery.parse("半年前 张三", TODAY);
        assertEquals(LocalDate.of(2026, 2, 1), q.fromInclusive());
        assertEquals(LocalDate.of(2026, 4, 30), q.toInclusive());
        assertEquals(List.of("张三"), q.keywords());
    }

    @Test
    void yearMonth() {
        var q = FindQuery.parse("2026-03 评审", TODAY);
        assertEquals(LocalDate.of(2026, 3, 1), q.fromInclusive());
        assertEquals(LocalDate.of(2026, 3, 31), q.toInclusive());
        assertEquals(List.of("评审"), q.keywords());
    }

    @Test
    void keywordsOnly() {
        var q = FindQuery.parse("张三 方案", TODAY);
        assertTrue(q.fromInclusive() == null && q.toInclusive() == null);
        assertEquals(List.of("张三", "方案"), q.keywords());
    }
}
