package com.mordor.kelly.app;

import com.mordor.kelly.common.Diag;
import com.mordor.kelly.kelsy.KelsyPaths;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kelly 安装身份与运行时锁，不得与旁路应用共用。
 */
class AppIsolationTest {

    private static final int OTHER_APP_INSTANCE_PORT = 18731;
    private static final String FORBIDDEN = new String(new char[] {'k', 'm', 'a', 't', 'e'});

    @Test
    void singleInstancePortIsKellySpecific() throws Exception {
        Field port = SingleInstance.class.getDeclaredField("PORT");
        port.setAccessible(true);
        assertEquals(18732, port.getInt(null));
        assertTrue(port.getInt(null) != OTHER_APP_INSTANCE_PORT);
    }

    @Test
    void diagLogIsKelly() {
        assertEquals("kelly.log", Diag.logFile().getFileName().toString());
    }

    @Test
    void userDataLivesUnderDotKelly() {
        Path home = Path.of("/tmp/isolation-home");
        KelsyPaths paths = KelsyPaths.forHome(home);
        assertTrue(paths.config().startsWith(home.resolve(".kelly")));
    }

    @Test
    void windowsMsiIdentityIsKelly() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        assertTrue(pom.contains("<argument>Kelly</argument>"));
        assertTrue(pom.contains("<argument>--win-upgrade-uuid</argument>"));
        assertTrue(pom.contains("e8b3c4d1-7a52-4f9e-b1c6-0d4e8a7f2b91"));
        assertTrue(pom.contains("--win-menu-group"));
        assertTrue(pom.contains("--install-dir"));
    }

    @Test
    void runtimeIconsAreKelly() throws Exception {
        String unread = Files.readString(
                Path.of("src/main/java/com/mordor/kelly/app/UnreadAlert.java"));
        String tray = Files.readString(
                Path.of("src/main/java/com/mordor/kelly/app/TrayManager.java"));
        assertTrue(unread.contains("/icons/kelly.png"));
        assertTrue(unread.contains("/icons/kelly-alert.png"));
        assertTrue(tray.contains("打开 Kelly"));
    }

    @Test
    void mainSourcesDoNotMentionForbiddenName() throws Exception {
        List<String> hits = new ArrayList<>();
        for (String root : List.of("src/main", "src/test", "pom.xml")) {
            Path start = Path.of(root);
            if (Files.isRegularFile(start)) {
                scanFile(start, hits);
                continue;
            }
            try (Stream<Path> walk = Files.walk(start)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                            return name.endsWith(".java") || name.endsWith(".json")
                                    || name.endsWith(".css") || name.endsWith(".md")
                                    || name.endsWith(".xml") || name.endsWith(".properties")
                                    || name.endsWith(".fxml");
                        })
                        .forEach(path -> scanFile(path, hits));
            }
        }
        assertTrue(hits.isEmpty(), "forbidden name remains in: " + hits);
    }

    private static void scanFile(Path path, List<String> hits) {
        try {
            String text = Files.readString(path);
            if (text.toLowerCase(Locale.ROOT).contains(FORBIDDEN)) {
                hits.add(path.toString());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
