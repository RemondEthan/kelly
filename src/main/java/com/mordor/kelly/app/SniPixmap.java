package com.mordor.kelly.app;

import org.freedesktop.dbus.Struct;
import org.freedesktop.dbus.annotations.Position;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * StatusNotifierItem 的 IconPixmap 结构 ((iiay)) 与像素编码工具。
 *
 * <p>SNI 协议的图标不走图标主题名，而是把像素直接塞进 D-Bus 消息：
 * 每个条目为 (宽 i, 高 i, 像素 ay)，像素按 ARGB32 排列——每像素 4 字节，
 * 依次是 A、R、G、B 各一字节（大端位序，即 ARGB int 逐字节写出）。
 * 提供多组尺寸（16/22/24/32/48），面板按自身 DPI 挑最合适的。
 *
 * <p>线程安全：无状态静态方法，可在任意线程调用。
 */
public final class SniPixmap extends Struct {

    @Position(0)
    public final int width;

    @Position(1)
    public final int height;

    /** ARGB32 像素数据，length == width * height * 4 */
    @Position(2)
    public final byte[] pixels;

    public SniPixmap(int width, int height, byte[] pixels) {
        if (pixels.length != width * height * 4) {
            throw new IllegalArgumentException(
                    "像素长度 " + pixels.length + " 与 " + width + "x" + height + "x4 不符");
        }
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    /**
     * 把源图缩放为 size x size 并编码为一条 IconPixmap。
     *
     * @param src  源图（任意尺寸，非方形会整体压缩为方形）
     * @param size 目标边长（像素）
     */
    public static SniPixmap encode(BufferedImage src, int size) {
        return new SniPixmap(size, size, toArgb32(scaleSquare(src, size)));
    }

    /**
     * 按给定的多组尺寸批量编码，供面板按 DPI 选择。
     */
    public static List<SniPixmap> encodeAll(BufferedImage src, int... sizes) {
        List<SniPixmap> out = new ArrayList<>(sizes.length);
        for (int size : sizes) {
            out.add(encode(src, size));
        }
        return out;
    }

    /**
     * 等边缩放。用双线性插值，托盘图标这种小尺寸下比默认最近邻平滑。
     */
    static BufferedImage scaleSquare(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    /**
     * 取像素并按 ARGB32 排布（A、R、G、B 各一字节）。
     */
    static byte[] toArgb32(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] out = new byte[px.length * 4];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int o = i * 4;
            out[o] = (byte) (p >>> 24);
            out[o + 1] = (byte) (p >>> 16);
            out[o + 2] = (byte) (p >>> 8);
            out[o + 3] = (byte) p;
        }
        return out;
    }

    /**
     * AWT Image 转 BufferedImage（编码需要随机像素访问）。
     */
    static BufferedImage toBuffered(Image src) {
        if (src instanceof BufferedImage buffered) {
            return buffered;
        }
        BufferedImage out = new BufferedImage(src.getWidth(null), src.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }
}
