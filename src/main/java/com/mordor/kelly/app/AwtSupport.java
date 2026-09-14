package com.mordor.kelly.app;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;

/**
 * AWT 线程支持工具——解决 macOS 上 JavaFX 与 AWT 线程冲突问题。
 *
 * <p>核心问题：在 macOS 上，JavaFX 的 UI 线程就是 AppKit 的主线程。
 * AWT Toolkit 的首次初始化如果发生在这条线程上，AWT 会创建 AWT-EDT 线程
 * 并等待 AppKit 主线程就绪，而 AppKit 主线程正被 JavaFX 占用，
 * 导致两个线程互相等待（死锁），窗口直接卡死。
 *
 * <p>解决方案：
 * <ul>
 *   <li>{@link #preinit()}：在独立线程中预初始化 AWT Toolkit 和 GraphicsEnvironment，
 *       在 main 方法中调用，确保 JavaFX 启动前 AWT 已就绪</li>
 *   <li>{@link #run(Runnable)}：将 AWT 操作派发到 AWT-EDT（Event Dispatch Thread）线程，
 *       从当前线程"跳"出去再进入 AWT 线程，避免在错误的线程上执行 AWT 操作</li>
 * </ul>
 *
 * <p>为什么需要"跳"：
 * JavaFX 线程 ≠ AWT-EDT 线程。在 JavaFX 线程上直接调用 AWT API（如 SystemTray.add）
 * 会触发跨线程访问违规，导致不可预测的行为或死锁。通过创建新线程再 EventQueue.invokeLater
 * 实现线程跳跃，确保 AWT 操作在正确的线程上执行。
 */
final class AwtSupport {

    private AwtSupport() {}

    /**
     * 预初始化 AWT Toolkit。
     *
     * <p>在独立线程中调用 {@code Toolkit.getDefaultToolkit()} 和
     * {@code GraphicsEnvironment.getLocalGraphicsEnvironment()}，
     * 强制 AWT 在非 JavaFX 线程上完成首次初始化。
     *
     * <p>此方法最多等待 8 秒（通过 Thread.join），超时后继续执行——
     * AWT 初始化失败不应阻止应用启动。
     *
     * <p>调用时机：在 main 方法中，{@code launch()} 之前。
     */
    static void preinit() {
        Thread t = new Thread(() -> {
            // 触发 AWT Toolkit 首次初始化，这会创建 AWT-EDT 线程
            Toolkit.getDefaultToolkit();
            GraphicsEnvironment.getLocalGraphicsEnvironment();
        }, "kelly-awt-preinit");
        t.setDaemon(true);
        t.start();
        try {
            // 等待 AWT 初始化完成，最多等 8 秒
            t.join(8000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 将操作派发到 AWT 事件分派线程（AWT-EDT）执行。
     *
     * <p>通过创建新线程再 {@code EventQueue.invokeLater} 的方式实现"线程跳跃"，
     * 避免在当前线程（可能是 JavaFX 线程）上直接执行 AWT 操作。
     *
     * <p>如果 AWT 不可用（如远程桌面环境），静默忽略异常。
     *
     * @param action 要在 AWT-EDT 上执行的操作
     */
    static void run(Runnable action) {
        // 创建新线程跳出当前线程栈，再通过 EventQueue.invokeLater 进入 AWT-EDT
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
