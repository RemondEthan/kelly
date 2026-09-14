package com.mordor.kelly.service;

/**
 * 图片分片重组器
 *
 * 本类负责将接收到的图片分片重新组装成完整的图片
 * 大图片在传输时会被拆分成多个分片（chunk），接收端需要将它们按顺序拼接
 *
 * 使用方法：
 * 1. 创建 ImageAssembler 实例，指定分片总数 n
 * 2. 依次调用 offer(i, data) 添加每个分片
 * 3. 当 offer() 返回 true 时，表示所有分片已收齐
 * 4. 调用 bytes() 获取完整的图片数据
 *
 * 线程安全：本类不是线程安全的，需要在单线程中使用
 */
public final class ImageAssembler {

    /**
     * 分片数组
     * 索引对应分片序号，值对应分片数据
     */
    private final byte[][] parts;

    /**
     * 已填充的分片数量
     */
    private int filled;

    /**
     * 构造方法
     * @param n 分片总数
     */
    public ImageAssembler(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n");
        }
        this.parts = new byte[n][];
    }

    /**
     * 添加分片
     * 如果分片索引有效且尚未填充，则添加分片
     *
     * @param i 分片索引（0-based）
     * @param data 分片数据
     * @return true 表示所有分片已收齐，可以调用 bytes()
     * @throws IllegalArgumentException 分片索引无效时抛出
     */
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

    /**
     * 获取完整的图片数据
     * 将所有分片按顺序拼接成一个字节数组
     *
     * @return 完整的图片数据
     * @throws IllegalStateException 分片未收齐时抛出
     */
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
