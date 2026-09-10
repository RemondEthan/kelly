package com.mordor.kelly.kelsy.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mordor.kelly.kelsy.KelsyPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

public final class ConfigLoader {

    private static final String TEMPLATE = """
            {
              "model": {
                "provider": "minimax",
                "apiKey": "",
                "baseUrl": "https://api.minimaxi.com/v1",
                "modelName": "MiniMax-M3"
              },
              "workspaceDir": "~/.kelly/kelsy/workspace",
              "lastUsername": "",
              "selfAvatarPath": "",
              "kelsyAvatarPath": ""
            }
            """;

    private ConfigLoader() {
    }

    public static boolean ensureAndHasApiKey(KelsyPaths paths) {
        try {
            Files.createDirectories(paths.config().getParent());
            if (!Files.exists(paths.config())) {
                if (Files.isRegularFile(paths.legacyConfig())) {
                    Files.copy(paths.legacyConfig(), paths.config(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.writeString(paths.config(), TEMPLATE);
                }
                restrictToOwner(paths.config());
            }
            KelsyConfig config = peek(paths);
            return config.model() != null
                    && config.model().apiKey() != null
                    && !config.model().apiKey().isBlank();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static KelsyConfig peek(KelsyPaths paths) {
        if (!Files.exists(paths.config())) {
            return new KelsyConfig(null, paths.workspace().toString(), "", "", "");
        }
        try {
            return new ObjectMapper().readValue(paths.config().toFile(), KelsyConfig.class);
        } catch (IOException e) {
            return new KelsyConfig(null, paths.workspace().toString(), "", "", "");
        }
    }

    public static void save(KelsyPaths paths, KelsyConfig config) {
        try {
            Files.createDirectories(paths.config().getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(paths.config().toFile(), config);
            restrictToOwner(paths.config());
        } catch (IOException e) {
            throw new UncheckedIOException("无法写入配置：" + paths.config(), e);
        }
    }

    public static KelsyConfig loadOrThrow(KelsyPaths paths) {
        if (!ensureAndHasApiKey(paths)) {
            throw new IllegalStateException("配置缺少 model.apiKey：" + paths.config());
        }
        return peek(paths);
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }
}
