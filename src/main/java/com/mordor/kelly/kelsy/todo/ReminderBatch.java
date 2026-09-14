/**
 * 提醒批次数据。
 *
 * <p>表示一次待办提醒的完整信息，包含需要提醒的待办列表和触发的时间槽。
 * 由 {@link TodoReminderService#evaluate} 生成，通过 {@link ReminderFormat} 格式化显示。
 *
 * @param todos 需要提醒的待办卡片列表（已按截止日期排序）
 * @param slots 触发提醒的时间槽集合
 */
package com.mordor.kelly.kelsy.todo;

import java.util.EnumSet;
import java.util.List;

public record ReminderBatch(List<TodoCard> todos, EnumSet<ReminderSlot> slots) {
}
