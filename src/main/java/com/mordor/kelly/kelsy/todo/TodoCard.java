/**
 * 待办事项数据模型。
 *
 * <p>表示一个从知识库中解析出的待办事项卡片。
 * 每个待办卡片对应一个 Markdown 文件，包含标题、截止日期、状态等信息。
 *
 * <p>待办卡片文件格式示例（knowledge/todos/xxx.md）：
 * <pre>
 *   # 待办 · 完成产品评审
 *   - 截止：2026-03-20
 *   - 状态：open
 *   - 标题：完成产品评审
 * </pre>
 *
 * @param title       待办事项标题
 * @param due         截止日期
 * @param status      状态（OPEN/CLOSED）
 * @param relativePath 知识库中的相对路径
 */
package com.mordor.kelly.kelsy.todo;

import java.time.LocalDate;

public record TodoCard(String title, LocalDate due, TodoStatus status, String relativePath) {
}
