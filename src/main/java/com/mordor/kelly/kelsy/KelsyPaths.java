package com.mordor.kelly.kelsy;

import java.nio.file.Path;

public record KelsyPaths(Path config, Path workspace, Path legacyConfig) {

    public static KelsyPaths defaults() {
        return forHome(Path.of(System.getProperty("user.home")));
    }

    public static KelsyPaths forHome(Path home) {
        return new KelsyPaths(
                home.resolve(".kelly").resolve("kelsy").resolve("config.json"),
                home.resolve(".kelly").resolve("kelsy").resolve("workspace"),
                home.resolve(".kelsy").resolve("config.json"));
    }

    public KelsyPaths withWorkspace(Path workspace) {
        return new KelsyPaths(config, workspace, legacyConfig);
    }
}
