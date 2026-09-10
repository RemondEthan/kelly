package com.mordor.kelly.kelsy.todo;

import java.time.LocalDate;

public record TodoCard(String title, LocalDate due, TodoStatus status, String relativePath) {
}
