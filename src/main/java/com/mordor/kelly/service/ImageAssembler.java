package com.mordor.kelly.service;

public final class ImageAssembler {

    private final byte[][] parts;
    private int filled;

    public ImageAssembler(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n");
        }
        this.parts = new byte[n][];
    }

    public boolean offer(int i, byte[] data) {
        if (i < 0 || i >= parts.length) {
            throw new IllegalArgumentException("chunk index");
        }
        if (parts[i] == null) {
            parts[i] = data == null ? new byte[0] : data;
            filled++;
        }
        return filled == parts.length;
    }

    public byte[] bytes() {
        if (filled != parts.length) {
            throw new IllegalStateException("incomplete");
        }
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
