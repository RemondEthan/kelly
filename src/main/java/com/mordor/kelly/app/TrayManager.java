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
 * 系统托盘封装。
 *
 * install(Stage) 立即返回占位对象，真正的 SystemTray.add 在 AWT 线程完成；
 * 系统不支持托盘或加载失败时,tray() / icon() 返回 null,QuitManager 据此跳过清理。
 *
 * 图标与窗口共用 /icons/kelly.png；提醒态用 /icons/kelly-alert.png。
 * Windows / Debian 托盘尺寸不同，加载后按 SystemTray.getTrayIconSize() 缩放。
 */
public final class TrayManager {

    private volatile SystemTray tray;
    private volatile TrayIcon icon;
    private volatile Image normalImage;
    private volatile Image alertImage;
    private volatile Runnable onQuit = () -> {};

    public static TrayManager install(Stage stage) {
        TrayManager tm = new TrayManager();
        AwtSupport.run(() -> tm.attach(stage));
        return tm;
    }

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
            Dimension size = systemTray.getTrayIconSize();
            Image image = AppIcons.fitAwt(loaded, size.width, size.height);
            Image alert = AppIcons.fitAwt(loadTrayImage("/icons/kelly-alert.png"), size.width, size.height);
            TrayIcon trayIcon = new TrayIcon(image, "Kelly");
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> FxStageSupport.show(stage));
            systemTray.add(trayIcon);
            trayIcon.setPopupMenu(buildMenu(stage));
            this.normalImage = image;
            this.alertImage = alert != null ? alert : image;
            this.icon = trayIcon;
            this.tray = systemTray;
        } catch (AWTException e) {
            System.err.println("[Tray] 无法添加托盘图标: " + e.getMessage());
        }
    }

    public SystemTray tray() { return tray; }
    public TrayIcon icon() { return icon; }

    public void setOnQuit(Runnable onQuit) {
        this.onQuit = onQuit == null ? () -> {} : onQuit;
    }

    void setAlert(boolean alert) {
        AwtSupport.run(() -> {
            if (icon == null || normalImage == null) {
                return;
            }
            icon.setImage(alert && alertImage != null ? alertImage : normalImage);
        });
    }

    void setIconImage(java.awt.Image image) {
        AwtSupport.run(() -> {
            if (icon != null && image != null) {
                Dimension size = tray != null ? tray.getTrayIconSize() : new Dimension(16, 16);
                icon.setImage(AppIcons.fitAwt(image, size.width, size.height));
            }
        });
    }

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

    private PopupMenu buildMenu(Stage stage) {
        // 中文 Windows 上 AWT 走 ANSI native API (AppendMenuA)，
        // 依赖 Charset.defaultCharset() 与系统 ANSI 代码页一致才能正常显示。
        // pom.xml win profile 已加 -Dfile.encoding=GBK 兜底中文区域。
        // 英文 / 日文等其它 ANSI 代码页区域仍会乱码；根治需用 JNA 走 AppendMenuW。
        PopupMenu menu = new PopupMenu();

        MenuItem openItem = new MenuItem("打开 Kelly");
        openItem.addActionListener(e -> FxStageSupport.show(stage));

        MenuItem hideItem = new MenuItem("隐藏窗口");
        hideItem.addActionListener(e -> FxStageSupport.hide(stage));

        MenuItem quitItem = new MenuItem("退出");
        quitItem.addActionListener(e -> onQuit.run());

        menu.add(openItem);
        menu.add(hideItem);
        menu.addSeparator();
        menu.add(quitItem);
        return menu;
    }

}
