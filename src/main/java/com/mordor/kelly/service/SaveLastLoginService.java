package com.mordor.kelly.service;

import com.mordor.kelly.app.Kelly;
import java.util.prefs.Preferences;

/**
 * 上次登录信息保存服务
 *
 * 本类负责持久化上次成功登录的配置（除密码外）
 * 使用 Java Preferences API 实现跨平台存储
 *
 * 存储位置：
 * - macOS: ~/Library/Preferences/com.apple.java.util.prefs.plist
 * - Windows: 注册表 HKCU\Software\JavaSoft\Prefs\...
 * - Linux: ~/.java/.userPrefs/...
 *
 * 设计目的：
 * - 将 UI 逻辑与存储逻辑分离
 * - 方便后续替换存储方式（如改用文件）
 * - 密码故意不持久化，安全考虑
 *
 * 默认值：
 * - 服务器IP: 127.0.0.1
 * - 服务器端口: 3000
 * - 对方名称: 等待对方
 */
public class SaveLastLoginService {

    /**
     * 服务器IP 键
     */
    private static final String KEY_SERVER_IP   = "serverIp";

    /**
     * 服务器端口 键
     */
    private static final String KEY_SERVER_PORT = "serverPort";

    /**
     * 聊天室标识码 键
     */
    private static final String KEY_IM_CODE     = "imCode";

    /**
     * 用户名 键
     */
    private static final String KEY_USERNAME    = "username";

    /**
     * 对方名称 键
     */
    private static final String KEY_PEER_NAME   = "peerName";

    /**
     * 头像路径 键
     */
    private static final String KEY_AVATAR_PATH = "avatarPath";

    /**
     * 默认服务器IP
     */
    private static final String DEFAULT_SERVER_IP   = "127.0.0.1";

    /**
     * 默认服务器端口
     */
    private static final String DEFAULT_SERVER_PORT = "3000";

    /**
     * 默认对方名称
     */
    private static final String DEFAULT_PEER_NAME   = "等待对方";

    /**
     * Java Preferences 对象
     */
    private final Preferences prefs;

    /**
     * 默认构造方法
     * 使用 Kelly 类对应的 Preferences 节点
     */
    public SaveLastLoginService() {
        this.prefs = Preferences.userNodeForPackage(Kelly.class);
    }

    /**
     * 获取上次登录的服务器IP
     * @return 服务器IP，未找到返回默认值 127.0.0.1
     */
    public String getServerIp() {
        return prefs.get(KEY_SERVER_IP, DEFAULT_SERVER_IP);
    }

    /**
     * 获取上次登录的服务器端口
     * @return 服务器端口，未找到返回默认值 3000
     */
    public String getServerPort() {
        return prefs.get(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
    }

    /**
     * 获取上次登录的聊天室标识码
     * @return 聊天室标识码，未找到返回空字符串
     */
    public String getImCode() {
        return prefs.get(KEY_IM_CODE, "");
    }

    /**
     * 获取上次登录的用户名
     * @return 用户名，未找到返回空字符串
     */
    public String getUsername() {
        return prefs.get(KEY_USERNAME, "");
    }

    /**
     * 获取上次登录的对方名称
     * @return 对方名称，未找到返回默认值 "等待对方"
     */
    public String getPeerName() {
        return prefs.get(KEY_PEER_NAME, DEFAULT_PEER_NAME);
    }

    /**
     * 获取上次登录的头像路径
     * @return 头像路径，未找到返回空字符串
     */
    public String getAvatarPath() {
        return prefs.get(KEY_AVATAR_PATH, "");
    }

    /**
     * 保存头像路径
     * 如果路径为空或空白，会删除已保存的路径
     *
     * @param avatarPath 头像路径
     */
    public void saveAvatarPath(String avatarPath) {
        if (avatarPath == null || avatarPath.isBlank()) {
            prefs.remove(KEY_AVATAR_PATH);
        } else {
            prefs.put(KEY_AVATAR_PATH, avatarPath);
        }
    }

    /**
     * 保存成功登录的配置
     * 注意：密码故意不持久化，安全考虑
     *
     * @param serverIp 服务器IP
     * @param serverPort 服务器端口
     * @param imCode 聊天室标识码
     * @param username 用户名
     * @param peerName 对方名称
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