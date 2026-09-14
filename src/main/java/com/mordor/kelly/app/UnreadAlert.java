package com.mordor.kelly.app;

import com.mordor.kelly.service.ImClient;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.awt.Taskbar;
import java.util.List;

/**
 * 未读消息提醒管理器——窗口不在前台时触发图标闪烁和角标。
 *
 * <p>当收到新消息但窗口不在前台（未显示、最小化或未聚焦）时，
 * 通过多种方式提醒用户：
 * <ul>
 *   <li><b>系统托盘图标</b>：正常图标和提醒图标交替闪烁</li>
 *   <li><b>窗口标题栏图标</b>：切换为提醒图标</li>
 *   <li><b>任务栏按钮</b>：切换为提醒图标</li>
 *   <li><b>macOS Dock 角标</b>：显示 "•" 标记</li>
 *   <li><b>Windows 任务栏闪烁</b>：通过原生 FlashWindowEx API 高亮闪烁</li>
 * </ul>
 *
 * <p>闪烁策略：
 * <ul>
 *   <li>使用 {@link BlinkTimer} 实现周期性闪烁（500ms 间隔，30 秒超时）</li>
 *   <li>Windows 上使用原生 {@link WinFlash} 进行任务栏闪烁，BlinkTimer 只负责托盘图标</li>
 *   <li>超时后图标停留在 alert 状态（有未读消息）</li>
 *   <li>窗口回到前台时自动停止闪烁，恢复所有图标</li>
 * </ul>
 *
 * <p>窗口状态监听：
 * 焦点属性、显示属性、最小化属性的监听器会在窗口回到前台时调用 clear()，
 * 停止闪烁并恢复所有图标到正常状态。
 */
final class UnreadAlert {

    /** 主窗口 Stage */
    private final Stage stage;
    /** 系统托盘管理器 */
    private final TrayManager tray;
    /** AWT 格式的正常图标 */
    private final java.awt.Image awtNormal;
    /** AWT 格式的提醒图标（有未读消息） */
    private final java.awt.Image awtAlert;
    /** JavaFX 格式的正常图标列表（多尺寸） */
    private final List<Image> fxNormal;
    /** JavaFX 格式的提醒图标列表（多尺寸） */
    private final List<Image> fxAlert;
    /** Windows 原生窗口句柄（HWND），用于 FlashWindowEx；0 表示获取失败 */
    private final long hwnd;
    /** 闪烁定时器，控制图标周期性切换 */
    private final BlinkTimer blinkTimer = new BlinkTimer();
    /** 是否正在闪烁（true=提醒状态，false=正常状态） */
    private boolean on;

    /**
     * 构造未读消息提醒器。
     *
     * <p>注册三个窗口状态监听器，在窗口回到前台时自动停止闪烁：
     * <ul>
     *   <li>focusedProperty：窗口获得焦点</li>
     *   <li>showingProperty：窗口从隐藏变为显示</li>
     *   <li>iconifiedProperty：窗口从最小化恢复</li>
     * </ul>
     */
    private UnreadAlert(Stage stage, TrayManager tray,
                        java.awt.Image awtNormal, java.awt.Image awtAlert,
                        List<Image> fxNormal, List<Image> fxAlert, long hwnd) {
        this.stage = stage;
        this.tray = tray;
        this.awtNormal = awtNormal;
        this.awtAlert = awtAlert != null ? awtAlert : awtNormal;
        this.fxNormal = fxNormal;
        this.fxAlert = fxAlert != null ? fxAlert : fxNormal;
        this.hwnd = hwnd;
        // 窗口获得焦点时：停止闪烁
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                clear();
            }
        });
        // 窗口从隐藏变为显示且有焦点时：停止闪烁
        stage.showingProperty().addListener((obs, was, showing) -> {
            if (showing && stage.isFocused()) {
                clear();
            }
        });
        // 窗口从最小化恢复且有焦点时：停止闪烁
        stage.iconifiedProperty().addListener((obs, was, iconified) -> {
            if (!iconified && stage.isShowing() && stage.isFocused()) {
                clear();
            }
        });
    }

    /**
     * 安装未读消息提醒器。
     *
     * <p>预加载正常和提醒状态的图标（AWT 和 JavaFX 两套），
     * 获取 Windows 原生窗口句柄（HWND）。
     *
     * @param stage 主窗口 Stage
     * @param tray  托盘管理器
     * @return 未读消息提醒器实例
     */
    static UnreadAlert install(Stage stage, TrayManager tray) {
        // 获取 Windows 原生窗口句柄，用于 FlashWindowEx；非 Windows 或失败时为 0
        long hwnd = WinFlash.hwndOf(stage);
        return new UnreadAlert(
                stage,
                tray,
                AppIcons.awtImage("/icons/kelly.png"),
                AppIcons.awtImage("/icons/kelly-alert.png"),
                AppIcons.fxIcons("/icons/kelly.png"),
                AppIcons.fxIcons("/icons/kelly-alert.png"),
                hwnd);
    }

    /**
     * 开始监听 IM 客户端的未读消息事件。
     *
     * <p>清空之前的提醒状态，注册消息监听器。
     * 当收到聊天消息（Chat 或 Image 类型）时，如果窗口不在前台，
     * 则触发图标闪烁。
     *
     * @param client IM 客户端连接
     */
    void watch(ImClient client) {
        clear();
        client.addListener(event -> {
            // 只处理聊天消息和图片消息，忽略其他事件（如连接状态变更）
            if (event instanceof ImClient.Event.Chat || event instanceof ImClient.Event.Image) {
                Platform.runLater(this::onIncoming);
            }
        });
    }

    /**
     * 收到新消息时的处理逻辑。
     *
     * <p>仅在窗口不在前台时触发提醒：
     * 窗口未显示、已最小化、或未获得焦点。
     */
    private void onIncoming() {
        if (!stage.isShowing() || stage.isIconified() || !stage.isFocused()) {
            set(true);
        }
    }

    /**
     * 停止闪烁，恢复所有图标到正常状态。
     * 窗口回到前台时自动调用。
     */
    void clear() {
        set(false);
    }

    /**
     * 设置提醒状态。
     *
     * <p>alert=true 时：启动闪烁，切换图标到提醒状态。
     * alert=false 时：停止闪烁，恢复图标到正常状态。
     *
     * <p>关键逻辑：
     * <ul>
     *   <li>alert=true 时无论 wasOn 如何都要重启 timer——30 秒超时停止后
     *       再来新消息必须重置计时器，否则新消息会被吞掉</li>
     *   <li>Windows 上使用原生 FlashWindowEx 闪烁任务栏，BlinkTimer 只负责托盘图标</li>
     *   <li>非 Windows 平台使用 BlinkTimer 控制所有图标闪烁</li>
     * </ul>
     *
     * @param alert true 启动提醒，false 停止提醒
     */
    private void set(boolean alert) {
        boolean wasOn = on;
        on = alert;
        if (alert) {
            // 不论之前是否在闪烁，都要重启 timer：
            // 30s 自动停止之后再来新消息，必须靠 start() 重置 startTime
            // 把闪烁窗口续上，否则 on 一直是 true，新消息会被早返回吞掉
            if (!wasOn) {
                // 首次进入提醒状态，立即切换所有图标到提醒态
                applyIcons(true);
            }
            // Windows 上把任务栏按钮交给 user32 FlashWindowEx 高亮闪烁
            // 反射拿到 HWND 失败（hwnd=0）则继续走 BlinkTimer 软件模拟
            if (hwnd != 0) {
                WinFlash.start(hwnd);
            }
            // 启动闪烁定时器，每次翻转时回调 onBlinkTick
            blinkTimer.start(this::onBlinkTick);
        } else if (wasOn) {
            // 从提醒状态恢复到正常状态
            blinkTimer.stop();
            applyIcons(false);
            if (hwnd != 0) {
                WinFlash.stop(hwnd);
            }
        }
    }

    /**
     * 闪烁定时器的回调——每次 phase 翻转时切换图标。
     *
     * <p>Windows 上任务栏按钮由原生 FlashWindowEx 闪烁，
     * BlinkTimer 只需要切换托盘图标即可，避免双重闪烁造成视觉混乱。
     * 非 Windows 平台上同时切换托盘图标、窗口图标和任务栏图标。
     */
    private void onBlinkTick() {
        if (!on) {
            return; // 已经停止闪烁，忽略迟到的回调
        }
        if (hwnd != 0) {
            // Windows：任务栏由原生闪烁，只翻托盘图标
            tray.setIconImage(blinkTimer.isPhase() ? awtAlert : awtNormal);
        } else {
            // macOS/Linux：翻转所有图标
            applyIcons(blinkTimer.isPhase());
        }
    }

    /**
     * 将所有图标切换为提醒或正常状态。
     *
     * <p>同时更新：
     * <ul>
     *   <li>系统托盘图标（通过 TrayManager）</li>
     *   <li>窗口标题栏图标（通过 Stage.getIcons）</li>
     *   <li>任务栏按钮图标（通过 Taskbar API）</li>
     *   <li>macOS Dock 角标（通过 Taskbar.setIconBadge）</li>
     * </ul>
     *
     * @param alert true 切换到提醒图标，false 恢复正常图标
     */
    private void applyIcons(boolean alert) {
        // 切换系统托盘图标
        tray.setIconImage(alert ? awtAlert : awtNormal);
        // 切换窗口标题栏图标（在 FX 线程执行）
        Platform.runLater(() -> stage.getIcons().setAll(alert ? fxAlert : fxNormal));
        // 切换任务栏按钮图标
        AppIcons.applyTaskbar(alert ? awtAlert : awtNormal);
        // 切换 macOS Dock 角标
        applyDockBadge(alert);
    }

    /**
     * 设置 macOS Dock 角标（"•" 标记）。
     *
     * <p>通过 AWT Taskbar API 设置角标文本。
     * alert=true 时显示 "•"，alert=null 时清除角标。
     * 部分桌面环境不支持角标功能，静默忽略。
     *
     * @param alert true 显示角标，false 清除角标
     */
    private static void applyDockBadge(boolean alert) {
        AwtSupport.run(() -> {
            try {
                if (!Taskbar.isTaskbarSupported()) {
                    return;
                }
                Taskbar taskbar = Taskbar.getTaskbar();
                // 尝试文本角标或数字角标，不同平台支持不同
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
