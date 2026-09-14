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
 * 诊断日志系统。
 * 默认只写 WARN / ERROR 级别的日志。调试轨迹用 log() 方法，需设置 JVM 参数 {@code -Dkelly.debug=true} 才输出。
 * 日志文件位置：
 * - macOS: ~/Library/Logs/kelly.log
 * - 其他系统: ~/.kelly/kelly.log
 * 如果日志目录创建失败，则回退到系统临时目录下的 kelly.log。
 * 
 * 日志格式：HH:mm:ss.SSS [线程名] [级别] [标签] 消息
 * 
 * 该类还包含一个 FX 看门狗，用于检测 JavaFX 线程是否卡死。
 * 看门狗每 2 秒 ping 一次 FX 线程，如果超过 2 秒无响应，则判定为卡死并 dump 线程堆栈。
 * 线程 dump 有频率限制，8 秒内不会重复 dump。
 */
public final class Diagnostics {

    /**
     * 日志时间格式化器。
     * 使用系统默认时区，格式为 HH:mm:ss.SSS（时:分:秒.毫秒）。
     */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    /**
     * 日志文件路径。
     * 通过 resolveLogFile() 方法根据操作系统类型确定。
     */
    private static final Path LOG_FILE = resolveLogFile();

    /**
     * 文件写入同步锁。
     * 确保多线程环境下日志文件写入的线程安全性。
     */
    private static final Object LOCK = new Object();

    /**
     * FX 看门狗状态标志。
     * 使用 AtomicBoolean 确保线程安全，防止看门狗被多次启动。
     */
    private static final AtomicBoolean WATCHDOG = new AtomicBoolean();

    /**
     * 调试模式标志。
     * 从 JVM 系统属性 "kelly.debug" 读取，仅在该属性为 true 时启用调试日志输出。
     */
    private static final boolean DEBUG = Boolean.getBoolean("kelly.debug");

    /**
     * 上次线程 dump 的时间戳（纳秒）。
     * 用于线程 dump 频率限制，确保 8 秒内不重复 dump。
     */
    private static volatile long lastThreadDumpNanos;

    /**
     * 私有构造函数，防止工具类被实例化。
     */
    private Diagnostics() {}

    /**
     * 获取日志文件路径。
     * @return 日志文件的完整路径
     */
    public static Path logFile() {
        return LOG_FILE;
    }

    /**
     * 输出调试级别日志。
     * 仅在设置了 -Dkelly.debug=true 时才会实际输出。
     * 
     * @param tag 日志标签，用于标识日志来源模块
     * @param format 格式化字符串，支持 %s, %d 等占位符
     * @param args 格式化参数，与 format 中的占位符对应
     */
    public static void log(String tag, String format, Object... args) {
        if (DEBUG) {
            write("DEBUG", tag, format, args);
        }
    }

    /**
     * 输出警告级别日志。
     * 始终输出，不受调试模式限制。
     * 
     * @param tag 日志标签，用于标识日志来源模块
     * @param format 格式化字符串，支持 %s, %d 等占位符
     * @param args 格式化参数，与 format 中的占位符对应
     */
    public static void warn(String tag, String format, Object... args) {
        write("WARN", tag, format, args);
    }

    /**
     * 输出错误级别日志。
     * 始终输出，不受调试模式限制。
     * 
     * @param tag 日志标签，用于标识日志来源模块
     * @param format 格式化字符串，支持 %s, %d 等占位符
     * @param args 格式化参数，与 format 中的占位符对应
     */
    public static void error(String tag, String format, Object... args) {
        write("ERROR", tag, format, args);
    }

    /**
     * 写入日志的内部方法。
     * 格式化日志消息，同时输出到标准错误和日志文件。
     * 日志格式：HH:mm:ss.SSS [线程名] [级别] [标签] 消息
     * 
     * @param level 日志级别（DEBUG, WARN, ERROR）
     * @param tag 日志标签，用于标识日志来源模块
     * @param format 格式化字符串，支持 %s, %d 等占位符
     * @param args 格式化参数，与 format 中的占位符对应
     */
    private static void write(String level, String tag, String format, Object... args) {
        String body;
        try {
            // 如果没有参数，直接使用格式字符串；否则使用 String.format 格式化
            body = args.length == 0 ? format : String.format(format, args);
        } catch (Exception e) {
            // 格式化失败时，追加错误信息
            body = format + " (" + e.getMessage() + ")";
        }
        // 构建完整的日志行，格式：时间 [线程名] [级别] [标签] 消息
        String line = TIME.format(Instant.now())
                + " [" + Thread.currentThread().getName() + "]"
                + " [" + level + "]"
                + " [" + tag + "] "
                + body;
        // 同时输出到标准错误流
        System.err.println(line);
        // 同步写入日志文件，确保线程安全
        synchronized (LOCK) {
            try {
                Files.writeString(LOG_FILE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // 诊断通道失败不影响主流程，静默处理
            }
        }
    }

    /**
     * 计算从开始时间到现在的毫秒数。
     * 用于性能测量和看门狗超时计算。
     * 
     * @param startNanos 开始时间的纳秒数（System.nanoTime()）
     * @return 经过的毫秒数
     */
    public static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 启动 FX 看门狗。
     * 使用原子布尔值确保看门狗只启动一次。
     * 看门狗每 2 秒检查一次 JavaFX 线程的响应性。
     */
    public static void startFxWatchdog() {
        // 使用 CAS 操作确保看门狗只启动一次
        if (!WATCHDOG.compareAndSet(false, true)) {
            return;
        }
        log("fx", "watchdog start, logFile=%s", LOG_FILE);
        // 创建单线程调度执行器，用于定期执行 pingFx 任务
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kelly-fx-watchdog");
            t.setDaemon(true); // 设为守护线程，不阻止 JVM 退出
            return t;
        });
        // 每 2 秒执行一次 pingFx 任务，首次延迟 1 秒
        exec.scheduleAtFixedRate(Diagnostics::pingFx, 1, 2, TimeUnit.SECONDS);
    }

    /**
     * 向 FX 线程发送 ping 以检测其响应性。
     * 通过 CompletableFuture 实现超时检测。
     * 如果 FX 线程在 2 秒内无响应，则判定为卡死并 dump 线程堆栈。
     * 如果响应时间超过 200 毫秒，则输出警告日志。
     */
    private static void pingFx() {
        // 如果不是 FX 应用线程且看门狗未启动，则直接返回
        if (!Platform.isFxApplicationThread() && !WATCHDOG.get()) {
            return;
        }
        long t0 = System.nanoTime();
        CompletableFuture<Void> ping = new CompletableFuture<>();
        try {
            // 向 FX 线程提交一个完成任务，如果 FX 线程正常，会执行此任务
            Platform.runLater(() -> ping.complete(null));
        } catch (IllegalStateException e) {
            // JavaFX 工具包尚未就绪，输出警告并返回
            warn("fx", "toolkit not ready: %s", e.getMessage());
            return;
        }
        try {
            // 设置 2 秒超时，如果 FX 线程在 2 秒内未完成，则抛出 TimeoutException
            ping.orTimeout(2, TimeUnit.SECONDS).join();
            long ms = elapsedMs(t0);
            // 如果响应时间超过 200 毫秒，输出性能警告
            if (ms >= 200) {
                warn("fx", "pulse slow %dms", ms);
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof TimeoutException) {
                // FX 线程卡死，输出警告并 dump 线程堆栈
                warn("fx", "FX thread blocked >2000ms (卡死嫌疑)");
                dumpThreads();
            } else {
                // 其他错误，输出错误日志
                error("fx", "watchdog error: %s", cause.toString());
            }
        }
    }

    /**
     * Dump 所有线程的堆栈信息。
     * 用于诊断 FX 线程卡死问题。
     * 有频率限制，8 秒内不会重复 dump。
     * 输出格式包含线程名、状态和最多 12 帧堆栈信息。
     */
    private static void dumpThreads() {
        long now = System.nanoTime();
        // 频率限制：8 秒内不重复 dump
        if (now - lastThreadDumpNanos < TimeUnit.SECONDS.toNanos(8)) {
            return;
        }
        lastThreadDumpNanos = now;
        StringBuilder sb = new StringBuilder("thread dump:");
        // 遍历所有线程及其堆栈
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            sb.append(System.lineSeparator())
                    .append("  - ")
                    .append(t.getName())
                    .append(" state=")
                    .append(t.getState());
            StackTraceElement[] frames = e.getValue();
            // 限制堆栈深度为 12 帧，避免输出过多信息
            int limit = Math.min(frames.length, 12);
            for (int i = 0; i < limit; i++) {
                sb.append(System.lineSeparator()).append("      ").append(frames[i]);
            }
        }
        warn("fx", "%s", sb);
    }

    /**
     * 解析日志文件路径。
     * 根据操作系统类型选择不同的路径：
     * - macOS: ~/Library/Logs/kelly.log
     * - 其他系统: ~/.kelly/kelly.log
     * 如果目录创建失败，则回退到系统临时目录下的 kelly.log。
     * 
     * @return 日志文件的完整路径
     */
    private static Path resolveLogFile() {
        String home = System.getProperty("user.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase();
        // 根据操作系统类型选择日志路径
        Path path = os.contains("mac")
                ? Path.of(home, "Library", "Logs", "kelly.log")
                : Path.of(home, ".kelly", "kelly.log");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                // 创建日志目录（如果不存在）
                Files.createDirectories(parent);
            }
        } catch (IOException ignored) {
            // 目录创建失败，回退到系统临时目录
            path = Path.of(System.getProperty("java.io.tmpdir", "."), "kelly.log");
        }
        return path;
    }
}