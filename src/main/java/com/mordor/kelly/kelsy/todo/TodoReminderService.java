/**
 * 待办事项提醒服务。
 *
 * <p>定时检查待办事项，在指定时间槽（时间点）提醒用户处理即将到期的待办。
 *
 * <p>提醒时间槽：
 * <ul>
 *   <li><b>LOGIN</b> - 用户登录时（每次启动检查一次）</li>
 *   <li><b>TEN</b> - 上午 10:00</li>
 *   <li><b>FOURTEEN</b> - 下午 14:00</li>
 * </ul>
 *
 * <p>工作流程：
 * <pre>
 *   1. evaluate(now) → 扫描待办，检查是否在提醒窗口内
 *   2. 筛选出到期时间在 LEAD_DAYS（3天）内的待办
 *   3. 检查当前时间槽是否已触发（通过 ReminderLedger）
 *   4. 返回 ReminderBatch（待办列表 + 触发的时间槽）
 *   5. commit(batch, today) → 标记时间槽已触发（防止重复提醒）
 * </pre>
 *
 * <p>下次提醒时间计算：
 * <ul>
 *   <li>10:00 前 → 下次提醒 10:00</li>
 *   <li>14:00 前 → 下次提醒 14:00</li>
 *   <li>14:00 后 → 次日 10:00</li>
 * </ul>
 *
 * @see TodoReminderPolicy
 * @see ReminderLedger
 * @see ReminderBatch
 */
package com.mordor.kelly.kelsy.todo;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public final class TodoReminderService {

    /** 上午提醒时间点 */
    private static final LocalTime TEN = LocalTime.of(10, 0);

    /** 下午提醒时间点 */
    private static final LocalTime FOURTEEN = LocalTime.of(14, 0);

    private final TodoRepository repository;
    private final boolean ownsRepository;
    public TodoReminderService(Path knowledgeRoot) { this(new TodoRepository(knowledgeRoot), true); }
    public TodoReminderService(TodoRepository repository) { this(repository, false); }
    private TodoReminderService(TodoRepository repository, boolean ownsRepository) {
        this.repository=repository; this.ownsRepository=ownsRepository;
    }
    public Optional<ReminderBatch> evaluate(LocalDateTime now) {
        if(now==null)return Optional.empty();
        return repository.claim(now).map(p->new ReminderBatch(p.todos(),p.slots(),p.uid()));
    }
    public void commit(ReminderBatch batch, LocalDate today) {
        if(batch!=null && batch.uid()!=null)repository.acknowledge(batch.uid());
    }
    public void close() { if(ownsRepository)repository.close(); }

    /**
     * 计算下次提醒时间。
     *
     * @param now 当前时间
     * @return 下次提醒的日期时间
     */
    public static LocalDateTime nextClock(LocalDateTime now) {
        LocalDate day = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        if (time.isBefore(TEN)) {
            return LocalDateTime.of(day, TEN);
        }
        if (time.isBefore(FOURTEEN)) {
            return LocalDateTime.of(day, FOURTEEN);
        }
        return LocalDateTime.of(day.plusDays(1), TEN);
    }

}
