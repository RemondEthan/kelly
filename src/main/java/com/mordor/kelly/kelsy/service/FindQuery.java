package com.mordor.kelly.kelsy.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record FindQuery(LocalDate fromInclusive, LocalDate toInclusive, List<String> keywords) {

    public static FindQuery parse(String raw, LocalDate today) {
        List<String> keywords = new ArrayList<>();
        LocalDate from = null;
        LocalDate to = null;
        String source = raw == null ? "" : raw.strip();
        if (source.isEmpty()) {
            return new FindQuery(null, null, List.of());
        }
        for (String tok : source.split("\\s+")) {
            String t = tok.toLowerCase(Locale.ROOT);
            if (t.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate d = LocalDate.parse(t);
                from = d;
                to = d;
            } else if (t.matches("\\d{4}-\\d{2}")) {
                YearMonth ym = YearMonth.parse(t);
                from = ym.atDay(1);
                to = ym.atEndOfMonth();
            } else if (t.matches("\\d{4}")) {
                from = LocalDate.of(Integer.parseInt(t), 1, 1);
                to = LocalDate.of(Integer.parseInt(t), 12, 31);
            } else if (t.equals("今天")) {
                from = today;
                to = today;
            } else if (t.equals("昨天")) {
                from = today.minusDays(1);
                to = today.minusDays(1);
            } else if (t.equals("上周")) {
                from = today.minusDays(6);
                to = today;
            } else if (t.equals("本月")) {
                YearMonth ym = YearMonth.from(today);
                from = ym.atDay(1);
                to = ym.atEndOfMonth();
            } else if (t.equals("上月")) {
                YearMonth ym = YearMonth.from(today).minusMonths(1);
                from = ym.atDay(1);
                to = ym.atEndOfMonth();
            } else if (t.equals("半年前")) {
                YearMonth center = YearMonth.from(today).minusMonths(6);
                from = center.minusMonths(1).atDay(1);
                to = center.plusMonths(1).atEndOfMonth();
            } else if (!tok.isBlank()) {
                keywords.add(tok);
            }
        }
        return new FindQuery(from, to, List.copyOf(keywords));
    }

    public boolean matchesDailyFile(String fileName) {
        if (fromInclusive == null) {
            return true;
        }
        if (!fileName.matches("\\d{4}-\\d{2}-\\d{2}\\.md")) {
            return false;
        }
        LocalDate d = LocalDate.parse(fileName.substring(0, 10));
        return !d.isBefore(fromInclusive) && !d.isAfter(toInclusive);
    }
}
