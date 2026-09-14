package com.mordor.kelly.ui.chat.font;

import com.mordor.kelly.app.Kelly;

import java.util.prefs.Preferences;

/**
 * 聊天字体设置的持久化服务。
 *
 * <p>使用 {@link Preferences} API 将字体设置存储在用户级偏好中（跨会话保留）。
 * 在 Linux 上存储在 {@code ~/.java/.userPrefs/} 目录下，
 * 在 Windows 上存储在注册表中。</p>
 *
 * <h3>存储键值</h3>
 * <ul>
 *   <li>{@value #KEY_FAMILY} - 字体族名称字符串</li>
 *   <li>{@value #KEY_SCALE} - 缩放等级枚举名（如 "MEDIUM"）</li>
 * </ul>
 *
 * <p>线程安全：Preferences 本身是线程安全的。</p>
 */
public final class ChatFontSettingsService {

    /** Preferences 存储键：字体族名称 */
    private static final String KEY_FAMILY = "chat.font.family";
    /** Preferences 存储键：缩放等级枚举名 */
    private static final String KEY_SCALE = "chat.font.scale";

    /** Java Preferences 存储后端 */
    private final Preferences prefs;

    /**
     * 使用 Kelly 应用的默认 Preferences 节点。
     */
    public ChatFontSettingsService() {
        this(Preferences.userNodeForPackage(Kelly.class));
    }

    /**
     * 使用指定的 Preferences 节点（便于测试注入）。
     *
     * @param prefs Preferences 存储后端
     */
    public ChatFontSettingsService(Preferences prefs) {
        this.prefs = prefs;
    }

    /**
     * 从 Preferences 加载字体设置。
     *
     * <p>容错处理：如果存储的缩放等级字符串无效，回退到 MEDIUM。</p>
     *
     * @return 当前保存的字体设置，若无保存则返回默认值
     */
    public ChatFontSettings load() {
        String family = prefs.get(KEY_FAMILY, null);
        ChatFontScale scale;
        try {
            scale = ChatFontScale.valueOf(prefs.get(KEY_SCALE, "MEDIUM"));
        } catch (IllegalArgumentException | NullPointerException e) {
            scale = ChatFontScale.MEDIUM;  // 回退到默认值
        }
        return new ChatFontSettings(
                (family == null || family.isBlank()) ? null : family,
                scale);
    }

    /**
     * 将字体设置保存到 Preferences。
     *
     * @param s 要保存的字体设置
     */
    public void save(ChatFontSettings s) {
        if (s.fontFamily() == null) {
            prefs.remove(KEY_FAMILY);  // null 则删除键
        } else {
            prefs.put(KEY_FAMILY, s.fontFamily());
        }
        prefs.put(KEY_SCALE, s.scale().name());  // 存储枚举名
    }
}