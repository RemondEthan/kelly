package com.mordor.kelly.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class ImageWire {

    public static final int MAX_ORIGINAL_BYTES = 20 * 1024 * 1024;
    public static final int CHUNK_BYTES = 48 * 1024;

    private ImageWire() {}

    public record Meta(
            int v,
            String id,
            String caption,
            String mime,
            long bytes,
            String sha256,
            String previewJpeg
    ) {}

    public record Chunk(String id, int i, int n, String data) {}

    public static boolean acceptableSize(long bytes) {
        return bytes >= 0 && bytes <= MAX_ORIGINAL_BYTES;
    }

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

    public static String encodeChunk(Chunk chunk) {
        return "{\"id\":" + Protocol.quote(chunk.id())
                + ",\"i\":" + chunk.i()
                + ",\"n\":" + chunk.n()
                + ",\"data\":" + Protocol.quote(chunk.data())
                + "}";
    }

    public static Chunk parseChunk(String json) {
        String id = Protocol.stringField(json, "id");
        String data = Protocol.stringField(json, "data");
        if (id == null || data == null) {
            throw new IllegalArgumentException("incomplete image chunk");
        }
        return new Chunk(id, Protocol.intField(json, "i"), Protocol.intField(json, "n"), data);
    }

    public static String b64(byte[] raw) {
        return Base64.getEncoder().encodeToString(raw == null ? new byte[0] : raw);
    }

    public static byte[] unb64(String b64) {
        return Base64.getDecoder().decode(b64 == null ? "" : b64);
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
