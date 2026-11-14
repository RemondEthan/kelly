package com.mordor.kelly.service;

import javafx.scene.image.Image;
import javafx.stage.FileChooser;
import javafx.stage.Window;

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
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 头像服务
 *
 * 本类负责管理用户头像的存储和加载
 * 主要功能：
 * 1. 通过文件选择器选择头像图片
 * 2. 将头像拷贝到 ~/.kelly/avatars/ 目录
 * 3. 生成缩略图（64x64 JPEG）
 * 4. 从字节数组或文件加载头像
 *
 * 支持的图片格式：png, jpg, jpeg, gif, webp
 * 缩略图限制：控制在 32KB 以内（服务端限制）
 */
public final class AvatarService {

    /**
     * 支持的图片扩展名
     */
    private static final Set<String> EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    /**
     * 私有构造方法，防止实例化
     */
    private AvatarService() {}

    /**
     * 选择并存储用户头像
     * 打开文件选择器，让用户选择图片
     * 将选中的图片拷贝到 ~/.kelly/avatars/ 目录
     *
     * @param owner 父窗口
     * @return 存储后的头像路径，取消选择返回空
     */
    public static Optional<String> chooseAndStore(Window owner) {
        File picked = pickImage(owner);
        if (picked == null) {
            return Optional.empty();
        }
        return copyLocal(picked).map(Path::toAbsolutePath).map(Path::toString);
    }

    /**
     * 选择并存储 Kelsy 头像
     * Kelsy 是聊天室的秘书机器人
     * 头像文件名格式：kelsy-{sha256(imCode)}.*
     *
     * @param owner 父窗口
     * @param imCode 聊天室标识码
     * @return 存储后的头像路径，取消选择返回空
     */
    public static Optional<String> chooseAndStoreKelsy(Window owner, String imCode) {
        File picked = pickImage(owner);
        if (picked == null) {
            return Optional.empty();
        }
        return copyLocalAs(picked, "kelsy-" + ChatHistory.sha256Hex(imCode))
                .map(Path::toAbsolutePath)
                .map(Path::toString);
    }

    /**
     * 打开文件选择器选择图片
     * 支持 png, jpg, jpeg, gif, webp 格式
     *
     * @param owner 父窗口
     * @return 选中的文件，取消选择返回 null
     */
    private static File pickImage(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择头像");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
        return chooser.showOpenDialog(owner);
    }

    /**
     * 生成头像缩略图（Base64编码）
     * 将图片缩放到 64x64，然后编码为 JPEG Base64 字符串
     * 控制大小在 32KB 以内（服务端限制）
     *
     * @param path 图片文件路径
     * @return Base64编码的JPEG缩略图，失败返回空
     */
    public static Optional<String> thumbnailBase64(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        try {
            BufferedImage src = ImageIO.read(Path.of(path).toFile());
            if (src == null) {
                return Optional.empty();
            }
            // JPEG RGB：96×96 ARGB PNG 加密后常超过 kserver 的 32KB 丢弃线
            int size = 64;
            BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, size, size);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double scale = Math.max(size / (double) src.getWidth(), size / (double) src.getHeight());
            int w = (int) Math.round(src.getWidth() * scale);
            int h = (int) Math.round(src.getHeight() * scale);
            g.drawImage(src, (size - w) / 2, (size - h) / 2, w, h, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!writeJpeg(dst, out)) {
                return Optional.empty();
            }
            return Optional.of(Base64.getEncoder().encodeToString(out.toByteArray()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 将 BufferedImage 写入 JPEG 格式
     * 使用 0.72 的压缩质量，平衡质量和大小
     *
     * @param img 要写入的图片
     * @param out 输出流
     * @return true 表示成功
     */
    private static boolean writeJpeg(BufferedImage img, ByteArrayOutputStream out) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return false;
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.72f);
            }
            writer.setOutput(new MemoryCacheImageOutputStream(out));
            writer.write(null, new IIOImage(img, null, null), param);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            writer.dispose();
        }
    }

    /**
     * 从 PNG 字节数组加载 JavaFX Image
     * 缩放到 96x96，保持宽高比
     *
     * @param png PNG 图片字节数组
     * @return 加载后的 Image 对象，失败返回空
     */
    public static Optional<Image> fromPngBytes(byte[] png) {
        if (png == null || png.length == 0) {
            return Optional.empty();
        }
        Image image = new Image(new ByteArrayInputStream(png), 96, 96, true, true);
        if (image.isError()) {
            return Optional.empty();
        }
        return Optional.of(image);
    }

    /**
     * 从文件路径加载 JavaFX Image
     * 缩放到 96x96，保持宽高比
     *
     * @param path 图片文件路径
     * @return 加载后的 Image 对象，失败返回空
     */
    public static Optional<Image> load(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Image image = new Image(file.toUri().toString(), 96, 96, true, true, false);
        if (image.isError()) {
            return Optional.empty();
        }
        return Optional.of(image);
    }

    /**
     * 拷贝本地头像文件
     * 使用默认的文件名 "self"
     *
     * @param src 源文件
     * @return 目标文件路径
     */
    static Optional<Path> copyLocal(File src) {
        return copyLocalAs(src, "self");
    }

    /**
     * 拷贝选中的图片到头像目录
     * 目标路径：~/.kelly/avatars/{basename}.{ext}
     * 如果目标文件已存在，会覆盖
     *
     * @param picked 选中的文件
     * @param basename 目标文件名（不含扩展名）
     * @return 目标文件路径
     */
    public static Optional<Path> copyLocalAs(File picked, String basename) {
        if (picked == null || basename == null || basename.isBlank()) {
            return Optional.empty();
        }
        String ext = extension(picked.getName());
        if (!EXTS.contains(ext)) {
            return Optional.empty();
        }
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".kelly", "avatars");
            Files.createDirectories(dir);
            Path dest = dir.resolve(basename + "." + ext);
            Files.copy(picked.toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(dest);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * 提取文件扩展名
     * 返回小写的扩展名（不含点号）
     *
     * @param name 文件名
     * @return 扩展名，无扩展名返回空字符串
     */
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
