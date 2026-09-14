package com.mordor.kelly.service;

/**
 * JSON 协议编解码器 - 与 KServer 服务器通信的协议实现
 *
 * 本类实现了与 KServer 服务器对齐的 JSON 文本协议
 * 主要特点：
 * 1. 手写 JSON 解析，不引入第三方库（便于 jlink 打包）
 * 2. 支持所有消息类型的编解码
 * 3. 提供 JSON 字符串转义功能
 * 4. 提供 JSON 字段提取功能
 *
 * 消息格式示例：
 * - 注册消息：{"type":"register","data":{"im_code":"xxx","username":"xxx","user_id":123}}
 * - 文本消息：{"type":"text","data":{"content":"加密内容","username":"xxx"}}
 * - 头像消息：{"type":"avatar","data":{"content":"加密的Base64数据"}}
 */
public final class Protocol {

    /**
     * 私有构造方法，防止实例化
     * 本类所有方法都是静态的，无需实例化
     */
    private Protocol() {}

    /**
     * 服务器消息记录类
     * 表示从服务器接收到的解析后的消息
     */
    public record Incoming(
            String type,        // 消息类型（registered/text/avatar/error等）
            String content,     // 消息内容（加密的）
            String username,    // 发送者用户名
            String padding,     // 用于密钥派生的填充字符串
            String message,     // 错误消息或通知消息
            int userId          // 用户ID
    ) {}

    /**
     * 构造注册消息
     * 用于客户端向服务器注册身份
     *
     * @param imCode 聊天室标识码
     * @param username 用户名
     * @return JSON 格式的注册消息
     */
    public static String register(String imCode, String username) {
        return register(imCode, username, 0);
    }

    /**
     * 构造注册消息（带用户ID）
     * 如果之前已经注册过，可以带上用户ID，避免重新分配
     *
     * @param imCode 聊天室标识码
     * @param username 用户名
     * @param userId 之前分配的用户ID（0表示首次注册）
     * @return JSON 格式的注册消息
     */
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

    /**
     * 构造文本消息
     * 消息内容会被加密后发送
     *
     * @param content 加密后的内容
     * @param username 发送者用户名
     * @return JSON 格式的文本消息
     */
    public static String text(String content, String username) {
        return typed("text", content, username);
    }

    /**
     * 构造图片消息
     * 包含图片的元数据（id、caption、mime等）
     *
     * @param content 加密后的图片元数据
     * @param username 发送者用户名
     * @return JSON 格式的图片消息
     */
    public static String image(String content, String username) {
        return typed("image", content, username);
    }

    /**
     * 构造图片分片消息
     * 大图片会被拆分成多个分片发送
     *
     * @param content 加密后的分片数据
     * @param username 发送者用户名
     * @return JSON 格式的图片分片消息
     */
    public static String imageChunk(String content, String username) {
        return typed("image_chunk", content, username);
    }

    /**
     * 构造通用消息
     * 用于构造 text、image、image_chunk 等消息
     *
     * @param type 消息类型
     * @param content 加密后的内容
     * @param username 发送者用户名
     * @return JSON 格式的通用消息
     */
    private static String typed(String type, String content, String username) {
        return "{\"type\":" + quote(type) + ",\"data\":{\"content\":"
                + quote(content) + ",\"username\":" + quote(username) + "}}";
    }

    /**
     * 构造头像消息
     * 用于发送用户的头像数据
     *
     * @param content 加密后的头像数据（Base64编码的PNG）
     * @return JSON 格式的头像消息
     */
    public static String avatar(String content) {
        return "{\"type\":\"avatar\",\"data\":{\"content\":" + quote(content) + "}}";
    }

    /**
     * 解析 JSON 消息
     * 将 JSON 字符串解析为 Incoming 记录对象
     * 支持嵌套的 data 字段
     *
     * @param json JSON 字符串
     * @return 解析后的 Incoming 对象，解析失败返回空对象
     */
    public static Incoming parse(String json) {
        if (json == null || json.isBlank()) {
            return new Incoming("", "", "", "", "", 0);
        }
        String type = stringField(json, "type");
        String data = objectField(json, "data");
        // 如果有 data 字段，从 data 中提取字段；否则从顶层提取
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

    /**
     * JSON 字符串转义
     * 将字符串转换为 JSON 安全的格式
     * 处理特殊字符：引号、反斜杠、换行符、制表符等
     *
     * @param raw 原始字符串
     * @return 转义后的 JSON 字符串（带引号）
     */
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
                        // 控制字符使用 Unicode 转义
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

    /**
     * 空值转空字符串
     * @param s 输入字符串
     * @return 非空字符串
     */
    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 提取 JSON 对象字段
     * 查找指定键对应的 JSON 对象值
     * 支持嵌套对象的正确解析
     *
     * @param json JSON 字符串
     * @param key 要查找的键
     * @return 对象值字符串，未找到返回 null
     */
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

    /**
     * 提取 JSON 字符串字段
     * 查找指定键对应的字符串值
     * 支持转义字符的正确解析
     *
     * @param json JSON 字符串
     * @param key 要查找的键
     * @return 字符串值，未找到返回 null
     */
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

    /**
     * 提取 JSON 整数字段
     * 查找指定键对应的整数值
     * 支持负数
     *
     * @param json JSON 字符串
     * @param key 要查找的键
     * @return 整数值，未找到或解析失败返回 0
     */
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

    /**
     * 查找 JSON 键的位置
     * 查找 "key": 模式的位置
     * 跳过值中出现的相同字符串
     *
     * @param json JSON 字符串
     * @param key 要查找的键
     * @return 键值对中值的起始位置，未找到返回 -1
     */
    private static int indexOfKey(String json, String key) {
        String needle = "\"" + key + "\"";
        int from = 0;
        while (from < json.length()) {
            int at = json.indexOf(needle, from);
            if (at < 0) {
                return -1;
            }
            // 检查后面是否跟着冒号
            int after = skipWs(json, at + needle.length());
            if (after < json.length() && json.charAt(after) == ':') {
                return skipWs(json, after + 1);
            }
            from = at + needle.length();
        }
        return -1;
    }

    /**
     * 跳过空白字符
     * @param json JSON 字符串
     * @param i 起始位置
     * @return 第一个非空白字符的位置
     */
    private static int skipWs(String json, int i) {
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        return i;
    }
}
