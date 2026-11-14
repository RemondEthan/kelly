package com.mordor.kelly.service;

import java.util.prefs.Preferences;

/**
 * 用户 ID 持久化服务
 *
 * 本类负责在本地保存和读取服务器分配的用户 ID
 * 当用户首次连接时，服务器会分配一个 user_id
 * 本类将这个 ID 保存到本地，下次连接时可以恢复
 *
 * 存储方式：
 * - 使用 Java Preferences API
 * - 键格式：uid.{sha256(imCode + "\n" + username)}
 * - 不保存密码，只保存 user_id
 *
 * 存储位置：
 * - macOS: ~/Library/Preferences/com.apple.java.util.prefs.plist
 * - Windows: 注册表 HKCU\Software\JavaSoft\Prefs\...
 * - Linux: ~/.java/.userPrefs/...
 *
 * 注意：Preferences 键最长 80 字符，所以使用 SHA-256 哈希
 */
public final class SavedUserIdService {

    /**
     * Java Preferences 对象
     */
    private final Preferences prefs;

    /**
     * 默认构造方法
     * 使用 SavedUserIdService 类对应的 Preferences 节点
     */
    public SavedUserIdService() {
        this(Preferences.userNodeForPackage(SavedUserIdService.class));
    }

    /**
     * 带依赖注入的构造方法
     * 用于测试时注入模拟的 Preferences
     *
     * @param prefs Preferences 对象
     */
    public SavedUserIdService(Preferences prefs) {
        this.prefs = prefs;
    }

    /**
     * 获取保存的用户 ID
     *
     * @param imCode 聊天室标识码
     * @param username 用户名
     * @return 用户 ID，未找到返回 0
     */
    public int get(String imCode, String username) {
        return prefs.getInt(key(imCode, username), 0);
    }

    /**
     * 保存用户 ID
     *
     * @param imCode 聊天室标识码
     * @param username 用户名
     * @param userId 服务器分配的用户 ID
     */
    public void put(String imCode, String username, int userId) {
        prefs.putInt(key(imCode, username), userId);
    }

    /**
     * 生成 Preferences 键
     * 使用 imCode 和 username 的 SHA-256 哈希
     * 确保键长度不超过 80 字符限制
     *
     * @param imCode 聊天室标识码
     * @param username 用户名
     * @return Preferences 键
     */
    private static String key(String imCode, String username) {
        return "uid." + ChatHistory.sha256Hex(
                (imCode == null ? "" : imCode) + "\n" + (username == null ? "" : username));
    }
}
