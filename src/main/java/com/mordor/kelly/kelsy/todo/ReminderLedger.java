package com.mordor.kelly.kelsy.todo;

import com.mordor.kelly.common.Diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Set;

public final class ReminderLedger {

    private final Path file;
    private LocalDate date;
    private final EnumSet<ReminderSlot> slots = EnumSet.noneOf(ReminderSlot.class);

    private ReminderLedger(Path file) {
        this.file = file;
    }

    public static ReminderLedger open(Path file) {
        ReminderLedger ledger = new ReminderLedger(file);
        ledger.load();
        return ledger;
    }

    public boolean fired(LocalDate day, ReminderSlot slot) {
        return day != null && slot != null && day.equals(date) && slots.contains(slot);
    }

    public void mark(LocalDate day, Set<ReminderSlot> extra) {
        if (day == null || extra == null || extra.isEmpty()) {
            return;
        }
        if (!day.equals(date)) {
            date = day;
            slots.clear();
        }
        slots.addAll(extra);
        save();
    }

    private void load() {
        date = null;
        slots.clear();
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            String line = Files.readString(file).strip();
            if (line.isBlank()) {
                return;
            }
            String[] parts = line.split("\\s+");
            date = LocalDate.parse(parts[0]);
            for (int i = 1; i < parts.length; i++) {
                try {
                    slots.add(ReminderSlot.valueOf(parts[i]));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException | DateTimeParseException e) {
            date = null;
            slots.clear();
        }
    }

    private void save() {
        if (file == null || date == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(date.toString());
        for (ReminderSlot slot : ReminderSlot.values()) {
            if (slots.contains(slot)) {
                sb.append(' ').append(slot.name());
            }
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, sb.toString());
        } catch (IOException e) {
            Diagnostics.warn("todo", "无法写入提醒账本: %s", e.getMessage());
        }
    }
}
