package com.mordor.kelly.ui.chat;

/**
 * 区分「布局/程序化改 vvalue」和「用户拖动滚动条」。
 * 进房灌历史时 vvalue 会落到中间，不能当成用户离开底部。
 */
final class ScrollFollowPolicy {

    enum Action { NONE, STOP_FOLLOWING, LOAD_OLDER, FOLLOW_LATEST }

    private ScrollFollowPolicy() {}

    static Action onVvalue(double oldV, double newV, boolean canScroll, boolean programmatic) {
        if (programmatic || !canScroll) {
            return Action.NONE;
        }
        if (oldV > 0.02 && newV <= 0.02) {
            // 跟底时内容增高，ScrollPane 可能把 vvalue 从 ~1 一次性打到 0；
            // 那是布局跳变，不是用户翻到顶。
            if (oldV >= 0.95) {
                return Action.NONE;
            }
            return Action.LOAD_OLDER;
        }
        // 必须从下方到达底部；贴底抖动 0.99→1.0 不能触发 followLatest/setAll。
        if (newV >= 0.98 && oldV < 0.98) {
            return Action.FOLLOW_LATEST;
        }
        if (oldV >= 0.98 && newV < 0.95) {
            return Action.STOP_FOLLOWING;
        }
        return Action.NONE;
    }

    static boolean shouldPinToBottom(boolean followingLatest, boolean prepend) {
        return followingLatest && !prepend;
    }

    /**
     * 只有真正加载过更早历史后，回到底部才需要用磁盘快照替换内存列表。
     * 布局误触发的 unfollow→follow 绝不能 setAll，否则会把刚发的消息刷空。
     */
    static boolean shouldReloadFromDisk(boolean loadedOlder) {
        return loadedOlder;
    }
}
