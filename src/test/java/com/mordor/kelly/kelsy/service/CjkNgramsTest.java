package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CjkNgramsTest {

    @Test
    void licenceKeepsAscii() {
        assertEquals("licence", CjkNgrams.forIndex("licence").strip());
        assertEquals("licence", CjkNgrams.forQuery("licence").strip());
    }

    @Test
    void chineseEmitsBigramsAndOriginal() {
        String out = CjkNgrams.forIndex("许可证");
        assertTrue(out.contains("许可"));
        assertTrue(out.contains("可证"));
        assertTrue(out.contains("许可证"));
    }

    @Test
    void mixedKeepsBoth() {
        String out = CjkNgrams.forIndex("交付 licence");
        assertTrue(out.contains("licence"));
        assertTrue(out.contains("交付"));
    }
}
