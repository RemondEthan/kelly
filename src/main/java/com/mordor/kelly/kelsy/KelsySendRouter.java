/**
 * Kelsy 消息发送路由器。
 *
 * <p>决定用户输入的消息应该发给谁（普通聊天还是 AI 助手），
 * 以及如何处理斜杠命令（/note, /today, /find 等）。
 *
 * <p>路由决策流程：
 * <pre>
 *   用户输入 → 是否启用？→ 是否 @提及？→ 是否忙碌？
 *       ↓ 否            ↓ 否
 *     PEER（普通聊天）  PEER（普通聊天）
 *       ↓ 是            ↓ 是
 *     UNCONFIGURED？→ 返回错误提示
 *       ↓ 已配置
 *     内容是否为空？→ 返回错误提示
 *       ↓ 非空
 *     解析斜杠命令 → FIND / ASK / SLASH_ERROR
 * </pre>
 *
 * <p>路由结果类型（{@link Kind}）：
 * <ul>
 *   <li><b>PEER</b> - 普通聊天消息，转发给其他用户</li>
 *   <li><b>ASK</b> - 发送给 AI 助手的普通提问</li>
 *   <li><b>FIND</b> - 知识库搜索命令（/find）</li>
 *   <li><b>BUSY</b> - 助手正在回复中，无法处理新消息</li>
 *   <li><b>UNCONFIGURED</b> - 助手未配置 API Key</li>
 *   <li><b>EMPTY_BODY</b> - @提及后无实际内容</li>
 *   <li><b>SLASH_ERROR</b> - 斜杠命令格式错误</li>
 * </ul>
 */
package com.mordor.kelly.kelsy;

import com.mordor.kelly.kelsy.service.SlashCommands;

public final class KelsySendRouter {

    /**
     * 路由结果类型枚举。
     * 决定消息的最终去向：普通聊天、AI 提问、知识搜索等。
     */
    public enum Kind {
        /** 普通聊天消息，发给其他用户 */
        PEER,
        /** 助手正在回复，无法处理新消息 */
        BUSY,
        /** 助手未配置（缺少 API Key） */
        UNCONFIGURED,
        /** @提及后无实际内容 */
        EMPTY_BODY,
        /** 发送给 AI 助手的普通提问 */
        ASK,
        /** 知识库搜索命令（/find 关键词） */
        FIND,
        /** 斜杠命令格式错误 */
        SLASH_ERROR
    }

    /**
     * 路由结果数据类。
     *
     * @param kind      路由结果类型
     * @param peerText  普通聊天时的原始文本（仅 PEER 类型有值）
     * @param outgoing  发送给 AI 助手的文本（ASK/FIND 类型有值）
     * @param error     错误提示信息（BUSY/UNCONFIGURED/EMPTY_BODY/SLASH_ERROR 类型有值）
     */
    public record Result(Kind kind, String peerText, String outgoing, String error) {
        /** 创建普通聊天消息的结果 */
        static Result peer(String text) {
            return new Result(Kind.PEER, text, null, null);
        }
    }

    /** 私有构造函数，防止实例化 */
    private KelsySendRouter() {
    }

    /**
     * 根据消息内容和当前状态决定路由。
     *
     * <p>判断优先级：
     * <ol>
     *   <li>未启用 → PEER（普通聊天）</li>
     *   <li>未 @提及 → PEER（普通聊天）</li>
     *   <li>忙碌中 → BUSY（拒绝新消息）</li>
     *   <li>未配置 → UNCONFIGURED（提示配置）</li>
     *   <li>内容为空 → EMPTY_BODY（提示输入内容）</li>
     *   <li>/find 命令 → FIND（知识搜索）</li>
     *   <li>其他斜杠命令 → ASK（AI 提问）</li>
     *   <li>普通文本 → ASK（AI 提问）</li>
     * </ol>
     *
     * @param enabled   该房间是否启用了 Kelsy
     * @param busy      助手是否正在回复中
     * @param configured 助手是否已配置 API Key
     * @param text      用户输入的原始文本
     * @param nickname  助手昵称（用于 @提及 匹配）
     * @return 路由结果
     */
    public static Result route(boolean enabled, boolean busy, boolean configured,
                               String text, String nickname) {
        String raw = text == null ? "" : text;
        // 未启用或不是 @提及 → 普通聊天
        if (!enabled || !KelsyMention.isMention(raw, nickname)) {
            return Result.peer(raw);
        }
        // 助手正在回复
        if (busy) {
            return new Result(Kind.BUSY, null, null, "秘书还在回复");
        }
        // 未配置 API Key
        if (!configured) {
            return new Result(Kind.UNCONFIGURED, null, null, null);
        }
        // 移除 @提及前缀，提取纯指令内容
        String body = KelsyMention.strip(raw, nickname);
        if (body.isEmpty()) {
            return new Result(Kind.EMPTY_BODY, null, null, "请输入要问秘书的内容");
        }
        // 解析斜杠命令
        SlashCommands.Result slash = SlashCommands.parse(body);
        if (slash.find()) {
            return new Result(Kind.FIND, null, slash.outgoing(), null);
        }
        if (!slash.send()) {
            return new Result(Kind.SLASH_ERROR, null, null, slash.error());
        }
        return new Result(Kind.ASK, null, slash.outgoing(), null);
    }
}
