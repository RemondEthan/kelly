package com.mordor.kelly.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class MediaStore {

    private final Path root;

    public MediaStore(Path root) {
        this.root = root;
    }

    public static MediaStore defaultStore() {
        return new MediaStore(Path.of(System.getProperty("user.home"), ".kelly", "media"));
    }

    public record Stored(String previewRel, String originalRel) {}

    public Path dir(String imCode) {
        return root.resolve(ChatHistory.sha256Hex(imCode));
    }

    public Path resolve(String imCode, String rel) {
        return dir(imCode).resolve(rel);
    }

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

    public void writePart(String imCode, String mediaId, byte[] data) throws IOException {
        Path dir = dir(imCode);
        Files.createDirectories(dir);
        Files.write(dir.resolve(mediaId + ".part"), data);
    }

    public void commitOriginal(String imCode, String mediaId, String mime) throws IOException {
        Path dir = dir(imCode);
        Path part = dir.resolve(mediaId + ".part");
        Path dest = dir.resolve(mediaId + originalSuffix(mime));
        Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    public void writePreview(String imCode, String mediaId, byte[] jpeg) throws IOException {
        Path dir = dir(imCode);
        Files.createDirectories(dir);
        Files.write(dir.resolve(previewName(mediaId)), jpeg);
    }

    public static String previewName(String mediaId) {
        return mediaId + ".preview.jpg";
    }

    public static String originalName(String mediaId, String mime) {
        return mediaId + originalSuffix(mime);
    }

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
