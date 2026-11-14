/**
 * Kelsy 配置模型（不可变数据类）。
 *
 * <p>对应配置文件 {@code config.json} 的数据结构，包含以下配置组：
 * <ul>
 *   <li><b>model</b> - AI 模型配置（提供者、API Key、基础 URL、模型名称）</li>
 *   <li><b>workspaceDir</b> - 工作空间目录路径</li>
 *   <li><b>lastUsername</b> - 上次使用的用户名</li>
 *   <li><b>selfAvatarPath</b> - 用户自定义头像路径</li>
 *   <li><b>kelsyAvatarPath</b> - Kelsy 助手自定义头像路径</li>
 * </ul>
 *
 * <p>使用 Java record 类型，JSON 序列化由 Jackson 处理。
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 确保向前兼容：
 * 配置文件中的未知字段会被忽略，不会导致解析失败。
 *
 * <p>工作空间路径支持 {@code ~} 前缀（代表用户主目录），通过
 * {@link #workspacePath()} 方法解析为实际的 {@link java.nio.file.Path}。
 */
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

    /** 默认工作空间目录（相对于用户主目录） */
    public static final String DEFAULT_WORKSPACE_DIR = "~/.kelly/kelsy/workspace";

    /**
     * 紧凑构造函数：确保所有字段非 null，提供默认值。
     * Jackson 反序列化时会调用此构造函数。
     */
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

    /**
     * 将工作空间目录路径解析为实际的 Path 对象。
     * 支持 {@code ~} 前缀（替换为用户主目录）。
     *
     * @return 解析后的绝对路径
     */
    public Path workspacePath() {
        String dir = workspaceDir.startsWith("~")
                ? System.getProperty("user.home") + workspaceDir.substring(1)
                : workspaceDir;
        return Path.of(dir);
    }

    /**
     * AI 模型配置子类。
     *
     * <p>包含连接 AI 模型 API 所需的所有参数：
     * <ul>
     *   <li><b>provider</b> - 模型提供者标识（如 "minimax"、"kimi"、"glm"、"deepseek"）</li>
     *   <li><b>apiKey</b> - API 认证密钥</li>
     *   <li><b>baseUrl</b> - API 基础 URL（兼容 OpenAI 格式）</li>
     *   <li><b>modelName</b> - 具体模型名称（如 "MiniMax-M3"）</li>
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelSettings(String provider, String apiKey, String baseUrl, String modelName) {

        /**
         * 紧凑构造函数：规范化字符串字段（空白值转为 null）。
         */
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
