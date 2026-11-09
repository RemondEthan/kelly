package com.mordor.kelly.ui.theme;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThemeServiceTest {

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
    void load_emptyPrefs_returnsLight() {
        ThemeService svc = new ThemeService(inMemoryPrefs());
        assertEquals(AppTheme.LIGHT, svc.load());
    }

    @Test
    void roundTrip_dark() {
        ThemeService svc = new ThemeService(inMemoryPrefs());
        svc.save(AppTheme.DARK);
        assertEquals(AppTheme.DARK, svc.load());
    }

    @Test
    void load_corruptThemeName_fallsBackToLight() {
        Preferences real = Preferences.userRoot().node("kelly-test-theme-" + System.nanoTime());
        try {
            real.put("ui.theme", "NOT_AN_ENUM");
            ThemeService svc = new ThemeService(real);
            assertEquals(AppTheme.LIGHT, svc.load());
        } finally {
            try { real.removeNode(); } catch (Exception e) { /* ignore */ }
        }
    }

    @Test
    void toggled_flipsBetweenLightAndDark() {
        assertEquals(AppTheme.DARK, AppTheme.LIGHT.toggled());
        assertEquals(AppTheme.LIGHT, AppTheme.DARK.toggled());
    }

    @Test
    void themeNames_areChinese() {
        assertEquals("浅色", AppTheme.LIGHT.label());
        assertEquals("深色", AppTheme.DARK.label());
    }
}
