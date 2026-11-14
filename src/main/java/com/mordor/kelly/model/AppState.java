package com.mordor.kelly.model;

import com.mordor.kelly.service.ImClient;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.image.Image;

/**
 * 应用状态持有者。
 * 存储当前应用的各种状态信息，包括用户名、客户端实例、对方显示名、在线状态和头像。
 * 使用 JavaFX 属性（Property）实现数据绑定，方便与 UI 组件绑定。
 * 
 * 主要功能：
 * - 保存当前登录用户名
 * - 持有 IM 客户端实例（可为 null 表示脱机模式）
 * - 管理对方的显示名、在线状态和头像
 * - 提供脱机模式的工厂方法
 */
public final class AppState {

    /**
     * 当前登录用户名。
     * 不可变字段，在构造时设置。
     */
    private final String username;

    /**
     * IM 客户端实例。
     * 可为 null，表示处于脱机模式。
     * 不可变字段，在构造时设置。
     */
    private final ImClient client;

    /**
     * 是否处于脱机模式。
     * 不可变字段，在构造时设置。
     */
    private final boolean offline;

    /**
     * 对方显示名属性。
     * JavaFX StringProperty 类型，可以绑定到 UI 组件实现自动更新。
     * 默认值为 "等待对方"。
     */
    private final StringProperty peerDisplay = new SimpleStringProperty("等待对方");

    /**
     * 对方在线状态属性。
     * JavaFX BooleanProperty 类型，可以绑定到 UI 组件实现自动更新。
     * 默认值为 true（在线）。
     */
    private final BooleanProperty online = new SimpleBooleanProperty(true);

    /**
     * 对方头像属性。
     * JavaFX ObjectProperty 类型，可以绑定到 UI 组件实现自动更新。
     * 默认值为 null（无头像）。
     */
    private final ObjectProperty<Image> avatar = new SimpleObjectProperty<>();

    /**
     * 构造函数，创建带有用户名和客户端的 AppState 实例。
     * 头像默认为 null，脱机模式默认为 false。
     * 
     * @param username 当前登录用户名
     * @param client IM 客户端实例
     */
    public AppState(String username, ImClient client) {
        this(username, client, null);
    }

    /**
     * 构造函数，创建带有用户名、客户端和头像的 AppState 实例。
     * 脱机模式默认为 false。
     * 
     * @param username 当前登录用户名
     * @param client IM 客户端实例
     * @param avatar 对方头像
     */
    public AppState(String username, ImClient client, Image avatar) {
        this(username, client, avatar, false);
    }

    /**
     * 完整构造函数，创建 AppState 实例。
     * 如果脱机模式为 true，则自动设置对方在线状态为 false，显示名为 "脱机"。
     * 
     * @param username 当前登录用户名
     * @param client IM 客户端实例（可为 null）
     * @param avatar 对方头像
     * @param offline 是否处于脱机模式
     */
    public AppState(String username, ImClient client, Image avatar, boolean offline) {
        this.username = username;
        this.client = client;
        this.avatar.set(avatar);
        this.offline = offline;
        if (offline) {
            this.online.set(false);
            this.peerDisplay.set("脱机");
        }
    }

    /**
     * 工厂方法，创建脱机状态的 AppState 实例。
     * 脱机模式下，客户端为 null，在线状态为 false，显示名为 "脱机"。
     * 
     * @param username 当前登录用户名
     * @param avatar 对方头像
     * @return 脱机状态的 AppState 实例
     */
    public static AppState offline(String username, Image avatar) {
        return new AppState(username, null, avatar, true);
    }

    /**
     * 获取当前登录用户名。
     * 
     * @return 用户名
     */
    public String username() {
        return username;
    }

    /**
     * 获取对方显示名。
     * 
     * @return 对方显示名
     */
    public String peerName() {
        return peerDisplay.get();
    }

    /**
     * 获取 IM 客户端实例。
     * 
     * @return IM 客户端实例（可能为 null）
     */
    public ImClient client() {
        return client;
    }

    /**
     * 获取对方头像。
     * 
     * @return 对方头像
     */
    public Image avatar() {
        return avatar.get();
    }

    /**
     * 获取对方头像属性。
     * 用于 JavaFX 数据绑定。
     * 
     * @return 头像属性对象
     */
    public ObjectProperty<Image> avatarProperty() {
        return avatar;
    }

    /**
     * 设置对方头像。
     * 
     * @param value 新的头像值
     */
    public void setAvatar(Image value) {
        avatar.set(value);
    }

    /**
     * 获取对方显示名属性。
     * 用于 JavaFX 数据绑定。
     * 
     * @return 显示名属性对象
     */
    public StringProperty peerDisplayProperty() {
        return peerDisplay;
    }

    /**
     * 获取对方在线状态属性。
     * 用于 JavaFX 数据绑定。
     * 
     * @return 在线状态属性对象
     */
    public BooleanProperty onlineProperty() {
        return online;
    }

    /**
     * 设置对方显示名。
     * 
     * @param value 新的显示名
     */
    public void setPeerDisplay(String value) {
        peerDisplay.set(value);
    }

    /**
     * 设置对方在线状态。
     * 
     * @param value 新的在线状态
     */
    public void setOnline(boolean value) {
        online.set(value);
    }

    /**
     * 检查是否处于脱机模式。
     * 
     * @return 如果处于脱机模式返回 true，否则返回 false
     */
    public boolean offline() {
        return offline;
    }
}