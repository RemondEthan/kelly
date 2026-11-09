package com.mordor.kelly.kelsy.storage;

import com.mordor.kelly.kelsy.service.CjkNgrams;
import java.text.Normalizer;
import java.util.*;

public final class SearchTerms {
    private SearchTerms() {}
    public static String index(String text) {
        return CjkNgrams.forIndex(Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
    }
    public static String match(String text) {
        List<String> groups = new ArrayList<>();
        for (String word : (text == null ? "" : text.strip()).split("\\s+")) {
            var tokens = Arrays.stream(index(word).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> !t.isBlank()).distinct().toList();
            // Whole CJK runs are also emitted for indexing. Query by constituent bigrams.
            if (tokens.size() > 1 && tokens.getFirst().codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                tokens = tokens.subList(1, tokens.size());
            }
            if (!tokens.isEmpty()) groups.add("(" + String.join(" AND ", tokens.stream().map(t -> "\"" + t.replace("\"","\"\"") + "\"").toList()) + ")");
        }
        return String.join(" AND ", groups);
    }
}
