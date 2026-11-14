package com.mordor.kelly.app;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwtSupportTest {

    @Test
    void runReturnsImmediatelyAndExecutesOffCaller() throws Exception {
        Thread caller = Thread.currentThread();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        long t0 = System.nanoTime();
        AwtSupport.run(() -> {
            ranOn.set(Thread.currentThread());
            done.countDown();
        });
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(ms < 200, "AWT hop must not block the caller, took " + ms + "ms");
        assertTrue(done.await(3, TimeUnit.SECONDS), "AWT task never ran");
        assertNotSame(caller, ranOn.get());
    }
}
