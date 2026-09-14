package com.mordor.kelly.service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * 预览图生成
 *
 * 本类负责生成图片的预览图（缩略图）
 * 主要功能：
 * 1. 将原始图片缩放到合适的大小
 * 2. 转换为 JPEG 格式
 * 3. 控制文件大小在 400KB 以内
 * 4. 自动调整压缩质量以满足大小限制
 *
 * 限制：
 * - 最大边长：1280 像素
 * - 最小边长：480 像素
 * - 最大文件大小：400KB
 *
 * 算法：
 * 1. 初始使用 MAX_EDGE 和 0.8 质量
 * 2. 如果超过 MAX_BYTES，降低质量
 * 3. 如果质量降到 0.45 以下，减小尺寸
 * 4. 重复直到满足大小限制
 */
public final class PreviewJpeg {

    /**
     * 最大边长（1280 像素）
     */
    public static final int MAX_EDGE = 1280;

    /**
     * 最小边长（480 像素）
     */
    public static final int MIN_EDGE = 480;

    /**
     * 最大文件大小（400KB）
     */
    public static final int MAX_BYTES = 400 * 1024;

    /**
     * 私有构造方法，防止实例化
     */
    private PreviewJpeg() {}

    /**
     * 生成预览图
     * 自动调整尺寸和质量以满足大小限制
     *
     * @param original 原始图片字节数据
     * @return JPEG 格式的预览图字节数据
     * @throws IllegalArgumentException 图片无法解码或处理失败时抛出
     */
    public static byte[] encode(byte[] original) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(original));
            if (src == null) {
                throw new IllegalArgumentException("undecodable image");
            }
            float quality = 0.8f;
            int edge = MAX_EDGE;
            byte[] last = jpeg(scale(src, edge), quality);
            // 循环调整直到满足大小限制
            while (last.length > MAX_BYTES && (edge > MIN_EDGE || quality > 0.45f)) {
                if (last.length > MAX_BYTES && quality > 0.45f) {
                    quality -= 0.1f;
                } else if (edge > MIN_EDGE) {
                    edge = Math.max(MIN_EDGE, (int) (edge * 0.85));
                    quality = 0.8f;
                }
                last = jpeg(scale(src, edge), quality);
            }
            return last;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("preview failed", e);
        }
    }

    /**
     * 缩放图片
     * 保持宽高比，将最长边缩放到 maxEdge
     * 背景填充白色
     *
     * @param src 原始图片
     * @param maxEdge 最大边长
     * @return 缩放后的图片
     */
    static BufferedImage scale(BufferedImage src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, maxEdge / (double) Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return dst;
    }

    /**
     * 将 BufferedImage 编码为 JPEG
     * 使用指定的压缩质量
     *
     * @param img 要编码的图片
     * @param quality 压缩质量（0.0-1.0）
     * @return JPEG 字节数据
     * @throws Exception 编码失败时抛出
     */
    private static byte[] jpeg(BufferedImage img, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("no jpeg writer");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.setOutput(new MemoryCacheImageOutputStream(out));
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
