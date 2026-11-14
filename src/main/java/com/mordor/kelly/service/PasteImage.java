package com.mordor.kelly.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 粘贴图片处理
 *
 * 本类负责处理用户粘贴或拖拽的图片
 * 主要功能：
 * 1. 优先处理剪贴板 PNG 数据
 * 2. 其次处理拖拽的文件
 * 3. 根据文件扩展名判断 MIME 类型
 * 4. 只接受支持的图片格式
 *
 * 支持的格式：png, jpg, jpeg, gif, webp
 * 限制：图片大小不能超过 20MB
 */
public final class PasteImage {

    /**
     * 支持的图片扩展名
     */
    private static final Set<String> EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /**
     * 私有构造方法，防止实例化
     */
    private PasteImage() {}

    /**
     * 接受的图片数据记录
     * 包含图片字节数据和 MIME 类型
     */
    public record Accepted(byte[] bytes, String mime) {}

    /**
     * 解析粘贴/拖拽的图片
     * 优先处理剪贴板 PNG，其次处理拖拽文件
     *
     * @param rawPng 剪贴板 PNG 数据（可能为空）
     * @param files 拖拽的文件列表
     * @param hasText 是否有文本数据（未使用，保留参数）
     * @return 解析后的图片数据，无有效图片返回空
     */
    public static Optional<Accepted> resolve(
            Optional<byte[]> rawPng,
            List<Path> files,
            boolean hasText) {
        // 优先处理剪贴板 PNG 数据
        if (rawPng != null && rawPng.isPresent()) {
            byte[] bytes = rawPng.get();
            if (ImageWire.acceptableSize(bytes.length) && bytes.length > 0) {
                return Optional.of(new Accepted(bytes, "image/png"));
            }
            return Optional.empty();
        }
        // 其次处理拖拽文件
        if (files != null) {
            for (Path file : files) {
                Optional<Accepted> fromFile = fromFile(file);
                if (fromFile.isPresent()) {
                    return fromFile;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 从文件路径解析图片
     * 检查文件扩展名，读取文件内容
     *
     * @param file 文件路径
     * @return 解析后的图片数据，无效文件返回空
     */
    private static Optional<Accepted> fromFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String ext = name.substring(dot + 1);
        if (!EXTS.contains(ext)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (!ImageWire.acceptableSize(bytes.length) || bytes.length == 0) {
                return Optional.empty();
            }
            // 根据扩展名判断 MIME 类型
            String mime = switch (ext) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "gif" -> "image/gif";
                case "webp" -> "image/webp";
                default -> "image/png";
            };
            return Optional.of(new Accepted(bytes, mime));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
