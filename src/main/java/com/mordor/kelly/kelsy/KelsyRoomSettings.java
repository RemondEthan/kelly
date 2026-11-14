/**
 * 聊天室级别的 Kelsy 配置管理。
 *
 * <p>每个聊天室（IM 会话）可以独立配置 Kelsy 助手的启用状态、头像和昵称。
 * 使用 Java Preferences API 持久化存储，重启后配置不会丢失。
 *
 * <p>存储键使用房间标识（imCode）的 SHA-256 哈希值作为前缀，
 * 避免键名冲突和敏感信息泄露。
 *
 * <p>配置项：
 * <ul>
 *   <li><b>enabled</b> - 该房间是否启用 Kelsy 助手（默认 false）</li>
 *   <li><b>avatarPath</b> - Kelsy 在该房间的自定义头像路径</li>
 *   <li><b>nickname</b> - Kelsy 在该房间的昵称（默认 "tars"）</li>
 * </ul>
 *
 * <p>使用示例：
 * <pre>
 *   KelsyRoomSettings settings = new KelsyRoomSettings();
 *   settings.enable("room-123", "/path/to/avatar.png", "小秘");
 *   boolean enabled = settings.enabled("room-123"); // true
 * </pre>
 */
package com.mordor.kelly.kelsy;

import com.mordor.kelly.service.ChatHistory;

import java.util.prefs.Preferences;

public final class KelsyRoomSettings {

    /** Java Preferences 存储节点 */
    private final Preferences prefs;

    /**
     * 使用当前用户默认的 Preferences 节点创建实例。
     */
    public KelsyRoomSettings() {
        this(Preferences.userNodeForPackage(KelsyRoomSettings.class));
    }

    /**
     * 使用指定的 Preferences 节点创建实例（便于测试）。
     *
     * @param prefs Preferences 存储节点
     */
    public KelsyRoomSettings(Preferences prefs) {
        this.prefs = prefs;
    }

    /**
     * 检查指定房间是否启用了 Kelsy 助手。
     *
     * @param imCode 房间标识（IM 会话 ID）
     * @return true 如果该房间已启用 Kelsy
     */
    public boolean enabled(String imCode) {
        return prefs.getBoolean(onKey(imCode), false);
    }

    /**
     * 获取指定房间的 Kelsy 自定义头像路径。
     *
     * @param imCode 房间标识
     * @return 头像文件路径，未设置时返回空字符串
     */
    public String avatarPath(String imCode) {
        return prefs.get(avatarKey(imCode), "");
    }

    /**
     * 获取指定房间的 Kelsy 昵称。
     *
     * @param imCode 房间标识
     * @return 昵称，未设置时默认返回 "tars"
     */
    public String nickname(String imCode) {
        String n = prefs.get(nickerKey(imCode), "");
        return n == null || n.isBlank() ? "tars" : n;
    }

    /**
     * 启用指定房间的 Kelsy 助手，并设置头像和昵称。
     *
     * @param imCode     房间标识
     * @param avatarPath 头像路径（可为 null）
     * @param nickname   昵称（可为 null，使用默认值 "tars"）
     */
    public void enable(String imCode, String avatarPath, String nickname) {
        prefs.putBoolean(onKey(imCode), true);
        prefs.put(avatarKey(imCode), avatarPath == null ? "" : avatarPath);
        String n = nickname == null ? "" : nickname.strip();
        prefs.put(nickerKey(imCode), n.isEmpty() ? "tars" : n);
    }

    /**
     * 禁用指定房间的 Kelsy 助手，并清除头像和昵称配置。
     *
     * @param imCode 房间标识
     */
    public void disable(String imCode) {
        prefs.putBoolean(onKey(imCode), false);
        prefs.remove(avatarKey(imCode));
        prefs.remove(nickerKey(imCode));
    }

    /** 生成启用状态的 Preferences 键（使用 SHA-256 哈希） */
    private static String onKey(String imCode) {
        return "on." + ChatHistory.sha256Hex(imCode);
    }

    /** 生成头像路径的 Preferences 键 */
    private static String avatarKey(String imCode) {
        return "avatar." + ChatHistory.sha256Hex(imCode);
    }

    /** 生成昵称的 Preferences 键 */
    private static String nickerKey(String imCode) {
        return "nick." + ChatHistory.sha256Hex(imCode);
    }
}
