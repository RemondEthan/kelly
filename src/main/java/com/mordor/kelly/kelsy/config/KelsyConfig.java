package com.mordor.kelly.kelsy.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KelsyConfig(
        ModelSettings model,
        String workspaceDir,
        String lastUsername,
        String selfAvatarPath,
        String kelsyAvatarPath) {

    public static final String DEFAULT_WORKSPACE_DIR = "~/.kelly/kelsy/workspace";

    public KelsyConfig {
        if (model == null) {
            model = new ModelSettings(null, null, null, null);
        }
        if (workspaceDir == null || workspaceDir.isBlank()) {
            workspaceDir = DEFAULT_WORKSPACE_DIR;
        }
        if (lastUsername == null) {
            lastUsername = "";
        }
        if (selfAvatarPath == null) {
            selfAvatarPath = "";
        }
        if (kelsyAvatarPath == null) {
            kelsyAvatarPath = "";
        }
    }

    public Path workspacePath() {
        String dir = workspaceDir.startsWith("~")
                ? System.getProperty("user.home") + workspaceDir.substring(1)
                : workspaceDir;
        return Path.of(dir);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelSettings(String provider, String apiKey, String baseUrl, String modelName) {

        public ModelSettings {
            if (provider != null) {
                provider = provider.isBlank() ? null : provider.strip();
            }
            if (baseUrl != null && baseUrl.isBlank()) {
                baseUrl = null;
            }
            if (modelName != null && modelName.isBlank()) {
                modelName = null;
            }
        }
    }
}
