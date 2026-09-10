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
 * 本地头像：选图后拷到 ~/.kelly/avatars/&lt;basename&gt;.*，路径可落 Preferences。
 */
public final class AvatarService {

    private static final Set<String> EXTS = Set.of("png", "jpg", "jpeg", "gif", "webp");

    private AvatarService() {}

    public static Optional<String> chooseAndStore(Window owner) {
        File picked = pickImage(owner);
        if (picked == null) {
            return Optional.empty();
        }
        return copyLocal(picked).map(Path::toAbsolutePath).map(Path::toString);
    }

    /** 选图后拷到 ~/.kelly/avatars/kelsy-&lt;imCode 哈希&gt;.*，供本房间秘书使用。 */
    public static Optional<String> chooseAndStoreKelsy(Window owner, String imCode) {
        File picked = pickImage(owner);
        if (picked == null) {
            return Optional.empty();
        }
        return copyLocalAs(picked, "kelsy-" + ChatHistory.sha256Hex(imCode))
                .map(Path::toAbsolutePath)
                .map(Path::toString);
    }

    private static File pickImage(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择头像");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("图片", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
        return chooser.showOpenDialog(owner);
    }

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

    static Optional<Path> copyLocal(File src) {
        return copyLocalAs(src, "self");
    }

    /** 把选中的图片拷到 ~/.kelly/avatars/&lt;basename&gt;.&lt;ext&gt;。 */
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

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
