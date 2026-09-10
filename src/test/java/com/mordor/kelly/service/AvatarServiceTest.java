package com.mordor.kelly.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarServiceTest {

    /** 与当前 kserver handle_avatar 对齐；超限会被静默丢弃。 */
    private static final int SERVER_AVATAR_CONTENT_LIMIT = 32768;

    @Test
    void thumbnailCipherFitsServerLimit() throws Exception {
        Path tmp = Files.createTempFile("kelly-avatar", ".png");
        try {
            BufferedImage src = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 400; y++) {
                for (int x = 0; x < 400; x++) {
                    src.setRGB(x, y, (x * 73 + y * 91) & 0xFFFFFF);
                }
            }
            assertTrue(ImageIO.write(src, "png", tmp.toFile()));

            String b64 = AvatarService.thumbnailBase64(tmp.toString()).orElseThrow();
            assertFalse(b64.isBlank());
            byte[] raw = Base64.getDecoder().decode(b64);
            assertTrue(raw.length > 32);

            CryptoService crypto = new CryptoService();
            crypto.initialize("secret", "padding");
            String cipher = crypto.encrypt(b64);
            assertTrue(cipher.length() <= SERVER_AVATAR_CONTENT_LIMIT,
                    "encrypted avatar content " + cipher.length()
                            + " exceeds server drop limit " + SERVER_AVATAR_CONTENT_LIMIT);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void copyLocalAsUsesBasenameAndCopyLocalUsesSelf(@TempDir Path tmpHome) throws Exception {
        String prev = System.getProperty("user.home");
        System.setProperty("user.home", tmpHome.toString());
        try {
            Path src = Files.createTempFile(tmpHome, "pic", ".png");
            BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
            assertTrue(ImageIO.write(img, "png", src.toFile()));

            var dest = AvatarService.copyLocalAs(src.toFile(), "kelsy-abc");
            assertTrue(dest.isPresent());
            assertEquals("kelsy-abc.png", dest.get().getFileName().toString());
            assertEquals(tmpHome.resolve(".kelly").resolve("avatars").resolve("kelsy-abc.png"),
                    dest.get());
            assertTrue(Files.isRegularFile(dest.get()));

            var self = AvatarService.copyLocal(src.toFile());
            assertTrue(self.isPresent());
            assertEquals("self.png", self.get().getFileName().toString());
        } finally {
            System.setProperty("user.home", prev);
        }
    }

    @Test
    void copyLocalAsRejectsUnknownExtension() {
        assertTrue(AvatarService.copyLocalAs(new File("note.txt"), "kelsy-x").isEmpty());
    }
}
