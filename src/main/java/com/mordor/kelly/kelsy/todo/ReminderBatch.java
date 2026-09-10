package com.mordor.kelly.kelsy.todo;

import java.util.EnumSet;
import java.util.List;

public record ReminderBatch(List<TodoCard> todos, EnumSet<ReminderSlot> slots) {
}
