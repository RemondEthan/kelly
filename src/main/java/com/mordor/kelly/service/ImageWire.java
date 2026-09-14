package com.mordor.kelly.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 图片传输协议
 *
 * 本类实现了图片在网络传输时的协议处理
 * 主要功能：
 * 1. 大图片分片传输（超过 48KB 的图片会被拆分）
 * 2. 图片元数据编解码
 * 3. 图片分片编解码
 * 4. SHA-256 哈希计算
 * 5. Base64 编解码
 *
 * 传输限制：
 * - 最大图片大小：20MB
 * - 分片大小：48KB
 */
public final class ImageWire {

    /**
     * 最大图片大小（20MB）
     * 超过此大小的图片会被拒绝
     */
    public static final int MAX_ORIGINAL_BYTES = 20 * 1024 * 1024;

    /**
     * 分片大小（48KB）
     * 大于此大小的图片会被拆分成多个分片
     */
    public static final int CHUNK_BYTES = 48 * 1024;

    /**
     * 私有构造方法，防止实例化
     */
    private ImageWire() {}

    /**
     * 图片元数据记录
     * 包含图片的基本信息，用于接收端处理
     */
    public record Meta(
            int v,              // 协议版本号
            String id,          // 图片唯一标识
            String caption,     // 图片说明文字
            String mime,        // MIME 类型（image/png, image/jpeg等）
            long bytes,         // 原始图片大小（字节）
            String sha256,      // 原始图片的 SHA-256 哈希值
            String previewJpeg  // 预览图（Base64编码的JPEG）
    ) {}

    /**
     * 图片分片记录
     * 包含分片的数据和位置信息
     */
    public record Chunk(
            String id,  // 图片唯一标识
            int i,      // 分片序号（0-based）
            int n,      // 分片总数
            String data // 分片数据（Base64编码）
    ) {}

    /**
     * 检查图片大小是否可接受
     * @param bytes 图片大小（字节）
     * @return true 表示可接受
     */
    public static boolean acceptableSize(long bytes) {
        return bytes >= 0 && bytes <= MAX_ORIGINAL_BYTES;
    }

    /**
     * 将图片拆分成多个分片
     * 每个分片大小不超过 CHUNK_BYTES
     *
     * @param original 原始图片数据
     * @return 分片列表
     * @throws IllegalArgumentException 图片为空或过大时抛出
     */
    public static List<byte[]> split(byte[] original) {
        if (original == null) {
            throw new IllegalArgumentException("original");
        }
        if (!acceptableSize(original.length)) {
            throw new IllegalArgumentException("image too large");
        }
        List<byte[]> parts = new ArrayList<>();
        if (original.length == 0) {
            parts.add(new byte[0]);
            return parts;
        }
        for (int offset = 0; offset < original.length; offset += CHUNK_BYTES) {
            int len = Math.min(CHUNK_BYTES, original.length - offset);
            byte[] slice = new byte[len];
            System.arraycopy(original, offset, slice, 0, len);
            parts.add(slice);
        }
        return parts;
    }

    /**
     * 计算数据的 SHA-256 哈希值
     * 用于验证数据完整性
     *
     * @param data 数据
     * @return 64 字符的十六进制哈希字符串
     */
    public static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data);
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

    /**
     * 编码图片元数据为 JSON 字符串
     * 手动拼接 JSON，避免引入第三方库
     *
     * @param meta 图片元数据
     * @return JSON 字符串
     */
    public static String encodeMeta(Meta meta) {
        return "{\"v\":" + meta.v()
                + ",\"id\":" + Protocol.quote(meta.id())
                + ",\"caption\":" + Protocol.quote(meta.caption())
                + ",\"mime\":" + Protocol.quote(meta.mime())
                + ",\"bytes\":" + meta.bytes()
                + ",\"sha256\":" + Protocol.quote(meta.sha256())
                + ",\"previewJpeg\":" + Protocol.quote(meta.previewJpeg())
                + "}";
    }

    /**
     * 解析 JSON 字符串为图片元数据
     * 使用 Protocol.stringField 和 Protocol.intField 提取字段
     *
     * @param json JSON 字符串
     * @return 解析后的 Meta 对象
     * @throws IllegalArgumentException JSON 不完整时抛出
     */
    public static Meta parseMeta(String json) {
        String id = Protocol.stringField(json, "id");
        String caption = Protocol.stringField(json, "caption");
        String mime = Protocol.stringField(json, "mime");
        String sha = Protocol.stringField(json, "sha256");
        String preview = Protocol.stringField(json, "previewJpeg");
        if (id == null || mime == null || sha == null || preview == null) {
            throw new IllegalArgumentException("incomplete image meta");
        }
        return new Meta(
                Protocol.intField(json, "v"),
                id,
                caption == null ? "" : caption,
                mime,
                Protocol.intField(json, "bytes"),
                sha,
                preview);
    }

    /**
     * 编码图片分片为 JSON 字符串
     *
     * @param chunk 图片分片
     * @return JSON 字符串
     */
    public static String encodeChunk(Chunk chunk) {
        return "{\"id\":" + Protocol.quote(chunk.id())
                + ",\"i\":" + chunk.i()
                + ",\"n\":" + chunk.n()
                + ",\"data\":" + Protocol.quote(chunk.data())
                + "}";
    }

    /**
     * 解析 JSON 字符串为图片分片
     *
     * @param json JSON 字符串
     * @return 解析后的 Chunk 对象
     * @throws IllegalArgumentException JSON 不完整时抛出
     */
    public static Chunk parseChunk(String json) {
        String id = Protocol.stringField(json, "id");
        String data = Protocol.stringField(json, "data");
        if (id == null || data == null) {
            throw new IllegalArgumentException("incomplete image chunk");
        }
        return new Chunk(id, Protocol.intField(json, "i"), Protocol.intField(json, "n"), data);
    }

    /**
     * 字节数组转 Base64 字符串
     * @param raw 字节数组
     * @return Base64 编码的字符串
     */
    public static String b64(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw == null ? new byte[0] : raw);
    }

    /**
     * Base64 字符串转字节数组
     * @param b64 Base64 编码的字符串
     * @return 解码后的字节数组
     */
    public static byte[] unb64(String b64) {
        return Base64.getDecoder().decode(b64 == null ? "" : b64);
    }

    /**
     * 字符串转 UTF-8 字节数组
     * @param s 字符串
     * @return UTF-8 编码的字节数组
     */
    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
