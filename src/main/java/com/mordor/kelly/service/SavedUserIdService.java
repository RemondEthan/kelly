package com.mordor.kelly.service;

import java.util.prefs.Preferences;

/** 本机记住的 server user_id。不含口令。键是 imCode+用户名的哈希（Preferences 键最长 80）。 */
public final class SavedUserIdService {

    private final Preferences prefs;

    public SavedUserIdService() {
        this(Preferences.userNodeForPackage(SavedUserIdService.class));
    }

    public SavedUserIdService(Preferences prefs) {
        this.prefs = prefs;
    }

    public int get(String imCode, String username) {
        return prefs.getInt(key(imCode, username), 0);
    }

    public void put(String imCode, String username, int userId) {
        prefs.putInt(key(imCode, username), userId);
    }

    private static String key(String imCode, String username) {
        return "uid." + ChatHistory.sha256Hex(
                (imCode == null ? "" : imCode) + "\n" + (username == null ? "" : username));
    }
}
