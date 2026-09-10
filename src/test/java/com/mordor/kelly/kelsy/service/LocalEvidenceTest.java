package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEvidenceTest {

    @Test
    void folderCues() {
        assertTrue(LocalEvidence.mentionsTodos("我最近有什么待办"));
        assertTrue(LocalEvidence.mentionsMeetings("上次会议怎么定的"));
        assertTrue(LocalEvidence.mentionsMeetings("把纪要调出来"));
        assertTrue(LocalEvidence.mentionsDecisions("当时那个决定是什么"));
        assertFalse(LocalEvidence.mentionsMeetings("随便聊聊天"));
    }

    @Test
    void stripsQuestionWordsForSearchTerms() {
        assertEquals(List.of("张三"), LocalEvidence.terms("张三是谁"));
        assertEquals(List.of("licence"), LocalEvidence.terms("licence怎么定的"));
        assertTrue(LocalEvidence.terms("我最近有什么会议").contains("会议"));
    }
}
