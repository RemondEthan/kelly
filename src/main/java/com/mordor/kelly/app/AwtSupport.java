package com.mordor.kelly.app;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;

/**
 * macOS 上 JavaFX 线程就是 AppKit 主线程。AWT Toolkit / 托盘 / Taskbar
 * 的首次初始化若发生在这条线程上，会和 AWT-EDT 互相等待，窗口直接卡死。
 */
final class AwtSupport {

    private AwtSupport() {}

    static void preinit() {
        Thread t = new Thread(() -> {
            Toolkit.getDefaultToolkit();
            GraphicsEnvironment.getLocalGraphicsEnvironment();
        }, "kelly-awt-preinit");
        t.setDaemon(true);
        t.start();
        try {
            t.join(8000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void run(Runnable action) {
        Thread hop = new Thread(() -> {
            try {
                EventQueue.invokeLater(action);
            } catch (Throwable ignored) {
                // AWT 不可用时忽略
            }
        }, "kelly-awt");
        hop.setDaemon(true);
        hop.start();
    }
}
