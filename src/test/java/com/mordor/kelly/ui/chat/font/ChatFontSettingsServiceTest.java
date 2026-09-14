package com.mordor.kelly.ui.chat.font;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

class ChatFontSettingsServiceTest {

    private static Preferences inMemoryPrefs() {
        return new AbstractPreferences(null, "") {
            private final Map<String, String> store = new HashMap<>();

            @Override protected void putSpi(String k, String v) { store.put(k, v); }
            @Override protected String getSpi(String k) { return store.get(k); }
            @Override protected void removeSpi(String k) { store.remove(k); }
            @Override protected void removeNodeSpi() { }
            @Override protected void flushSpi() { }
            @Override protected void syncSpi() { }
            @Override protected AbstractPreferences childSpi(String n) { return null; }
            @Override protected String[] childrenNamesSpi() { return new String[0]; }
            @Override protected String[] keysSpi() { return store.keySet().toArray(new String[0]); }
        };
    }

    @Test
    void load_emptyPrefs_returnsDefaults() {
        ChatFontSettingsService svc = new ChatFontSettingsService(inMemoryPrefs());
        ChatFontSettings s = svc.load();
        assertNull(s.fontFamily());
        assertEquals(ChatFontScale.MEDIUM, s.scale());
    }

    @Test
    void roundTrip_familyAndScale() {
        ChatFontSettingsService svc = new ChatFontSettingsService(inMemoryPrefs());
        svc.save(new ChatFontSettings("Microsoft YaHei", ChatFontScale.LARGE));
        ChatFontSettings loaded = svc.load();
        assertEquals("Microsoft YaHei", loaded.fontFamily());
        assertEquals(ChatFontScale.LARGE, loaded.scale());
    }

    @Test
    void save_nullFamily_removesKey() {
        ChatFontSettingsService svc = new ChatFontSettingsService(inMemoryPrefs());
        svc.save(new ChatFontSettings("Arial", ChatFontScale.SMALL));
        svc.save(new ChatFontSettings(null, ChatFontScale.SMALL));
        ChatFontSettings loaded = svc.load();
        assertNull(loaded.fontFamily());
        assertEquals(ChatFontScale.SMALL, loaded.scale());
    }

    @Test
    void load_corruptScaleName_fallsBackToMedium() {
        Preferences real = Preferences.userRoot().node("kelly-test-corrupt-" + System.nanoTime());
        try {
            real.put("chat.font.family", "Anything");
            real.put("chat.font.scale", "NOT_AN_ENUM");
            ChatFontSettingsService svc = new ChatFontSettingsService(real);
            ChatFontSettings s = svc.load();
            assertEquals(ChatFontScale.MEDIUM, s.scale());
            assertEquals("Anything", s.fontFamily());
        } finally {
            try { real.removeNode(); } catch (Exception e) { /* ignore */ }
        }
    }
}