package com.mordor.kelly.kelsy.todo;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public final class TodoReminderPolicy {

    public static final int LEAD_DAYS = 3;

    private TodoReminderPolicy() {
    }

    public static boolean inWindow(TodoCard card, LocalDate today) {
        if (card == null || today == null || card.status() != TodoStatus.OPEN) {
            return false;
        }
        return !today.isBefore(card.due().minusDays(LEAD_DAYS));
    }

    public static List<TodoCard> sort(List<TodoCard> cards) {
        return cards.stream()
                .sorted(Comparator.comparing(TodoCard::due).thenComparing(TodoCard::title))
                .toList();
    }

    public static boolean overdue(TodoCard card, LocalDate today) {
        return card != null && today != null && today.isAfter(card.due());
    }
}
