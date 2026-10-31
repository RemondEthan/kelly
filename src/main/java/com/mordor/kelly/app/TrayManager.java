package com.mordor.kelly.app;

import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.IOException;
import java.io.InputStream;

/**
 * 系统托盘管理器——封装 AWT SystemTray 操作。
 *
 * <p>工作原理：
 * <ul>
 *   <li>双后端：Linux 优先走 {@link SniTray}（StatusNotifierItem 协议，
 *       GNOME/KDE 的 AppIndicator 面板只认 SNI，AWT XEmbed 图标无法嵌入）；
 *       SNI 不可用或 Windows/macOS 走 AWT {@link SystemTray}/{@link TrayIcon}</li>
 *   <li>{@link #install(Stage)} 是工厂方法，立即返回占位对象，真正的托盘注册
 *       异步完成（SNI 在调用线程，AWT 通过 {@link AwtSupport#run}）</li>
 *   <li>如果系统不支持托盘或加载失败，{@link #tray()} / {@link #icon()} 返回 null，
 *       上层调用者（如 QuitManager）据此跳过清理</li>
 * </ul>
 *
 * <p>图标管理：
 * <ul>
 *   <li>正常态：/icons/kelly.png；提醒态（有未读消息）：/icons/kelly-alert.png</li>
 *   <li>加载后按 {@link SystemTray#getTrayIconSize()} 缩放，
 *       因为 Windows 托盘约 16px，Debian 约 24px，原图 256px 直接塞进去会模糊</li>
 * </ul>
 *
 * <p>线程安全：所有 AWT 操作（图标切换、菜单构建）通过 {@link AwtSupport#run}
 * 派发到 AWT-EDT 线程执行，避免跨线程访问 AWT 组件。
 */
public final class TrayManager {

    /** 系统托盘实例，volatile 保证多线程可见性 */
    private volatile SystemTray tray;
    /** 托盘图标实例 */
    private volatile TrayIcon icon;
    /** 托盘右键菜单"退出"按钮的回调 */
    private volatile Runnable onQuit = () -> {};

    /** Linux SNI/AppIndicator 后端；AWT 后端时为 null */
    private volatile SniTray sni;

    /**
     * 安装系统托盘。
     *
     * <p>后端选择：Linux 上优先走 StatusNotifierItem（GNOME/KDE 的
     * AppIndicator 扩展只认 SNI，AWT XEmbed 图标在其面板上无法显示）；
     * 无会话总线或无 watcher 时回退 AWT。Windows/macOS 走 AWT。
     *
     * <p>此方法立即返回，实际的托盘注册异步完成（SNI 在调用线程，
     * AWT 在 AWT 线程）。调用方可安全地在 JavaFX 线程中调用而不会阻塞 UI。
     *
     * @param stage 主窗口 Stage，双击托盘图标时恢复窗口
     * @return 托盘管理器实例，即使安装失败也会返回（tray/icon 为 null）
     */
    public static TrayManager install(Stage stage) {
        TrayManager tm = new TrayManager();
        tm.sni = SniTray.install(stage, () -> tm.onQuit);
        if (tm.sni == null) {
            AwtSupport.run(() -> tm.attach(stage));
        }
        return tm;
    }

    /**
     * 在 AWT 线程中真正执行托盘注册。
     *
     * <p>步骤：
     * <ol>
     *   <li>检查系统是否支持托盘（远程桌面等环境可能不支持）</li>
     *   <li>加载图标资源，缩放至系统托盘要求的尺寸</li>
     *   <li>创建 TrayIcon，设置双击监听（恢复窗口）和右键菜单</li>
     *   <li>添加到系统托盘</li>
     * </ol>
     *
     * @param stage 主窗口 Stage
     */
    private void attach(Stage stage) {
        if (!SystemTray.isSupported()) {
            System.out.println("[Tray] 当前系统不支持托盘图标,跳过");
            return;
        }
        Image loaded = loadTrayImage("/icons/kelly.png");
        if (loaded == null) {
            return;
        }

        try {
            SystemTray systemTray = SystemTray.getSystemTray();
            // 获取系统托盘图标尺寸（Windows 约 16x16，Linux 约 24x24）
            Dimension size = systemTray.getTrayIconSize();
            // 将原始图标缩放至托盘尺寸
            Image image = AppIcons.fitAwt(loaded, size.width, size.height);
            Image alert = AppIcons.fitAwt(loadTrayImage("/icons/kelly-alert.png"), size.width, size.height);
            // 创建托盘图标，imageAutoSize 自动处理不同 DPI
            TrayIcon trayIcon = new TrayIcon(image, "Kelly");
            trayIcon.setImageAutoSize(true);
            // 双击托盘图标恢复窗口
            trayIcon.addActionListener(e -> FxStageSupport.show(stage));
            systemTray.add(trayIcon);
            // 设置右键上下文菜单
            trayIcon.setPopupMenu(buildMenu(stage));
            this.icon = trayIcon;
            this.tray = systemTray;
        } catch (AWTException e) {
            System.err.println("[Tray] 无法添加托盘图标: " + e.getMessage());
        }
    }

    /**
     * 获取系统托盘实例。
     *
     * @return SystemTray 实例，系统不支持时返回 null
     */
    public SystemTray tray() { return tray; }

    /**
     * 获取托盘图标实例。
     *
     * @return TrayIcon 实例，安装失败时返回 null
     */
    public TrayIcon icon() { return icon; }

    /**
     * 设置退出回调，托盘右键菜单点击"退出"时触发。
     *
     * @param onQuit 退出回调，null 会被替换为空操作
     */
    public void setOnQuit(Runnable onQuit) {
        this.onQuit = onQuit == null ? () -> {} : onQuit;
    }

    /**
     * 设置托盘图标图像（用于闪烁动画，交替切换正常/提醒图标）。
     *
     * <p>SNI 后端按 Image 身份缓存编码像素并推送 PropertiesChanged；
     * AWT 后端自动将图像缩放至系统托盘要求的尺寸。
     *
     * @param image 新的图标图像，null 时忽略
     */
    void setIconImage(java.awt.Image image) {
        SniTray sniBackend = sni;
        if (sniBackend != null) {
            if (image != null) {
                sniBackend.setIconImage(image);
            }
            return;
        }
        AwtSupport.run(() -> {
            if (icon != null && image != null) {
                Dimension size = tray != null ? tray.getTrayIconSize() : new Dimension(16, 16);
                icon.setImage(AppIcons.fitAwt(image, size.width, size.height));
            }
        });
    }

    /**
     * 从 classpath 加载托盘图标图像。
     *
     * @param path 图标资源路径（如 "/icons/kelly.png"）
     * @return 加载成功返回 Image，失败返回 null
     */
    private static Image loadTrayImage(String path) {
        try (InputStream is = TrayManager.class.getResourceAsStream(path)) {
            if (is == null) {
                System.err.println("[Tray] 找不到 " + path);
                return null;
            }
            return ImageIO.read(is);
        } catch (IOException e) {
            System.err.println("[Tray] 加载图标失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 构建托盘右键上下文菜单。
     *
     * <p>菜单项：
     * <ul>
     *   <li>"打开 Kelly" — 恢复隐藏的窗口</li>
     *   <li>"隐藏窗口" — 将窗口最小化到托盘</li>
     *   <li>"退出" — 触发退出流程（QuitManager）</li>
     * </ul>
     *
     * <p>注意：中文 Windows 上 AWT 使用 ANSI native API (AppendMenuA)，
     * 依赖系统 ANSI 代码页（GBK）才能正常显示中文。
     * pom.xml 的 win profile 已加 -Dfile.encoding=GBK 兜底。
     * 英文/日文等其他代码页区域仍会乱码，根治需用 JNA 走 AppendMenuW。
     *
     * @param stage 主窗口 Stage
     * @return 包含菜单项的 PopupMenu
     */
    private PopupMenu buildMenu(Stage stage) {
        PopupMenu menu = new PopupMenu();

        // "打开 Kelly"：恢复窗口到前台
        MenuItem openItem = new MenuItem("打开 Kelly");
        openItem.addActionListener(e -> FxStageSupport.show(stage));

        // "隐藏窗口"：将窗口最小化到托盘
        MenuItem hideItem = new MenuItem("隐藏窗口");
        hideItem.addActionListener(e -> FxStageSupport.hide(stage));

        // "退出"：触发完整的退出流程
        MenuItem quitItem = new MenuItem("退出");
        quitItem.addActionListener(e -> onQuit.run());

        menu.add(openItem);
        menu.add(hideItem);
        menu.addSeparator(); // 分隔线
        menu.add(quitItem);
        return menu;
    }

}
