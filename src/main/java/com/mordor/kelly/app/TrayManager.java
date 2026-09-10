package com.mordor.kelly.app;

import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.AWTException;
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
 * 图标路径固定为 /icons/tray.png(平台资源,不挂特性)。
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
        Image image = loadTrayImage("/icons/tray.png");
        if (image == null) {
            return;
        }
        Image alert = loadTrayImage("/icons/tray-alert.png");

        try {
            SystemTray systemTray = SystemTray.getSystemTray();
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
                icon.setImage(image);
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
