package com.mordor.kelly.service;

import com.mordor.kelly.common.Diag;
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
 * 按 IM_CODE 一份加密文本档案。打开失败（密码不对）则不解密、不改写。
 */
public final class ChatHistory {

    public static final int MEMORY_CAP = 100;
    public static final int PAGE_SIZE = 50;
    public static final int DISK_MAX_MESSAGES = 2000;
    public static final long DISK_MAX_BYTES = 8L * 1024 * 1024;
    static final String HEADER = "kelly-history-v1";

    private final Path file;
    private final CryptoService crypto;
    private final int diskMaxMessages;
    private final long diskMaxBytes;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kelly-history");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean unlocked;

    public ChatHistory(Path file, CryptoService archiveCrypto) {
        this(file, archiveCrypto, DISK_MAX_MESSAGES, DISK_MAX_BYTES);
    }

    public ChatHistory(Path file, CryptoService archiveCrypto, int diskMaxMessages, long diskMaxBytes) {
        this.file = file;
        this.crypto = archiveCrypto;
        this.diskMaxMessages = diskMaxMessages;
        this.diskMaxBytes = diskMaxBytes;
    }

    public static Path defaultFile(String imCode) {
        return defaultFile(Path.of(System.getProperty("user.home"), ".kelly"), imCode);
    }

    public static Path defaultFile(Path appRoot, String imCode) {
        return appRoot.resolve("history").resolve(sha256Hex(imCode)).resolve("messages.log");
    }

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
            String header = crypto.decrypt(lines.get(0).trim());
            unlocked = isHistoryHeader(header);
            if (unlocked && !HEADER.equals(header)) {
                rewriteHeader(lines);
            }
            if (!unlocked) {
                Diag.warn("history", "unlock failed file=%s", file);
            }
            return unlocked;
        } catch (CryptoService.CryptoException e) {
            Diag.warn("history", "unlock failed file=%s: %s", file, e.getMessage());
            unlocked = false;
            return false;
        } catch (IOException e) {
            Diag.warn("history", "open failed: %s", e.getMessage());
            unlocked = false;
            return false;
        }
    }

    public boolean isUnlocked() {
        return unlocked;
    }

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
            Diag.warn("history", "append failed: %s", e.getMessage());
        }
    }

    public void appendAsync(Message message) {
        exec.execute(() -> append(message));
    }

    public void loadInitialAsync(Consumer<List<Message>> callback) {
        exec.execute(() -> {
            open();
            callback.accept(loadNewest(MEMORY_CAP));
        });
    }

    public void loadNewestAsync(int limit, Consumer<List<Message>> callback) {
        exec.execute(() -> callback.accept(loadNewest(limit)));
    }

    public void loadOlderThanAsync(String messageId, int limit, Consumer<List<Message>> callback) {
        exec.execute(() -> callback.accept(loadOlderThan(messageId, limit)));
    }

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

    public void flush(long timeout, TimeUnit unit) throws InterruptedException {
        try {
            exec.submit(() -> null).get(timeout, unit);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("history flush failed", e);
        }
    }

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

    static boolean isHistoryHeader(String header) {
        return header != null && header.endsWith("-history-v1");
    }

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
            Diag.warn("history", "header migrate failed: %s", e.getMessage());
        }
    }

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
            Diag.warn("history", "read failed: %s", e.getMessage());
        }
        return out;
    }

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
        while (msgs.size() > diskMaxMessages) {
            msgs.remove(0);
        }
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
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        restrictOwnerOnly(file);
    }

    private static long encodedSize(String header, List<String> msgs) {
        long n = header.length() + 1L;
        for (String line : msgs) {
            n += line.length() + 1L;
        }
        return n;
    }

    private static void restrictOwnerOnly(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 等非 POSIX
        }
    }

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
