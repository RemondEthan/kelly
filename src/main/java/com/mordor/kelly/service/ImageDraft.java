package com.mordor.kelly.service;

public record ImageDraft(byte[] bytes, String mime, String caption) {
    public ImageDraft {
        bytes = bytes == null ? new byte[0] : bytes;
        mime = mime == null || mime.isBlank() ? "image/png" : mime;
        caption = caption == null ? "" : caption;
    }
}
