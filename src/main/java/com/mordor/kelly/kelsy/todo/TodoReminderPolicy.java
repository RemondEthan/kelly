/**
 * 待办提醒策略。
 *
 * <p>定义待办事项的提醒触发条件和排序规则。
 *
 * <p>提醒窗口规则：
 * <ul>
 *   <li>仅提醒 <b>OPEN</b> 状态的待办</li>
 *   <li>截止日期在 <b>LEAD_DAYS（3天）</b> 内的待办才会被提醒</li>
 *   <li>已逾期的待办也会被提醒（today > due）</li>
 * </ul>
 *
 * <p>排序规则：
 * 先按截止日期升序，再按标题字母升序。
 * 即即将到期的排在前面，同一天到期的按标题排序。
 *
 * @see TodoCard
 * @see TodoReminderService
 */
package com.mordor.kelly.kelsy.todo;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public final class TodoReminderPolicy {

    /** 提前提醒天数：截止日期前 3 天开始提醒 */
    public static final int LEAD_DAYS = 3;

    /** 私有构造函数，防止实例化 */
    private TodoReminderPolicy() {
    }

    /**
     * 判断待办是否在提醒窗口内。
     *
     * <p>条件：
     * <ul>
     *   <li>待办状态为 OPEN</li>
     *   <li>今天 >= 截止日期 - 3天（即截止日期前 3 天内或已逾期）</li>
     * </ul>
     *
     * @param card  待办卡片
     * @param today 当前日期
     * @return true 如果在提醒窗口内
     */
    public static boolean inWindow(TodoCard card, LocalDate today) {
        if (card == null || today == null || card.status() != TodoStatus.OPEN) {
            return false;
        }
        return !today.isBefore(card.due().minusDays(LEAD_DAYS));
    }

    /**
     * 按截止日期和标题排序待办列表。
     *
     * @param cards 待办列表
     * @return 排序后的新列表（不修改原列表）
     */
    public static List<TodoCard> sort(List<TodoCard> cards) {
        return cards.stream()
                .sorted(Comparator.comparing(TodoCard::due).thenComparing(TodoCard::title))
                .toList();
    }

    /**
     * 判断待办是否已逾期。
     *
     * @param card  待办卡片
     * @param today 当前日期
     * @return true 如果今天在截止日期之后
     */
    public static boolean overdue(TodoCard card, LocalDate today) {
        return card != null && today != null && today.isAfter(card.due());
    }
}
