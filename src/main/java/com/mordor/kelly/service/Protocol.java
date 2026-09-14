package com.mordor.kelly.service;

/**
 * 与 KServer 对齐的 JSON 文本协议（WebSocket 文本帧）。
 * 不引入第三方 JSON 库，便于 jlink 打包。
 */
public final class Protocol {

    private Protocol() {}

    public record Incoming(
            String type,
            String content,
            String username,
            String padding,
            String message,
            int userId
    ) {}

    public static String register(String imCode, String username) {
        return register(imCode, username, 0);
    }

    public static String register(String imCode, String username, int userId) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"register\",\"data\":{\"im_code\":")
                .append(quote(imCode))
                .append(",\"username\":")
                .append(quote(username));
        if (userId > 0) {
            sb.append(",\"user_id\":").append(userId);
        }
        sb.append("}}");
        return sb.toString();
    }

    public static String text(String content, String username) {
        return typed("text", content, username);
    }

    public static String image(String content, String username) {
        return typed("image", content, username);
    }

    public static String imageChunk(String content, String username) {
        return typed("image_chunk", content, username);
    }

    private static String typed(String type, String content, String username) {
        return "{\"type\":" + quote(type) + ",\"data\":{\"content\":"
                + quote(content) + ",\"username\":" + quote(username) + "}}";
    }

    public static String avatar(String content) {
        return "{\"type\":\"avatar\",\"data\":{\"content\":" + quote(content) + "}}";
    }

    public static Incoming parse(String json) {
        if (json == null || json.isBlank()) {
            return new Incoming("", "", "", "", "", 0);
        }
        String type = stringField(json, "type");
        String data = objectField(json, "data");
        String src = data != null ? data : json;
        return new Incoming(
                type == null ? "" : type,
                nullToEmpty(stringField(src, "content")),
                nullToEmpty(stringField(src, "username")),
                nullToEmpty(stringField(src, "padding")),
                nullToEmpty(stringField(src, "message")),
                intField(src, "user_id")
        );
    }

    static String quote(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String objectField(String json, String key) {
        int start = indexOfKey(json, key);
        if (start < 0) {
            return null;
        }
        int i = skipWs(json, start);
        if (i >= json.length() || json.charAt(i) != '{') {
            return null;
        }
        int depth = 1;
        boolean inStr = false;
        boolean escape = false;
        for (int j = i + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (inStr) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(i, j + 1);
                }
            }
        }
        return null;
    }

    static String stringField(String json, String key) {
        int start = indexOfKey(json, key);
        if (start < 0) {
            return null;
        }
        int i = skipWs(json, start);
        if (i >= json.length() || json.charAt(i) != '"') {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int j = i + 1; j < json.length(); j++) {
            char c = json.charAt(j);
            if (escape) {
                sb.append(switch (c) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    default -> c;
                });
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    static int intField(String json, String key) {
        int start = indexOfKey(json, key);
        if (start < 0) {
            return 0;
        }
        int i = skipWs(json, start);
        int j = i;
        if (j < json.length() && json.charAt(j) == '-') {
            j++;
        }
        while (j < json.length() && Character.isDigit(json.charAt(j))) {
            j++;
        }
        if (j == i || (j == i + 1 && json.charAt(i) == '-')) {
            return 0;
        }
        try {
            return Integer.parseInt(json.substring(i, j));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int indexOfKey(String json, String key) {
        String needle = "\"" + key + "\"";
        int from = 0;
        while (from < json.length()) {
            int at = json.indexOf(needle, from);
            if (at < 0) {
                return -1;
            }
            int after = skipWs(json, at + needle.length());
            if (after < json.length() && json.charAt(after) == ':') {
                return skipWs(json, after + 1);
            }
            from = at + needle.length();
        }
        return -1;
    }

    private static int skipWs(String json, int i) {
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }
}
