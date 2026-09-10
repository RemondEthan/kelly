package com.mordor.kelly.kelsy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class KelsySendRouterTest {

    @Test
    void disabledAlwaysPeerEvenWithMention() {
        var r = KelsySendRouter.route(false, false, true, "@tars 你好", "tars");
        assertEquals(KelsySendRouter.Kind.PEER, r.kind());
        assertEquals("@tars 你好", r.peerText());
    }

    @Test
    void enabledNonMentionIsPeer() {
        var r = KelsySendRouter.route(true, false, true, "/find 关键词", "tars");
        assertEquals(KelsySendRouter.Kind.PEER, r.kind());
        assertEquals("/find 关键词", r.peerText());
    }

    @Test
    void oldKelsyPrefixIsPeerEvenWhenEnabled() {
        var r = KelsySendRouter.route(true, false, true, "@kelsy 你好", "tars");
        assertEquals(KelsySendRouter.Kind.PEER, r.kind());
        assertEquals("@kelsy 你好", r.peerText());
    }

    @Test
    void busyMentionIsBusy() {
        var r = KelsySendRouter.route(true, true, true, "@tars 第二问", "tars");
        assertEquals(KelsySendRouter.Kind.BUSY, r.kind());
        assertNull(r.peerText());
    }

    @Test
    void busyDoesNotBlockPeer() {
        var r = KelsySendRouter.route(true, true, true, "普通消息", "tars");
        assertEquals(KelsySendRouter.Kind.PEER, r.kind());
    }

    @Test
    void mentionWithoutKey() {
        var r = KelsySendRouter.route(true, false, false, "@tars 你好", "tars");
        assertEquals(KelsySendRouter.Kind.UNCONFIGURED, r.kind());
    }

    @Test
    void mentionEmptyBody() {
        var r = KelsySendRouter.route(true, false, true, "@tars", "tars");
        assertEquals(KelsySendRouter.Kind.EMPTY_BODY, r.kind());
    }

    @Test
    void mentionAskAndFind() {
        var ask = KelsySendRouter.route(true, false, true, "@tars 你好", "tars");
        assertEquals(KelsySendRouter.Kind.ASK, ask.kind());
        assertEquals("你好", ask.outgoing());

        var find = KelsySendRouter.route(true, false, true, "@tars /find 方案", "tars");
        assertEquals(KelsySendRouter.Kind.FIND, find.kind());
        assertEquals("方案", find.outgoing());
    }

    @Test
    void mentionSlashReject() {
        var r = KelsySendRouter.route(true, false, true, "@tars /note", "tars");
        assertEquals(KelsySendRouter.Kind.SLASH_ERROR, r.kind());
        assertEquals("请写上要记的内容", r.error());
    }

    @Test
    void customNickAskAndDefaultTarsIsPeer() {
        var ask = KelsySendRouter.route(true, false, true, "@Ada 你好", "Ada");
        assertEquals(KelsySendRouter.Kind.ASK, ask.kind());
        assertEquals("你好", ask.outgoing());
        var peer = KelsySendRouter.route(true, false, true, "@tars 你好", "Ada");
        assertEquals(KelsySendRouter.Kind.PEER, peer.kind());
    }
}
