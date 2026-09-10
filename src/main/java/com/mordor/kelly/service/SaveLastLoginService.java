package com.mordor.kelly.service;

import com.mordor.kelly.app.Kelly;
import java.util.prefs.Preferences;

/**
 * 持久化上次成功登录的配置（除密码外）。
 *
 * 用 Java Preferences API：跨平台、零依赖。
 *   - macOS: ~/Library/Preferences/com.apple.java.util.prefs.plist
 *   - Windows: 注册表 HKCU\Software\JavaSoft\Prefs\...
 *   - Linux: ~/.java/.userPrefs/...
 *
 * 之所以独立成类，是为了在 LoginPane 里只保留 UI 逻辑，方便后续替换存储（比如改用文件）。
 */
public class SaveLastLoginService {

    private static final String KEY_SERVER_IP   = "serverIp";
    private static final String KEY_SERVER_PORT = "serverPort";
    private static final String KEY_IM_CODE     = "imCode";
    private static final String KEY_USERNAME    = "username";
    private static final String KEY_PEER_NAME   = "peerName";
    private static final String KEY_AVATAR_PATH = "avatarPath";

    // 默认值：与原 LoginPane prefill 保持一致
    private static final String DEFAULT_SERVER_IP   = "127.0.0.1";
    private static final String DEFAULT_SERVER_PORT = "3000";
    private static final String DEFAULT_PEER_NAME   = "等待对方";

    private final Preferences prefs;

    public SaveLastLoginService() {
        this.prefs = Preferences.userNodeForPackage(Kelly.class);
    }

    public String getServerIp() {
        return prefs.get(KEY_SERVER_IP, DEFAULT_SERVER_IP);
    }

    public String getServerPort() {
        return prefs.get(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
    }

    public String getImCode() {
        return prefs.get(KEY_IM_CODE, "");
    }

    public String getUsername() {
        return prefs.get(KEY_USERNAME, "");
    }

    public String getPeerName() {
        return prefs.get(KEY_PEER_NAME, DEFAULT_PEER_NAME);
    }

    public String getAvatarPath() {
        return prefs.get(KEY_AVATAR_PATH, "");
    }

    public void saveAvatarPath(String avatarPath) {
        if (avatarPath == null || avatarPath.isBlank()) {
            prefs.remove(KEY_AVATAR_PATH);
        } else {
            prefs.put(KEY_AVATAR_PATH, avatarPath);
        }
    }

    /**
     * 保存成功登录的配置。
     *
     * 密码故意不持久化——demo 模式预填可见，但真实场景不能落盘。
     */
    public void save(String serverIp, String serverPort, String imCode,
                     String username, String peerName) {
        prefs.put(KEY_SERVER_IP, serverIp);
        prefs.put(KEY_SERVER_PORT, serverPort);
        prefs.put(KEY_IM_CODE, imCode);
        prefs.put(KEY_USERNAME, username);
        prefs.put(KEY_PEER_NAME, peerName);
    }
}