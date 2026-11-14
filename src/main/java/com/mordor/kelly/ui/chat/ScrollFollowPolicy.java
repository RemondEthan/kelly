package com.mordor.kelly.ui.chat;

/**
 * 滚动跟随策略：根据 ScrollPane 的 vvalue 变化判断用户意图。
 *
 * <h3>核心问题</h3>
 * <p>JavaFX 的 {@link javafx.scene.control.ScrollPane#vvalueProperty()} 取值范围是 0.0（顶部）
 * 到 1.0（底部）。当程序化修改列表（如加载历史、新消息到达）时，vvalue 也会变化，
 * 不能把所有变化都当成"用户在滚动"。</p>
 *
 * <h3>策略设计</h3>
 * <ul>
 *   <li><b>LOAD_OLDER</b> - 用户滚动到顶部 → 加载更早的历史消息</li>
 *   <li><b>FOLLOW_LATEST</b> - 用户从上方滚回底部 → 恢复"跟随最新"模式</li>
 *   <li><b>STOP_FOLLOWING</b> - 用户从底部向上滚动 → 停止自动跟随</li>
 *   <li><b>NONE</b> - 程序化变化或无需响应的微小变化</li>
 * </ul>
 *
 * <h3>防抖阈值说明</h3>
 * <ul>
 *   <li>vvalue 从 ~1 跳到 0：布局重排导致的跳变，不是用户操作</li>
 *   <li>vvalue 从 0.99 到 1.0：贴底抖动，不应触发 setAll</li>
 * </ul>
 *
 * <p>工具类，不能实例化。</p>
 */
final class ScrollFollowPolicy {

    /**
     * 滚动事件对应的动作类型。
     */
    enum Action {
        /** 无需响应（程序化变化或布局抖动） */
        NONE,
        /** 用户主动向上滚动，停止自动跟随最新消息 */
        STOP_FOLLOWING,
        /** 用户滚动到顶部附近，加载更早的历史消息 */
        LOAD_OLDER,
        /** 用户从上方滚回底部，恢复跟随最新消息模式 */
        FOLLOW_LATEST
    }

    private ScrollFollowPolicy() {}

    /**
     * 根据 vvalue 变化判断用户意图。
     *
     * @param oldV         变化前的 vvalue
     * @param newV         变化后的 vvalue
     * @param canScroll    是否有可滚动的内容（内容高度 > 视口高度）
     * @param programmatic 是否为程序化修改（非用户操作）
     * @return 应执行的动作
     */
    static Action onVvalue(double oldV, double newV, boolean canScroll, boolean programmatic) {
        if (programmatic || !canScroll) {
            return Action.NONE;  // 程序化变化或无可滚动内容，忽略
        }
        if (oldV > 0.02 && newV <= 0.02) {
            // 滚动到顶部区域（vvalue ≤ 0.02）
            // 特殊情况：从底部（≥0.95）跳到顶部是布局重排，不是用户操作
            if (oldV >= 0.95) {
                return Action.NONE;
            }
            return Action.LOAD_OLDER;
        }
        // 从上方到达底部（vvalue ≥ 0.98）
        // 要求从下方（<0.98）到达，防止贴底抖动 0.99→1.0 误触发
        if (newV >= 0.98 && oldV < 0.98) {
            return Action.FOLLOW_LATEST;
        }
        // 从底部（≥0.98）向上离开到 0.95 以下
        if (oldV >= 0.98 && newV < 0.95) {
            return Action.STOP_FOLLOWING;
        }
        return Action.NONE;
    }

    /**
     * 判断是否应该将滚动条钉在底部。
     *
     * @param followingLatest 是否处于"跟随最新"模式
     * @param prepend         是否为前置插入（加载历史时不应钉底）
     * @return true 表示应该钉在底部
     */
    static boolean shouldPinToBottom(boolean followingLatest, boolean prepend) {
        return followingLatest && !prepend;
    }

    /**
     * 判断回到底部时是否需要用磁盘快照替换内存列表。
     *
     * <p>只有真正加载过更早历史后（loadedOlder=true）才需要。
     * 布局误触发的 unfollow→follow 绝不能 setAll，
     * 否则会把刚发的消息刷空。</p>
     *
     * @param loadedOlder 是否已加载过更早的历史
     * @return true 表示需要从磁盘重新加载
     */
    static boolean shouldReloadFromDisk(boolean loadedOlder) {
        return loadedOlder;
    }
}
