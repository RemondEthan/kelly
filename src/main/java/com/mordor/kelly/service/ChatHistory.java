package com.mordor.kelly.service;

import com.mordor.kelly.common.Diagnostics;
import com.mordor.kelly.model.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 加密聊天历史管理
 *
 * 本类负责管理加密的聊天历史记录
 * 主要功能：
 * 1. 按 imCode 的 SHA-256 哈希分目录存储
 * 2. 使用 AES-256-GCM 加密每条消息
 * 3. 支持内存缓存和分页加载
 * 4. 自动修剪超限的历史记录
 * 5. 异步操作，不阻塞 UI 线程
 *
 * 文件格式：
 * - 每行一条加密消息
 * - 第一行是加密的 header（用于验证密码）
 * - 使用 \n 分隔符
 *
 * 存储限制：
 * - 内存中最多加载 100 条（MEMORY_CAP）
 * - 分页加载每页 50 条（PAGE_SIZE）
 * - 磁盘最多 2000 条消息（DISK_MAX_MESSAGES）
 * - 磁盘最多 8MB（DISK_MAX_BYTES）
 */
public final class ChatHistory {

    /**
     * 内存中最多加载的消息数量
     */
    public static final int MEMORY_CAP = 100;

    /**
     * 分页加载时每页的消息数量
     */
    public static final int PAGE_SIZE = 50;

    /**
     * 磁盘中最多保存的消息数量
     */
    public static final int DISK_MAX_MESSAGES = 2000;

    /**
     * 磁盘中最多保存的字节数（8MB）
     */
    public static final long DISK_MAX_BYTES = 8L * 1024 * 1024;

    /**
     * 历史文件头标识
     * 用于验证密码是否正确
     */
    static final String HEADER = "kelly-history-v1";

    /**
     * 历史文件路径
     */
    private final Path file;

    /**
     * 加密服务
     * 使用历史档案专用的密钥
     */
    private final CryptoService crypto;

    /**
     * 磁盘最大消息数限制
     */
    private final int diskMaxMessages;

    /**
     * 磁盘最大字节数限制
     */
    private final long diskMaxBytes;

    /**
     * 异步执行器
     * 单线程执行器，确保操作顺序
     */
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kelly-history");
        t.setDaemon(true);
        return t;
    });

    /**
     * 解锁状态标志
     * true 表示密码正确，可以读写历史记录
     */
    private volatile boolean unlocked;

    /**
     * 构造方法
     * 使用默认的磁盘限制
     *
     * @param file 历史文件路径
     * @param archiveCrypto 历史档案专用的加密服务
     */
    public ChatHistory(Path file, CryptoService archiveCrypto) {
        this(file, archiveCrypto, DISK_MAX_MESSAGES, DISK_MAX_BYTES);
    }

    /**
     * 构造方法（带自定义限制）
     *
     * @param file 历史文件路径
     * @param archiveCrypto 历史档案专用的加密服务
     * @param diskMaxMessages 磁盘最大消息数限制
     * @param diskMaxBytes 磁盘最大字节数限制
     */
    public ChatHistory(Path file, CryptoService archiveCrypto, int diskMaxMessages, long diskMaxBytes) {
        this.file = file;
        this.crypto = archiveCrypto;
        this.diskMaxMessages = diskMaxMessages;
        this.diskMaxBytes = diskMaxBytes;
    }

    /**
     * 获取默认的历史文件路径
     * 路径格式：~/.kelly/history/{sha256(imCode)}/messages.log
     *
     * @param imCode 聊天室标识码
     * @return 历史文件路径
     */
    public static Path defaultFile(String imCode) {
        return defaultFile(Path.of(System.getProperty("user.home"), ".kelly"), imCode);
    }

    /**
     * 获取默认的历史文件路径（带应用根目录）
     *
     * @param appRoot 应用根目录
     * @param imCode 聊天室标识码
     * @return 历史文件路径
     */
    public static Path defaultFile(Path appRoot, String imCode) {
        return appRoot.resolve("history").resolve(sha256Hex(imCode)).resolve("messages.log");
    }

    /**
     * 打开历史文件
     * 如果文件不存在，创建新文件并写入加密的 header
     * 如果文件存在，验证 header 是否正确（密码是否匹配）
     *
     * @return true 表示成功打开，false 表示密码错误或文件损坏
     */
    public boolean open() {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, crypto.encrypt(HEADER) + System.lineSeparator(), StandardCharsets.UTF_8);
                restrictOwnerOnly(file);
                unlocked = true;
                return true;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                Files.writeString(file, crypto.encrypt(HEADER) + System.lineSeparator(), StandardCharsets.UTF_8);
                unlocked = true;
                return true;
            }
            // 尝试解密第一行，验证密码是否正确
            String header = crypto.decrypt(lines.get(0).trim());
            unlocked = isHistoryHeader(header);
            // 如果 header 版本不一致，重写 header
            if (unlocked && !HEADER.equals(header)) {
                rewriteHeader(lines);
            }
            if (!unlocked) {
                Diagnostics.warn("history", "unlock failed file=%s", file);
            }
            return unlocked;
        } catch (CryptoService.CryptoException e) {
            Diagnostics.warn("history", "unlock failed file=%s: %s", file, e.getMessage());
            unlocked = false;
            return false;
        } catch (IOException e) {
            Diagnostics.warn("history", "open failed: %s", e.getMessage());
            unlocked = false;
            return false;
        }
    }

    /**
     * 检查历史文件是否已解锁
     * @return true 表示已解锁，可以读写
     */
    public boolean isUnlocked() {
        return unlocked;
    }

    /**
     * 追加消息到历史记录
     * 加密消息后追加到文件末尾
     * 如果超过限制，会自动修剪旧消息
     *
     * @param message 要追加的消息
     */
    public void append(Message message) {
        if (!unlocked || message == null) {
            return;
        }
        try {
            Files.writeString(
                    file,
                    crypto.encrypt(HistoryCodec.encode(message)) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            trimIfNeeded();
        } catch (IOException | CryptoService.CryptoException e) {
            Diagnostics.warn("history", "append failed: %s", e.getMessage());
        }
    }

    /**
     * 异步追加消息
     * 在后台线程中执行追加操作，不阻塞 UI 线程
     *
     * @param message 要追加的消息
     */
    public void appendAsync(Message message) {
        exec.execute(() -> append(message));
    }

    /**
     * 异步加载初始消息
     * 打开历史文件并加载最新的 MEMORY_CAP 条消息
     * 用于应用启动时的初始加载
     *
     * @param callback 加载完成后的回调，接收消息列表
     */
    public void loadInitialAsync(Consumer<List<Message>> callback) {
        exec.execute(() -> {
            open();
            callback.accept(loadNewest(MEMORY_CAP));
        });
    }

    /**
     * 异步加载最新的消息
     * @param limit 加载的数量限制
     * @param callback 加载完成后的回调
     */
    public void loadNewestAsync(int limit, Consumer<List<Message>> callback) {
        exec.execute(() -> callback.accept(loadNewest(limit)));
    }

    /**
     * 异步加载比指定消息更早的消息
     * 用于分页加载历史记录
     *
     * @param messageId 基准消息ID
     * @param limit 加载的数量限制
     * @param callback 加载完成后的回调
     */
    public void loadOlderThanAsync(String messageId, int limit, Consumer<List<Message>> callback) {
        exec.execute(() -> callback.accept(loadOlderThan(messageId, limit)));
    }

    /**
     * 加载最新的消息
     * @param limit 加载的数量限制
     * @return 消息列表
     */
    public List<Message> loadNewest(int limit) {
        if (!unlocked) {
            return List.of();
        }
        List<Message> all = readMessages();
        if (all.size() <= limit) {
            return all;
        }
        return List.copyOf(all.subList(all.size() - limit, all.size()));
    }

    /**
     * 加载比指定消息更早的消息
     * 用于分页加载
     *
     * @param messageId 基准消息ID
     * @param limit 加载的数量限制
     * @return 消息列表
     */
    public List<Message> loadOlderThan(String messageId, int limit) {
        if (!unlocked || messageId == null || limit <= 0) {
            return List.of();
        }
        List<Message> all = readMessages();
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (messageId.equals(all.get(i).id())) {
                idx = i;
                break;
            }
        }
        if (idx <= 0) {
            return List.of();
        }
        int from = Math.max(0, idx - limit);
        return List.copyOf(all.subList(from, idx));
    }

    /**
     * 刷新历史记录
     * 等待所有待处理的操作完成
     *
     * @param timeout 超时时间
     * @param unit 时间单位
     * @throws InterruptedException 等待被中断时抛出
     */
    public void flush(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            exec.submit(() -> null).get(timeout, unit);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("history flush failed", e);
        }
    }

    /**
     * 关闭历史记录
     * 刷新所有待处理的操作，然后关闭执行器
     */
    public void close() {
        try {
            flush(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // 退出路径：尽量刷盘，失败也要关掉执行器
        }
        exec.shutdown();
    }

    /**
     * 验证历史文件头是否有效
     * @param header 文件头字符串
     * @return true 表示有效
     */
    static boolean isHistoryHeader(String header) {
        return header != null && header.endsWith("-history-v1");
    }

    /**
     * 重写文件头
     * 当 header 版本不一致时，更新为当前版本
     *
     * @param lines 文件所有行
     * @throws IOException 写入失败时抛出
     */
    private void rewriteHeader(List<String> lines) throws IOException {
        try {
            lines.set(0, crypto.encrypt(HEADER));
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append(System.lineSeparator());
            }
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            restrictOwnerOnly(file);
        } catch (CryptoService.CryptoException e) {
            Diagnostics.warn("history", "header migrate failed: %s", e.getMessage());
        }
    }

    /**
     * 读取所有消息
     * 解密并解析每行消息
     * 跳过第一行（header）和空行
     *
     * @return 消息列表
     */
    private List<Message> readMessages() {
        List<Message> out = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    out.add(HistoryCodec.decode(crypto.decrypt(line)));
                } catch (RuntimeException ignored) {
                    // 坏行跳过
                }
            }
        } catch (IOException e) {
            Diagnostics.warn("history", "read failed: %s", e.getMessage());
        }
        return out;
    }

    /**
     * 修剪历史记录
     * 当超过磁盘限制时，从头部删除旧消息
     * 使用原子移动确保数据安全
     *
     * @throws IOException 写入失败时抛出
     */
    private void trimIfNeeded() throws IOException {
        if (Files.size(file) <= diskMaxBytes) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() <= diskMaxMessages + 1) {
                return;
            }
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return;
        }
        String header = lines.get(0);
        List<String> msgs = new ArrayList<>(lines.subList(1, lines.size()));
        // 先按消息数量修剪
        while (msgs.size() > diskMaxMessages) {
            msgs.remove(0);
        }
        // 再按字节数修剪
        while (encodedSize(header, msgs) > diskMaxBytes && !msgs.isEmpty()) {
            msgs.remove(0);
        }
        Path tmp = file.resolveSibling("messages.log.tmp");
        StringBuilder sb = new StringBuilder();
        sb.append(header).append(System.lineSeparator());
        for (String line : msgs) {
            sb.append(line).append(System.lineSeparator());
        }
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            // 尝试原子移动，如果失败则使用普通移动
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictOwnerOnly(file);
    }

    /**
     * 计算编码后的大小
     * 包括 header 和所有消息行的长度
     *
     * @param header 文件头
     * @param msgs 消息行列表
     * @return 总字节数
     */
    private static long encodedSize(String header, List<String> msgs) {
        long n = header.length() + 1L;
        for (String line : msgs) {
            n += line.length() + 1L;
        }
        return n;
    }

    /**
     * 限制文件权限为仅所有者可读写
     * 在 POSIX 系统（Linux/macOS）上有效
     * Windows 等非 POSIX 系统会忽略此操作
     *
     * @param path 文件路径
     */
    private static void restrictOwnerOnly(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 等非 POSIX
        }
    }

    /**
     * 计算字符串的 SHA-256 哈希值
     * 用于生成存储目录名
     *
     * @param imCode 聊天室标识码
     * @return 64 字符的十六进制哈希字符串
     */
    public static String sha256Hex(String imCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(imCode.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
