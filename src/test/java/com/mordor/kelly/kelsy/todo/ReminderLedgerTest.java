package com.mordor.kelly.kelsy.todo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderLedgerTest {

    @TempDir Path dir;

    @Test
    void marksAndPersistsSameDay() {
        Path file = dir.resolve(".todo-reminder-ledger");
        ReminderLedger a = ReminderLedger.open(file);
        LocalDate day = LocalDate.of(2026, 9, 4);
        assertFalse(a.fired(day, ReminderSlot.LOGIN));
        a.mark(day, EnumSet.of(ReminderSlot.LOGIN, ReminderSlot.TEN));
        assertTrue(a.fired(day, ReminderSlot.LOGIN));
        assertTrue(a.fired(day, ReminderSlot.TEN));
        assertFalse(a.fired(day, ReminderSlot.FOURTEEN));
        assertFalse(a.fired(day.plusDays(1), ReminderSlot.LOGIN));

        ReminderLedger b = ReminderLedger.open(file);
        assertTrue(b.fired(day, ReminderSlot.TEN));
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void corruptFileMeansUnfired() throws Exception {
        Path file = dir.resolve(".todo-reminder-ledger");
        Files.writeString(file, "not-a-ledger");
        ReminderLedger ledger = ReminderLedger.open(file);
        assertFalse(ledger.fired(LocalDate.of(2026, 9, 4), ReminderSlot.LOGIN));
    }
}
