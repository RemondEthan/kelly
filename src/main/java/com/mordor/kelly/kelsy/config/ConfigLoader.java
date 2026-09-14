/**
 * Kelsy 配置文件加载器。
 *
 * <p>负责管理 Kelsy 配置文件的创建、读取和写入。
 * 配置文件采用 JSON 格式，存储在 {@code ~/.kelly/kelsy/config.json}。
 *
 * <p>配置文件结构：
 * <pre>
 * {
 *   "model": {
 *     "provider": "minimax",
 *     "apiKey": "sk-xxx",
 *     "baseUrl": "https://api.minimaxi.com/v1",
 *     "modelName": "MiniMax-M3"
 *   },
 *   "workspaceDir": "~/.kelly/kelsy/workspace",
 *   "lastUsername": "",
 *   "selfAvatarPath": "",
 *   "kelsyAvatarPath": ""
 * }
 * </pre>
 *
 * <p>安全措施：
 * <ul>
 *   <li>配置文件创建后设置 POSIX 权限为 {@code rw-------}（仅所有者可读写）</li>
 *   <li>从旧版路径（~/.kelsy/config.json）自动迁移配置</li>
 * </ul>
 *
 * <p>文件操作：
 * <ul>
 *   <li>{@link #ensureAndHasApiKey} - 确保配置文件存在且包含有效 API Key</li>
 *   <li>{@link #peek} - 读取配置（失败返回空配置，不抛异常）</li>
 *   <li>{@link #loadOrThrow} - 读取配置（失败抛出异常）</li>
 *   <li>{@link #save} - 写入配置</li>
 * </ul>
 */
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

    /**
     * 配置文件模板（首次运行时写入）。
     * 默认使用 MiniMax 作为模型提供者。
     */
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

    /** 私有构造函数，防止实例化 */
    private ConfigLoader() {
    }

    /**
     * 确保配置文件存在且包含有效的 API Key。
     *
     * <p>处理流程：
     * <ol>
     *   <li>创建配置目录（如果不存在）</li>
     *   <li>如果配置文件不存在：
     *       <ul>
     *         <li>优先从旧版路径迁移配置</li>
     *         <li>否则写入模板配置</li>
     *       </ul>
     *   </li>
     *   <li>设置文件权限为仅所有者可读写</li>
     *   <li>读取配置并验证 API Key 是否有效</li>
     * </ol>
     *
     * @param paths Kelsy 路径配置
     * @return true 如果配置文件存在且包含有效的 API Key
     */
    public static boolean ensureAndHasApiKey(KelsyPaths paths) {
        try {
            Files.createDirectories(paths.config().getParent());
            if (!Files.exists(paths.config())) {
                if (Files.isRegularFile(paths.legacyConfig())) {
                    // 从旧版配置迁移
                    Files.copy(paths.legacyConfig(), paths.config(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // 写入模板配置
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

    /**
     * 读取配置文件，失败时返回空配置（不抛异常）。
     *
     * @param paths Kelsy 路径配置
     * @return 解析后的配置，文件不存在或解析失败时返回默认空配置
     */
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

    /**
     * 将配置写入文件。
     *
     * @param paths  Kelsy 路径配置
     * @param config 要保存的配置对象
     * @throws UncheckedIOException 如果写入失败
     */
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

    /**
     * 加载配置，如果不存在或缺少 API Key 则抛出异常。
     *
     * @param paths Kelsy 路径配置
     * @return 有效的配置对象
     * @throws IllegalStateException 如果 API Key 缺失
     */
    public static KelsyConfig loadOrThrow(KelsyPaths paths) {
        if (!ensureAndHasApiKey(paths)) {
            throw new IllegalStateException("配置缺少 model.apiKey：" + paths.config());
        }
        return peek(paths);
    }

    /**
     * 设置文件权限为仅所有者可读写（POSIX rw-------）。
     * 非 POSIX 系统（Windows）会静默忽略此操作。
     *
     * @param path 要设置权限的文件路径
     */
    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }
}
