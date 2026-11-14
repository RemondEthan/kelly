/**
 * 待办事项状态枚举。
 *
 * <p>表示待办事项的当前状态：
 * <ul>
 *   <li><b>OPEN</b> - 待处理（尚未完成）</li>
 *   <li><b>CLOSED</b> - 已完成</li>
 * </ul>
 */
package com.mordor.kelly.kelsy.todo;

public enum TodoStatus {
    /** 待处理状态 */
    OPEN,
    /** 已完成状态 */
    CLOSED
}
