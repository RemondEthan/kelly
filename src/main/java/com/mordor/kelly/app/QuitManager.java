package com.mordor.kelly.app;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 退出守卫 + 异步 forceQuit。
 *
 * quit() 用 AtomicBoolean 防止重入;真正退出在独立线程里移除托盘 + Runtime.halt,
 * 保证 JVM 一定死掉(避免 AWT 线程持有托盘导致进程不退出)。
 */
public final class QuitManager {

    private final AtomicBoolean quitting = new AtomicBoolean();
    private final TrayManager trayManager;
    private final Runnable beforeHalt;

    public QuitManager(TrayManager trayManager, Runnable beforeHalt) {
        this.trayManager = trayManager;
        this.beforeHalt = beforeHalt;
    }

    public void quit() {
        if (!quitting.compareAndSet(false, true)) return;
        if (beforeHalt != null) {
            try {
                beforeHalt.run();
            } catch (Throwable ignored) {
                // 退出路径，关连接失败也要继续杀进程
            }
        }
        Thread shutdown = new Thread(this::forceQuit, "kelly-shutdown");
        shutdown.start();
    }

    private void forceQuit() {
        SystemTray tr = trayManager == null ? null : trayManager.tray();
        TrayIcon ic = trayManager == null ? null : trayManager.icon();
        if (tr != null && ic != null) {
            Thread remover = new Thread(() -> {
                try {
                    tr.remove(ic);
                } catch (Throwable ignored) {
                    // 退出路径,移除失败也要继续杀进程
                }
            }, "kelly-tray-remove");
            remover.start();
            try {
                remover.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime.getRuntime().halt(0);
    }
}
