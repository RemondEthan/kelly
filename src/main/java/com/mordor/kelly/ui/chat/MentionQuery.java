package com.mordor.kelly.ui.chat;

import com.mordor.kelly.kelsy.KelsyMention;
import com.mordor.kelly.model.RoomMember;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MentionQuery {

    public record Token(int atIndex, String query) {
    }

    public record Applied(String text, int caret) {
    }

    private MentionQuery() {
    }

    public static Optional<Token> parse(String text, int caret) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        int pos = Math.max(0, Math.min(caret, text.length()));
        int i = pos - 1;
        while (i >= 0 && !Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        int wordStart = i + 1;
        if (wordStart >= pos || text.charAt(wordStart) != '@') {
            return Optional.empty();
        }
        return Optional.of(new Token(wordStart, text.substring(wordStart + 1, pos)));
    }

    public static List<RoomMember> candidates(List<RoomMember> members, String query) {
        String q = query == null ? "" : query;
        List<RoomMember> out = new ArrayList<>();
        if (members != null) {
            for (RoomMember m : members) {
                if (m == null || m.self()) {
                    continue;
                }
                String name = m.username() == null ? "" : m.username();
                if (!q.isEmpty() && !name.regionMatches(true, 0, q, 0, q.length())) {
                    continue;
                }
                out.add(m);
            }
        }
        out.sort((a, b) -> Boolean.compare(b.isKelsy(), a.isKelsy()));
        return List.copyOf(out);
    }

    public static Applied apply(String text, int atIndex, int caret, String nickname) {
        String src = text == null ? "" : text;
        int at = Math.max(0, Math.min(atIndex, src.length()));
        int pos = Math.max(at, Math.min(caret, src.length()));
        String repl = KelsyMention.insert(nickname);
        String next = src.substring(0, at) + repl + src.substring(pos);
        return new Applied(next, at + repl.length());
    }
}
