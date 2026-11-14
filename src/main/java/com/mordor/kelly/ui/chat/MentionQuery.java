package com.mordor.kelly.ui.chat;

import com.mordor.kelly.kelsy.KelsyMention;
import com.mordor.kelly.model.RoomMember;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @提及查询解析器：解析输入框中的 @ 提及语法，匹配成员列表。
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li>{@link #parse} - 从输入文本和光标位置中提取 @token（如 @张三）</li>
 *   <li>{@link #candidates} - 根据 token 查询匹配的成员列表</li>
 *   <li>{@link #apply} - 将选中的成员应用到文本中（替换 @token 为完整提及）</li>
 * </ol>
 *
 * <p>工具类，不能实例化。</p>
 */
public final class MentionQuery {

    /**
     * 解析出的 @ 提及 token。
     *
     * @param atIndex @ 符号在文本中的位置
     * @param query   @ 后面的查询文字（如 "张三"）
     */
    public record Token(int atIndex, String query) {
    }

    /**
     * 应用提及后的结果。
     *
     * @param text  替换后的完整文本
     * @param caret 替换后光标应处的位置
     */
    public record Applied(String text, int caret) {
    }

    private MentionQuery() {
    }

    /**
     * 解析输入文本，提取当前光标位置处的 @ 提及 token。
     *
     * <p>解析规则：从光标位置向前回溯，找到最近的 @ 符号，
     * 提取 @ 到光标之间的文字作为查询词。遇到空白字符或 @ 前无内容则返回空。</p>
     *
     * @param text  输入框全文
     * @param caret 当前光标位置（字符索引）
     * @return 解析出的 token，如果光标不在 @ 提及上下文中则返回空
     */
    public static Optional<Token> parse(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        // 将光标位置限制在有效范围内
        int pos = Math.max(0, Math.min(caret, text.length()));
        // 从光标前一个字符向前回溯，直到遇到空白字符
        int i = pos - 1;
        while (i >= 0 && !Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        int wordStart = i + 1;
        // 检查：单词起始位置必须是 @ 符号
        if (wordStart >= pos || text.charAt(wordStart) != '@') {
            return Optional.empty();
        }
        // 返回 token：@ 的位置 + @ 后面的查询文字
        return Optional.of(new Token(wordStart, text.substring(wordStart + 1, pos)));
    }

    /**
     * 根据查询词筛选匹配的成员候选人。
     *
     * <p>筛选规则：
     * <ul>
     *   <li>跳过 null 和自己（self）</li>
     *   <li>查询词为空时返回所有非自己成员</li>
     *   <li>查询词非空时按<strong>前缀匹配</strong>（大小写不敏感）筛选</li>
     *   <li>结果按 isKelsy 降序排列（秘书排在前面）</li>
     * </ul>
     *
     * @param members 所有房间成员
     * @param query   查询词
     * @return 匹配的成员列表（不可变）
     */
    public static List<RoomMember> candidates(List<RoomMember> members, String query) {
        String q = query == null ? "" : query;
        List<RoomMember> out = new ArrayList<>();
        if (members != null) {
            for (RoomMember m : members) {
                if (m == null || m.self()) {
                    continue;  // 跳过自己
                }
                String name = m.username() == null ? "" : m.username();
                // 前缀匹配：regionMatches(true, ...) 大小写不敏感
                if (!q.isEmpty() && !name.regionMatches(true, 0, q, 0, q.length())) {
                    continue;
                }
                out.add(m);
            }
        }
        // 排序：秘书（isKelsy）排在前面
        out.sort((a, b) -> Boolean.compare(b.isKelsy(), a.isKelsy()));
        return List.copyOf(out);  // 返回不可变副本
    }

    /**
     * 将选中的成员应用到文本中，替换 @token 为完整提及标记。
     *
     * @param text     原始文本
     * @param atIndex  @ 符号的位置
     * @param caret    当前光标位置
     * @param nickname 选中成员的昵称
     * @return 替换后的文本和新光标位置
     */
    public static Applied apply(String text, int atIndex, int caret, String nickname) {
        String src = text == null ? "" : text;
        int at = Math.max(0, Math.min(atIndex, src.length()));
        int pos = Math.max(at, Math.min(caret, src.length()));
        // KelsyMention.insert 生成完整提及标记（如 "@张三 "）
        String repl = KelsyMention.insert(nickname);
        String next = src.substring(0, at) + repl + src.substring(pos);
        return new Applied(next, at + repl.length());
    }
}
