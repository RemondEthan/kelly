/**
 * @提及解析工具类。
 *
 * <p>用于处理聊天消息中的 @kelsy（或自定义昵称）提及语法。
 * 当用户在群聊中输入 "@tars 请帮我查一下..." 时，系统需要识别这是一条
 * 发给 AI 助手的消息，而非普通聊天消息。
 *
 * <p>核心功能：
 * <ul>
 *   <li>{@link #insert} - 生成 @提及文本（用于 UI 自动补全）</li>
 *   <li>{@link #isMention} - 判断消息是否以 @提及 开头</li>
 *   <li>{@link #strip} - 从消息中移除 @提及前缀，提取纯指令内容</li>
 * </ul>
 *
 * <p>匹配规则：
 * <ul>
 *   <li>大小写不敏感（case-insensitive）</li>
 *   <li>昵称为空时默认使用 "secretary"（RoomMember.SECRETARY_NAME）</li>
 *   <li>提及后必须跟空格或字符串结束（避免误匹配 "@tarsus" 这样的词）</li>
 * </ul>
 */
package com.mordor.kelly.kelsy;

import com.mordor.kelly.model.RoomMember;

public final class KelsyMention {

    /** 私有构造函数，防止实例化工具类 */
    private KelsyMention() {
    }

    /**
     * 生成 @提及文本，用于 UI 自动补全或消息预填充。
     *
     * @param nickname 助手昵称（如 "tars"）
     * @return 格式化后的 @提及文本，如 "@tars "
     */
    public static String insert(String nickname) {
        return "@" + normalize(nickname) + " ";
    }

    /**
     * 判断消息文本是否以 @提及 开头。
     *
     * <p>匹配逻辑：
     * <ol>
     *   <li>将昵称规范化（去除空白，空值使用默认昵称）</li>
     *   <li>构建目标模式 "@昵称"</li>
     *   <li>检查消息是否以此模式开头（大小写不敏感）</li>
     *   <li>模式后必须跟空格或字符串结束</li>
     * </ol>
     *
     * @param text     用户输入的消息文本
     * @param nickname 助手昵称
     * @return true 如果消息是 @提及 格式
     */
    public static boolean isMention(String text, String nickname) {
        String at = "@" + normalize(nickname);
        String body = text == null ? "" : text.strip();
        int n = at.length();
        // 消息长度不足或前缀不匹配
        if (body.length() < n || !body.regionMatches(true, 0, at, 0, n)) {
            return false;
        }
        // 完全匹配（如 "@tars"）或后跟空格（如 "@tars 你好"）
        return body.length() == n || Character.isWhitespace(body.charAt(n));
    }

    /**
     * 从消息中移除 @提及前缀，提取纯指令内容。
     *
     * <p>示例：
     * <ul>
     *   <li>"@tars 今天天气怎么样" → "今天天气怎么样"</li>
     *   <li>"@tars" → ""（空字符串）</li>
     *   <li>"hello" → "hello"（无提及，原样返回）</li>
     * </ul>
     *
     * @param text     用户输入的消息文本
     * @param nickname 助手昵称
     * @return 移除 @提及 前缀后的纯文本内容
     */
    public static String strip(String text, String nickname) {
        if (!isMention(text, nickname)) {
            return text == null ? "" : text.strip();
        }
        String body = text.strip();
        int n = ("@" + normalize(nickname)).length();
        return body.length() == n ? "" : body.substring(n).strip();
    }

    /**
     * 规范化昵称：去除首尾空白，空值使用默认昵称。
     *
     * @param nickname 原始昵称
     * @return 规范化后的昵称
     */
    private static String normalize(String nickname) {
        String n = nickname == null ? "" : nickname.strip();
        return n.isEmpty() ? RoomMember.SECRETARY_NAME : n;
    }
}
