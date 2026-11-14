/**
 * 提醒时间槽枚举。
 *
 * <p>定义待办提醒的触发时间点：
 * <ul>
 *   <li><b>LOGIN</b> - 用户登录时触发（每次应用启动检查一次）</li>
 *   <li><b>TEN</b> - 上午 10:00 触发</li>
 *   <li><b>FOURTEEN</b> - 下午 14:00 触发</li>
 * </ul>
 *
 * <p>每个时间槽在同一天内只触发一次，通过 {@link ReminderLedger} 记录已触发状态。
 */
package com.mordor.kelly.kelsy.todo;

public enum ReminderSlot {
    /** 登录时触发 */
    LOGIN,
    /** 上午 10:00 触发 */
    TEN,
    /** 下午 14:00 触发 */
    FOURTEEN
}
