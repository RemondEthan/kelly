package com.mordor.kelly.kelsy;

import com.mordor.kelly.service.ChatHistory;

import java.util.prefs.Preferences;

public final class KelsyRoomSettings {

    private final Preferences prefs;

    public KelsyRoomSettings() {
        this(Preferences.userNodeForPackage(KelsyRoomSettings.class));
    }

    public KelsyRoomSettings(Preferences prefs) {
        this.prefs = prefs;
    }

    public boolean enabled(String imCode) {
        return prefs.getBoolean(onKey(imCode), false);
    }

    public String avatarPath(String imCode) {
        return prefs.get(avatarKey(imCode), "");
    }

    public String nickname(String imCode) {
        String n = prefs.get(nickerKey(imCode), "");
        return n == null || n.isBlank() ? "tars" : n;
    }

    public void enable(String imCode, String avatarPath, String nickname) {
        prefs.putBoolean(onKey(imCode), true);
        prefs.put(avatarKey(imCode), avatarPath == null ? "" : avatarPath);
        String n = nickname == null ? "" : nickname.strip();
        prefs.put(nickerKey(imCode), n.isEmpty() ? "tars" : n);
    }

    public void disable(String imCode) {
        prefs.putBoolean(onKey(imCode), false);
        prefs.remove(avatarKey(imCode));
        prefs.remove(nickerKey(imCode));
    }

    private static String onKey(String imCode) {
        return "on." + ChatHistory.sha256Hex(imCode);
    }

    private static String avatarKey(String imCode) {
        return "avatar." + ChatHistory.sha256Hex(imCode);
    }

    private static String nickerKey(String imCode) {
        return "nick." + ChatHistory.sha256Hex(imCode);
    }
}
