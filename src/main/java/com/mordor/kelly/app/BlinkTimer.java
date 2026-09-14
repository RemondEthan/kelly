package com.mordor.kelly.app;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 托盘/任务栏图标闪烁定时器——通过周期性翻转 phase 控制图标切换。
 *
 * <p>工作原理：
 * <ul>
 *   <li>使用 {@link ScheduledExecutorService} 实现精确的周期性调度</li>
 *   <li>每隔 {@link #intervalMs} 毫秒翻转一次 {@link #phase} 布尔值</li>
 *   <li>回调（如 UnreadAlert.onBlinkTick）根据 phase 值切换正常/提醒图标</li>
 *   <li>持续闪烁 {@link #maxDurationMs} 毫秒后自动停止</li>
 * </ul>
 *
 * <p>超时行为：
 * 超过最长闪烁时间后，自动将 phase 设为 true 并停止。
 * 这样回调会把图标留在「alert」状态——有未读消息时不应该停在正常图标。
 *
 * <p>默认参数：500ms 闪烁间隔，30 秒自动停止。
 */
final class BlinkTimer {

    /** 闪烁间隔（毫秒），默认 500ms */
    private final long intervalMs;
    /** 最大闪烁持续时间（毫秒），超过后自动停止，默认 30 秒 */
    private final long maxDurationMs;

    /** 单线程调度器，守护线程，线程名 kelly-blink */
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kelly-blink");
                t.setDaemon(true);
                return t;
            });

    /** 当前调度任务的句柄，用于取消 */
    private volatile ScheduledFuture<?> future;
    /** 闪烁相位：false=正常图标，true=提醒图标 */
    private volatile boolean phase;
    /** 闪烁开始时间（毫秒），用于计算是否超时 */
    private long startTime;

    /**
     * 创建默认参数的闪烁定时器（500ms 间隔，30 秒超时）。
     */
    BlinkTimer() {
        this(500, 30_000);
    }

    /**
     * 创建自定义参数的闪烁定时器。
     *
     * @param intervalMs    闪烁间隔（毫秒）
     * @param maxDurationMs 最大持续时间（毫秒），超时后自动停止
     */
    BlinkTimer(long intervalMs, long maxDurationMs) {
        this.intervalMs = intervalMs;
        this.maxDurationMs = maxDurationMs;
    }

    /**
     * 启动闪烁。
     *
     * <p>如果已在运行，先停止再重新开始（重置计时器和 phase）。
     * 每次翻转 phase 后调用 {@code onTick} 回调，由回调决定如何切换图标。
     *
     * @param onTick 每次 phase 翻转时的回调
     */
    void start(Runnable onTick) {
        stop(); // 先停止之前的任务
        phase = false; // 从正常图标开始
        startTime = System.currentTimeMillis();
        future = executor.scheduleAtFixedRate(() -> {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= maxDurationMs) {
                // 已超过最长闪烁时间：不再 toggle，直接把 phase 置为 true
                // 让回调 settle 到 alert 状态（有未读消息时不应停在正常图标），再停止
                phase = true;
                onTick.run();
                stop();
                return;
            }
            // 正常翻转 phase：false→true→false→...
            phase = !phase;
            onTick.run();
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止闪烁。
     *
     * <p>取消当前调度任务（不中断正在执行的任务），将 future 置空。
     */
    void stop() {
        ScheduledFuture<?> f = future;
        if (f != null) {
            f.cancel(false); // false 表示不中断正在执行的任务
            future = null;
        }
    }

    /**
     * 获取当前闪烁相位。
     *
     * @return true 表示提醒图标，false 表示正常图标
     */
    boolean isPhase() {
        return phase;
    }
}