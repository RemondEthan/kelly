package com.mordor.kelly.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class PasteImage {

    private static final Set<String> EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    private PasteImage() {}

    public record Accepted(byte[] bytes, String mime) {}

    public static Optional<Accepted> resolve(
            Optional<byte[]> rawPng,
            List<Path> files,
            boolean hasText) {
        if (rawPng != null && rawPng.isPresent()) {
            byte[] bytes = rawPng.get();
            if (ImageWire.acceptableSize(bytes.length) && bytes.length > 0) {
                return Optional.of(new Accepted(bytes, "image/png"));
            }
            return Optional.empty();
        }
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
