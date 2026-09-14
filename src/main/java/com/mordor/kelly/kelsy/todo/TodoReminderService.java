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

    /** 知识库根目录 */
    private final Path knowledgeRoot;

    /** 提醒账本（记录已触发的时间槽，防止重复提醒） */
    private final ReminderLedger ledger;

    /**
     * 创建提醒服务实例。
     *
     * @param knowledgeRoot 知识库根目录
     */
    public TodoReminderService(Path knowledgeRoot) {
        this.knowledgeRoot = knowledgeRoot;
        this.ledger = ReminderLedger.open(knowledgeRoot.resolve(".todo-reminder-ledger"));
    }

    /**
     * 评估当前是否需要触发提醒。
     *
     * @param now 当前时间
     * @return 提醒批次（包含待办列表和触发的时间槽），无需提醒时返回 Optional.empty()
     */
    public Optional<ReminderBatch> evaluate(LocalDateTime now) {
        if (now == null) {
            return Optional.empty();
        }
        LocalDate today = now.toLocalDate();
        // 扫描所有待办，筛选在提醒窗口内的
        List<TodoCard> window = new ArrayList<>();
        for (TodoCard card : TodoScanner.list(knowledgeRoot)) {
            if (TodoReminderPolicy.inWindow(card, today)) {
                window.add(card);
            }
        }
        window = TodoReminderPolicy.sort(window);
        // 检查哪些时间槽已到期且未触发
        EnumSet<ReminderSlot> due = slotsDue(now, ledger);
        if (window.isEmpty() || due.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReminderBatch(window, due));
    }

    /**
     * 提交提醒批次：标记时间槽已触发，防止重复提醒。
     *
     * @param batch 提醒批次
     * @param today 当前日期
     */
    public void commit(ReminderBatch batch, LocalDate today) {
        if (batch == null || today == null) {
            return;
        }
        ledger.mark(today, batch.slots());
    }

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

    /**
     * 检查哪些时间槽已到期且未触发。
     *
     * @param now   当前时间
     * @param ledger 提醒账本
     * @return 已到期的时间槽集合
     */
    static EnumSet<ReminderSlot> slotsDue(LocalDateTime now, ReminderLedger ledger) {
        EnumSet<ReminderSlot> due = EnumSet.noneOf(ReminderSlot.class);
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        if (!ledger.fired(today, ReminderSlot.LOGIN)) {
            due.add(ReminderSlot.LOGIN);
        }
        if (!time.isBefore(TEN) && !ledger.fired(today, ReminderSlot.TEN)) {
            due.add(ReminderSlot.TEN);
        }
        if (!time.isBefore(FOURTEEN) && !ledger.fired(today, ReminderSlot.FOURTEEN)) {
            due.add(ReminderSlot.FOURTEEN);
        }
        return due;
    }
}
