package com.mordor.kelly.kelsy;

import com.mordor.kelly.kelsy.service.SlashCommands;

public final class KelsySendRouter {

    public enum Kind {
        PEER, BUSY, UNCONFIGURED, EMPTY_BODY, ASK, FIND, SLASH_ERROR
    }

    public record Result(Kind kind, String peerText, String outgoing, String error) {
        static Result peer(String text) {
            return new Result(Kind.PEER, text, null, null);
        }
    }

    private KelsySendRouter() {
    }

    public static Result route(boolean enabled, boolean busy, boolean configured,
                               String text, String nickname) {
        String raw = text == null ? "" : text;
        if (!enabled || !KelsyMention.isMention(raw, nickname)) {
            return Result.peer(raw);
        }
        if (busy) {
            return new Result(Kind.BUSY, null, null, "秘书还在回复");
        }
        if (!configured) {
            return new Result(Kind.UNCONFIGURED, null, null, null);
        }
        String body = KelsyMention.strip(raw, nickname);
        if (body.isEmpty()) {
            return new Result(Kind.EMPTY_BODY, null, null, "请输入要问秘书的内容");
        }
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
