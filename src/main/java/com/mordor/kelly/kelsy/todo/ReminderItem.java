/**
 * 提醒项数据模型。
 *
 * <p>表示单个待办提醒的结构化信息，由 {@link ReminderFormat} 从文本格式解析而来。
 *
 * @param title        待办事项标题
 * @param due          截止日期
 * @param overdue      是否已逾期
 * @param relativePath 知识库中的相对路径
 */
package com.mordor.kelly.kelsy.todo;

import java.time.LocalDate;

public record ReminderItem(String title, LocalDate due, boolean overdue, String relativePath) {
}
