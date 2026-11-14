package com.mordor.kelly.ui.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScrollFollowPolicyTest {

    @Test
    void layoutDipDoesNotStopFollowing() {
        assertEquals(ScrollFollowPolicy.Action.NONE,
                ScrollFollowPolicy.onVvalue(0.0, 0.4, true, true));
        assertEquals(ScrollFollowPolicy.Action.NONE,
                ScrollFollowPolicy.onVvalue(1.0, 0.6, true, true));
    }

    @Test
    void cannotScrollDoesNothing() {
        assertEquals(ScrollFollowPolicy.Action.NONE,
                ScrollFollowPolicy.onVvalue(1.0, 0.0, false, false));
    }

    @Test
    void userLeavesBottomStopsFollowing() {
        assertEquals(ScrollFollowPolicy.Action.STOP_FOLLOWING,
                ScrollFollowPolicy.onVvalue(1.0, 0.5, true, false));
    }

    @Test
    void midRangeLayoutDoesNotStopFollowing() {
        assertEquals(ScrollFollowPolicy.Action.NONE,
                ScrollFollowPolicy.onVvalue(0.6, 0.4, true, false));
    }

    @Test
    void userReachesTopLoadsOlder() {
        assertEquals(ScrollFollowPolicy.Action.LOAD_OLDER,
                ScrollFollowPolicy.onVvalue(0.2, 0.0, true, false));
    }

    @Test
    void layoutJumpFromBottomToTopDoesNotLoadOlder() {
        // 内容刚超出视口时 ScrollPane 常把 vvalue 从 1 一次性打到 0，
        // 不能当成用户翻到顶去加载历史，否则会 unfollow → followLatest → setAll 把列表刷没。
        assertEquals(ScrollFollowPolicy.Action.NONE,
                ScrollFollowPolicy.onVvalue(1.0, 0.0, true, false));
        assertEquals(ScrollFollowPolicy.Action.NONE,
                ScrollFollowPolicy.onVvalue(0.99, 0.01, true, false));
    }

    @Test
    void userReachesBottomFollowsLatest() {
        assertEquals(ScrollFollowPolicy.Action.FOLLOW_LATEST,
                ScrollFollowPolicy.onVvalue(0.5, 1.0, true, false));
    }

    @Test
    void jitterAtBottomDoesNotFollowLatest() {
        assertEquals(ScrollFollowPolicy.Action.NONE,
                ScrollFollowPolicy.onVvalue(0.99, 1.0, true, false));
    }

    @Test
    void followLatestReloadsDiskOnlyAfterOlderLoad() {
        assertFalse(ScrollFollowPolicy.shouldReloadFromDisk(false));
        assertTrue(ScrollFollowPolicy.shouldReloadFromDisk(true));
    }

    @Test
    void shouldPinWhenFollowingAndNotPrepend() {
        assertTrue(ScrollFollowPolicy.shouldPinToBottom(true, false));
        assertFalse(ScrollFollowPolicy.shouldPinToBottom(true, true));
        assertFalse(ScrollFollowPolicy.shouldPinToBottom(false, false));
    }
}
