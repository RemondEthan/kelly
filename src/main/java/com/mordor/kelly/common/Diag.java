package com.mordor.kelly.common;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 诊断日志：默认只写 WARN / ERROR。调试轨迹用 {@link #log}，需 {@code -Dkelly.debug=true} 才输出。
 * macOS: ~/Library/Logs/kelly.log
 */
public final class Diag {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private static final Path LOG_FILE = resolveLogFile();
    private static final Object LOCK = new Object();
    private static final AtomicBoolean WATCHDOG = new AtomicBoolean();
    private static final boolean DEBUG = Boolean.getBoolean("kelly.debug");
    private static volatile long lastThreadDumpNanos;

    private Diag() {}

    public static Path logFile() {
        return LOG_FILE;
    }

    public static void log(String tag, String format, Object... args) {
        if (DEBUG) {
            write("DEBUG", tag, format, args);
        }
    }

    public static void warn(String tag, String format, Object... args) {
        write("WARN", tag, format, args);
    }

    public static void error(String tag, String format, Object... args) {
        write("ERROR", tag, format, args);
    }

    private static void write(String level, String tag, String format, Object... args) {
        String body;
        try {
            body = args.length == 0 ? format : String.format(format, args);
        } catch (Exception e) {
            body = format + " (" + e.getMessage() + ")";
        }
        String line = TIME.format(Instant.now())
                + " [" + Thread.currentThread().getName() + "]"
                + " [" + level + "]"
                + " [" + tag + "] "
                + body;
        System.err.println(line);
        synchronized (LOCK) {
            try {
                Files.writeString(LOG_FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // 诊断通道失败不影响主流程
            }
        }
    }

    public static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    public static void startFxWatchdog() {
        if (!WATCHDOG.compareAndSet(false, true)) {
            return;
        }
        log("fx", "watchdog start, logFile=%s", LOG_FILE);
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kelly-fx-watchdog");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleAtFixedRate(Diag::pingFx, 1, 2, TimeUnit.SECONDS);
    }

    private static void pingFx() {
        if (!Platform.isFxApplicationThread() && !WATCHDOG.get()) {
            return;
        }
        long t0 = System.nanoTime();
        CompletableFuture<Void> ping = new CompletableFuture<>();
        try {
            Platform.runLater(() -> ping.complete(null));
        } catch (IllegalStateException e) {
            warn("fx", "toolkit not ready: %s", e.getMessage());
            return;
        }
        try {
            ping.orTimeout(2, TimeUnit.SECONDS).join();
            long ms = elapsedMs(t0);
            if (ms >= 200) {
                warn("fx", "pulse slow %dms", ms);
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof TimeoutException) {
                warn("fx", "FX thread blocked >2000ms (卡死嫌疑)");
                dumpThreads();
            } else {
                error("fx", "watchdog error: %s", cause.toString());
            }
        }
    }

    private static void dumpThreads() {
        long now = System.nanoTime();
        if (now - lastThreadDumpNanos < TimeUnit.SECONDS.toNanos(8)) {
            return;
        }
        lastThreadDumpNanos = now;
        StringBuilder sb = new StringBuilder("thread dump:");
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            sb.append(System.lineSeparator())
                    .append("  - ")
                    .append(t.getName())
                    .append(" state=")
                    .append(t.getState());
            StackTraceElement[] frames = e.getValue();
            int limit = Math.min(frames.length, 12);
            for (int i = 0; i < limit; i++) {
                sb.append(System.lineSeparator()).append("      ").append(frames[i]);
            }
        }
        warn("fx", "%s", sb);
    }

    private static Path resolveLogFile() {
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase();
        Path path = os.contains("mac")
                ? Path.of(home, "Library", "Logs", "kelly.log")
                : Path.of(home, ".kelly", "kelly.log");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ignored) {
            path = Path.of(System.getProperty("java.io.tmpdir", "."), "kelly.log");
        }
        return path;
    }
}
