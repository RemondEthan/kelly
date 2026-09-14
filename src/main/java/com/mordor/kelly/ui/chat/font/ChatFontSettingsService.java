package com.mordor.kelly.ui.chat.font;

import com.mordor.kelly.app.Kelly;

import java.util.prefs.Preferences;

public final class ChatFontSettingsService {

    private static final String KEY_FAMILY = "chat.font.family";
    private static final String KEY_SCALE = "chat.font.scale";

    private final Preferences prefs;

    public ChatFontSettingsService() {
        this(Preferences.userNodeForPackage(Kelly.class));
    }

    public ChatFontSettingsService(Preferences prefs) {
        this.prefs = prefs;
    }

    public ChatFontSettings load() {
        String family = prefs.get(KEY_FAMILY, null);
        ChatFontScale scale;
        try {
            scale = ChatFontScale.valueOf(prefs.get(KEY_SCALE, "MEDIUM"));
        } catch (IllegalArgumentException | NullPointerException e) {
            scale = ChatFontScale.MEDIUM;
        }
        return new ChatFontSettings(
                (family == null || family.isBlank()) ? null : family,
                scale);
    }

    public void save(ChatFontSettings s) {
        if (s.fontFamily() == null) {
            prefs.remove(KEY_FAMILY);
        } else {
            prefs.put(KEY_FAMILY, s.fontFamily());
        }
        prefs.put(KEY_SCALE, s.scale().name());
    }
}