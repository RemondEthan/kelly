package com.mordor.kelly.app;

import com.mordor.kelly.service.ImClient;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.Taskbar;

/**
 * 窗口不在前台时收到聊天，托盘 / 任务栏 / 标题栏图标红点闪烁；回到前台后停闪。
 */
final class UnreadAlert {

    private final Stage stage;
    private final TrayManager tray;
    private final java.awt.Image awtNormal;
    private final java.awt.Image awtAlert;
    private final BlinkTimer blinkTimer = new BlinkTimer();
    private boolean on;

    private UnreadAlert(Stage stage, TrayManager tray,
                        java.awt.Image awtNormal, java.awt.Image awtAlert) {
        this.stage = stage;
        this.tray = tray;
        this.awtNormal = awtNormal;
        this.awtAlert = awtAlert != null ? awtAlert : awtNormal;
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                clear();
            }
        });
        stage.showingProperty().addListener((obs, was, showing) -> {
            if (showing && stage.isFocused()) {
                clear();
            }
        });
        stage.iconifiedProperty().addListener((obs, was, iconified) -> {
            if (!iconified && stage.isShowing() && stage.isFocused()) {
                clear();
            }
        });
    }

    static UnreadAlert install(Stage stage, TrayManager tray) {
        return new UnreadAlert(
                stage,
                tray,
                AppIcons.awtImage("/icons/kelly.png"),
                AppIcons.awtImage("/icons/kelly-alert.png"));
    }

    void watch(ImClient client) {
        clear();
        client.addListener(event -> {
            if (event instanceof ImClient.Event.Chat) {
                Platform.runLater(this::onIncoming);
            }
        });
    }

    private void onIncoming() {
        if (!stage.isShowing() || stage.isIconified() || !stage.isFocused()) {
            set(true);
        }
    }

    void clear() {
        set(false);
    }

    private void set(boolean alert) {
        boolean wasOn = on;
        on = alert;
        if (alert) {
            // 不论 wasOn 都要重启 timer：30s 自动停止之后再来新消息，
            // 必须靠 start() 重置 startTime 把闪烁窗口续上，否则 on 一直
            // 是 true，新消息会被早返回吞掉、图标也回不去 alert。
            if (!wasOn) {
                applyIcons(true);
            }
            blinkTimer.start(this::onBlinkTick);
        } else if (wasOn) {
            blinkTimer.stop();
            applyIcons(false);
        }
    }

    private void onBlinkTick() {
        if (!on) {
            return;
        }
        applyIcons(blinkTimer.isPhase());
    }

    private void applyIcons(boolean alert) {
        tray.setIconImage(alert ? awtAlert : awtNormal);
        String fxPath = alert ? "/icons/kelly-alert.png" : "/icons/kelly.png";
        Platform.runLater(() -> AppIcons.applyStage(stage, fxPath));
        AppIcons.applyTaskbar(alert ? awtAlert : awtNormal);
        applyDockBadge(alert);
    }

    private static void applyDockBadge(boolean alert) {
        AwtSupport.run(() -> {
            try {
                if (!Taskbar.isTaskbarSupported()) {
                    return;
                }
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_BADGE_TEXT)
                        || taskbar.isSupported(Taskbar.Feature.ICON_BADGE_NUMBER)) {
                    taskbar.setIconBadge(alert ? "•" : null);
                }
            } catch (Exception ignored) {
                // 部分桌面环境不支持角标
            }
        });
    }
}
