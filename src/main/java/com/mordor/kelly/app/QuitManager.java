package com.mordor.kelly.app;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 应用退出管理器——保证程序可靠退出。
 *
 * <p>设计背景：JavaFX 应用在 macOS 上关闭窗口后，
 * AWT-EDT 线程仍持有系统托盘引用，调用 {@code System.exit(0)} 时
 * JVM 可能不会真正退出（AWT 线程不会被 shutdown hook 杀掉）。
 * 因此需要 {@link Runtime#halt(int)} 强制终止。
 *
 * <p>退出流程：
 * <ol>
 *   <li>{@link #quit()} 使用 {@link AtomicBoolean} CAS 操作防止重入（多次触发只执行一次）</li>
 *   <li>先执行 {@code beforeHalt} 回调（关闭网络连接、清理聊天面板等）</li>
 *   <li>在独立线程 {@code kelly-shutdown} 中异步执行 {@link #forceQuit()}</li>
 *   <li>{@code forceQuit} 先在 AWT 线程移除托盘图标，等待最多 400ms</li>
 *   <li>最后调用 {@code Runtime.halt(0)} 强制终止 JVM，确保进程一定退出</li>
 * </ol>
 *
 * <p>为什么用 {@code halt} 而不是 {@code exit}：
 * {@code exit} 会触发 shutdown hook，但如果 AWT-EDT 线程仍在运行
 * （持有 SystemTray 引用），JVM 可能等待该线程结束才真正退出，导致挂起。
 * {@code halt} 直接终止进程，不执行 shutdown hook，保证立即退出。
 */
public final class QuitManager {

    /** 防止 quit() 被多次调用的原子标志 */
    private final AtomicBoolean quitting = new AtomicBoolean();
    /** 托盘管理器，用于移除托盘图标 */
    private final TrayManager trayManager;
    /** 退出前的清理回调（关闭连接、清理 UI 等），在 forceQuit 之前执行 */
    private final Runnable beforeHalt;

    /**
     * 构造退出管理器。
     *
     * @param trayManager 托盘管理器，用于移除托盘图标；null 则跳过托盘清理
     * @param beforeHalt  退出前的清理回调（关闭网络连接等），null 则跳过
     */
    public QuitManager(TrayManager trayManager, Runnable beforeHalt) {
        this.trayManager = trayManager;
        this.beforeHalt = beforeHalt;
    }

    /**
     * 触发退出流程。
     *
     * <p>使用 AtomicBoolean.compareAndSet 保证线程安全的单次执行。
     * 首次调用时执行 beforeHalt 回调，然后启动独立线程执行 forceQuit。
     * 后续调用直接返回（CAS 失败）。
     */
    public void quit() {
        // CAS 确保只执行一次，多个线程同时调用时只有一个能成功
        if (!quitting.compareAndSet(false, true)) return;
        // 执行退出前清理（关闭连接、清理聊天面板等）
        if (beforeHalt != null) {
            try {
                beforeHalt.run();
            } catch (Throwable ignored) {
                // 退出路径，关连接失败也要继续杀进程
            }
        }
        // 在独立线程中异步执行强制退出，不阻塞当前线程
        Thread shutdown = new Thread(this::forceQuit, "kelly-shutdown");
        shutdown.start();
    }

    /**
     * 强制终止 JVM 进程。
     *
     * <p>步骤：
     * <ol>
     *   <li>从 TrayManager 获取 SystemTray 和 TrayIcon 引用</li>
     *   <li>在独立线程（kelly-tray-remove）中移除托盘图标</li>
     *   <li>等待移除完成，最多等 400ms（超时不再等待）</li>
     *   <li>调用 {@code Runtime.halt(0)} 强制终止 JVM</li>
     * </ol>
     */
    private void forceQuit() {
        SystemTray tr = trayManager == null ? null : trayManager.tray();
        TrayIcon ic = trayManager == null ? null : trayManager.icon();
        if (tr != null && ic != null) {
            // 在独立线程中移除托盘图标，避免 AWT-EDT 死锁
            Thread remover = new Thread(() -> {
                try {
                    tr.remove(ic);
                } catch (Throwable ignored) {
                    // 退出路径,移除失败也要继续杀进程
                }
            }, "kelly-tray-remove");
            remover.start();
            try {
                // 等待托盘图标移除完成，最多等 400ms
                remover.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        // 强制终止 JVM，不执行 shutdown hook，确保进程一定退出
        Runtime.getRuntime().halt(0);
    }
}
