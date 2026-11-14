package com.mordor.kelly.app;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 {@link BlinkTimer} 的 timeout settle 行为与重启行为。
 *
 * 用例使用小间隔参数（50ms / 500ms）让测试在亚秒级完成。
 */
class BlinkTimerTest {

    @Test
    void timeoutSettlesOnAlertPhase() throws Exception {
        var timer = new BlinkTimer(50, 500);
        var lastPhase = new AtomicBoolean();
        var tickCount = new AtomicInteger();

        timer.start(() -> {
            lastPhase.set(timer.isPhase());
            tickCount.incrementAndGet();
        });

        Thread.sleep(900); // > MAX_DURATION_MS (500)

        assertTrue(tickCount.get() > 0, "应该有 tick 发生过");
        assertTrue(lastPhase.get(), "最后一次回调的 phase 应为 true（settle 到 alert 状态），不是 normal");

        int settled = tickCount.get();
        Thread.sleep(300);
        assertEquals(settled, tickCount.get(), "timeout 之后不应再 tick");

        timer.stop();
    }

    @Test
    void restartAfterTimeoutStartsFresh() throws Exception {
        var timer = new BlinkTimer(50, 500);
        var phases = new ConcurrentLinkedQueue<Boolean>();
        var tickCount = new AtomicInteger();

        Runnable onTick = () -> {
            phases.add(timer.isPhase());
            tickCount.incrementAndGet();
        };

        timer.start(onTick);
        Thread.sleep(900); // 触发 timeout

        int countAfterTimeout = tickCount.get();
        assertTrue(countAfterTimeout > 0, "第一轮应有 tick");

        // 模拟 timeout 后又来新消息 → 重新 start()
        timer.start(onTick);
        Thread.sleep(300);

        assertTrue(tickCount.get() > countAfterTimeout,
                "restart 后应继续 tick；新消息不应被 on=true 早返回吞掉");

        timer.stop();
    }

    @Test
    void firstTickAfterStartIsAlertPhase() throws Exception {
        var timer = new BlinkTimer(50, 5_000);
        var firstPhase = new AtomicBoolean();

        timer.start(() -> {
            firstPhase.compareAndSet(false, timer.isPhase());
        });

        Thread.sleep(150); // 等 2~3 个 tick，确保 capture 到第一个

        assertTrue(firstPhase.get(), "start() 之后第一个 tick 的 phase 应为 true（alert）");

        timer.stop();
    }
}