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

    private static final LocalTime TEN = LocalTime.of(10, 0);
    private static final LocalTime FOURTEEN = LocalTime.of(14, 0);

    private final Path knowledgeRoot;
    private final ReminderLedger ledger;

    public TodoReminderService(Path knowledgeRoot) {
        this.knowledgeRoot = knowledgeRoot;
        this.ledger = ReminderLedger.open(knowledgeRoot.resolve(".todo-reminder-ledger"));
    }

    public Optional<ReminderBatch> evaluate(LocalDateTime now) {
        if (now == null) {
            return Optional.empty();
        }
        LocalDate today = now.toLocalDate();
        List<TodoCard> window = new ArrayList<>();
        for (TodoCard card : TodoScanner.list(knowledgeRoot)) {
            if (TodoReminderPolicy.inWindow(card, today)) {
                window.add(card);
            }
        }
        window = TodoReminderPolicy.sort(window);
        EnumSet<ReminderSlot> due = slotsDue(now, ledger);
        if (window.isEmpty() || due.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReminderBatch(window, due));
    }

    public void commit(ReminderBatch batch, LocalDate today) {
        if (batch == null || today == null) {
            return;
        }
        ledger.mark(today, batch.slots());
    }

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
