package com.mordor.kelly.kelsy.service;

public final class CjkNgrams {

    private CjkNgrams() {
    }

    public static String forIndex(String raw) {
        return expand(raw);
    }

    public static String forQuery(String raw) {
        return expand(raw);
    }

    private static String expand(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        StringBuilder other = new StringBuilder();
        Runnable flushCjk = () -> {
            if (cjk.isEmpty()) {
                return;
            }
            String run = cjk.toString();
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(run);
            if (run.length() >= 2) {
                int[] points = run.codePoints().toArray();
                for (int i = 0; i < points.length - 1; i++) {
                    out.append(' ').appendCodePoint(points[i]).appendCodePoint(points[i+1]);
                }
            }
            cjk.setLength(0);
        };
        Runnable flushOther = () -> {
            if (other.isEmpty()) {
                return;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(other);
            other.setLength(0);
        };
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                flushOther.run();
                cjk.appendCodePoint(cp);
            } else if (Character.isWhitespace(cp)) {
                flushCjk.run();
                flushOther.run();
            } else {
                flushCjk.run();
                other.appendCodePoint(cp);
            }
        }
        flushCjk.run();
        flushOther.run();
        return out.toString();
    }

    static boolean isCjk(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN;
    }
}
