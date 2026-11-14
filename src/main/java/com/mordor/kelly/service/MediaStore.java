package com.mordor.kelly.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 媒体文件存储
 *
 * 本类负责管理聊天中传输的图片文件
 * 主要功能：
 * 1. 按 imCode 的 SHA-256 哈希分目录存储
 * 2. 保存原图和预览图
 * 3. 支持分片传输的临时文件管理
 * 4. 根据 MIME 类型生成正确的文件扩展名
 *
 * 存储结构：
 * ~/.kelly/media/{sha256(imCode)}/
 *   ├── {mediaId}.preview.jpg      # 预览图
 *   ├── {mediaId}.jpg/.png/.gif/.webp  # 原图
 *   └── {mediaId}.part             # 传输中的临时文件
 */
public final class MediaStore {

    /**
     * 存储根目录
     */
    private final Path root;

    /**
     * 构造方法
     * @param root 存储根目录
     */
    public MediaStore(Path root) {
        this.root = root;
    }

    /**
     * 创建默认的媒体存储
     * 根目录为 ~/.kelly/media
     *
     * @return 默认的 MediaStore 实例
     */
    public static MediaStore defaultStore() {
        return new MediaStore(Path.of(System.getProperty("user.home"), ".kelly", "media"));
    }

    /**
     * 存储结果记录
     * 包含预览图和原图的相对路径
     */
    public record Stored(String previewRel, String originalRel) {}

    /**
     * 获取聊天室对应的存储目录
     * 目录名是 imCode 的 SHA-256 哈希值
     *
     * @param imCode 聊天室标识码
     * @return 目录路径
     */
    public Path dir(String imCode) {
        return root.resolve(ChatHistory.sha256Hex(imCode));
    }

    /**
     * 解析相对路径
     * @param imCode 聊天室标识码
     * @param rel 相对路径
     * @return 绝对路径
     */
    public Path resolve(String imCode, String rel) {
        return dir(imCode).resolve(rel);
    }

    /**
     * 保存图片文件
     * 同时保存原图和预览图
     *
     * @param imCode 聊天室标识码
     * @param mediaId 媒体文件ID
     * @param original 原图字节数据
     * @param mime MIME 类型
     * @param previewJpeg 预览图字节数据（JPEG格式）
     * @return 存储结果，包含相对路径
     * @throws IOException 写入失败时抛出
     */
    public Stored save(String imCode, String mediaId, byte[] original, String mime, byte[] previewJpeg)
            throws IOException {
        Path dir = dir(imCode);
        Files.createDirectories(dir);
        String origRel = originalName(mediaId, mime);
        String prevRel = previewName(mediaId);
        Files.write(dir.resolve(origRel), original);
        Files.write(dir.resolve(prevRel), previewJpeg);
        return new Stored(prevRel, origRel);
    }

    /**
     * 写入分片数据到临时文件
     * 用于分片传输过程中保存已收到的数据
     *
     * @param imCode 聊天室标识码
     * @param mediaId 媒体文件ID
     * @param data 分片数据
     * @throws IOException 写入失败时抛出
     */
    public void writePart(String imCode, String mediaId, byte[] data) throws IOException {
        Path dir = dir(imCode);
        Files.createDirectories(dir);
        Files.write(dir.resolve(mediaId + ".part"), data);
    }

    /**
     * 提交原图
     * 将临时的 .part 文件重命名为最终的图片文件
     *
     * @param imCode 聊天室标识码
     * @param mediaId 媒体文件ID
     * @param mime MIME 类型
     * @throws IOException 移动失败时抛出
     */
    public void commitOriginal(String imCode, String mediaId, String mime) throws IOException {
        Path dir = dir(imCode);
        Path part = dir.resolve(mediaId + ".part");
        Path dest = dir.resolve(mediaId + originalSuffix(mime));
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 写入预览图
     * @param imCode 聊天室标识码
     * @param mediaId 媒体文件ID
     * @param jpeg 预览图字节数据
     * @throws IOException 写入失败时抛出
     */
    public void writePreview(String imCode, String mediaId, byte[] jpeg) throws IOException {
        Path dir = dir(imCode);
        Files.createDirectories(dir);
        Files.write(dir.resolve(previewName(mediaId)), jpeg);
    }

    /**
     * 生成预览图文件名
     * 格式：{mediaId}.preview.jpg
     *
     * @param mediaId 媒体文件ID
     * @return 预览图文件名
     */
    public static String previewName(String mediaId) {
        return mediaId + ".preview.jpg";
    }

    /**
     * 生成原图文件名
     * 格式：{mediaId}.{扩展名}
     *
     * @param mediaId 媒体文件ID
     * @param mime MIME 类型
     * @return 原图文件名
     */
    public static String originalName(String mediaId, String mime) {
        return mediaId + originalSuffix(mime);
    }

    /**
     * 根据 MIME 类型获取文件扩展名
     * 支持：jpg, gif, webp, png
     * 未知类型返回 .bin
     *
     * @param mime MIME 类型
     * @return 文件扩展名（含点号）
     */
    static String originalSuffix(String mime) {
        if (mime == null) {
            return ".bin";
        }
        return switch (mime) {
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/png" -> ".png";
            default -> ".bin";
        };
    }
}
