package com.mordor.kelly.kelsy.storage;

import com.mordor.kelly.kelsy.service.CjkNgrams;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

public final class SearchTerms {
    private static final Pattern RUN = Pattern.compile("\\p{IsHan}+|[\\p{L}\\p{N}&&[^\\p{IsHan}]]+");
    private SearchTerms() {}
    public static String index(String text) {
        return CjkNgrams.forIndex(normalize(text));
    }
    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    /** Each lexical run retains its own constraints, regardless of adjacent scripts/punctuation. */
    public static List<String> groups(String text) {
        var matcher = RUN.matcher(normalize(text));
        Set<String> groups = new LinkedHashSet<>();
        while (matcher.find()) {
            String run = matcher.group();
            int[] points = run.codePoints().toArray();
            List<String> tokens = new ArrayList<>();
            if (points.length > 2 && Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN) {
                for (int i = 0; i < points.length - 1; i++) tokens.add(new String(points, i, 2));
            } else tokens.add(run);
            groups.add("(" + String.join(" AND ", tokens.stream().distinct().map(t -> "\"" + t + "\"").toList()) + ")");
        }
        return List.copyOf(groups);
    }
    public static String match(String text) { return String.join(" AND ", groups(text)); }
}
