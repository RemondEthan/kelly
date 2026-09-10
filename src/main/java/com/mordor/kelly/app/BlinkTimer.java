package com.mordor.kelly.app;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 托盘 / 任务栏图标闪烁定时器。
 * <p>
 * 每 {@link #intervalMs} 翻转一次 phase，由回调负责切换图标。
 * 持续到 {@link #maxDurationMs} 后自动停止；停止时 phase settle 到 {@code true}，
 * 让回调把图标留在「alert」状态——{@code on=true} 时不应停在 normal 图标。
 */
final class BlinkTimer {

    private final long intervalMs;
    private final long maxDurationMs;

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "kelly-blink");
                t.setDaemon(true);
                return t;
            });

    private volatile ScheduledFuture<?> future;
    private volatile boolean phase;
    private long startTime;

    BlinkTimer() {
        this(500, 30_000);
    }

    BlinkTimer(long intervalMs, long maxDurationMs) {
        this.intervalMs = intervalMs;
        this.maxDurationMs = maxDurationMs;
    }

    void start(Runnable onTick) {
        stop();
        phase = false;
        startTime = System.currentTimeMillis();
        future = executor.scheduleAtFixedRate(() -> {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= maxDurationMs) {
                // 已超过最长闪烁时间：不再 toggle，直接把 phase 置为 true 让回调 settle 到 alert 状态，再停止。
                phase = true;
                onTick.run();
                stop();
                return;
            }
            phase = !phase;
            onTick.run();
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    void stop() {
        ScheduledFuture<?> f = future;
        if (f != null) {
            f.cancel(false);
            future = null;
        }
    }

    boolean isPhase() {
        return phase;
    }
}