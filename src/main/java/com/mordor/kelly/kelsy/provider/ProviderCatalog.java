/**
 * AI 模型提供者目录。
 *
 * <p>从 classpath 资源文件 {@code /com/mordor/kelly/kelsy/providers.json} 加载
 * 所有可用的 AI 模型提供者配置。提供者配置包含 API URL、默认模型名称等元数据。
 *
 * <p>加载的提供者用于：
 * <ul>
 *   <li>在配置文件中选择提供者时，提供默认的 baseUrl 和 modelName</li>
 *   <li>在 {@link com.mordor.kelly.kelsy.service.ModelFactory} 中根据 provider ID
 *       查找对应的格式化器（Formatter）</li>
 * </ul>
 *
 * <p>线程安全：使用双重检查锁定（DCL）实现懒加载缓存。
 * 加载失败时返回空列表，不会抛出异常。
 *
 * <p>支持的提供者示例（来自 providers.json）：
 * <pre>
 *   minimax  → MiniMax-M3, https://api.minimaxi.com/v1
 *   kimi     → moonshot-v1-8k, https://api.moonshot.cn/v1
 *   glm      → glm-4, https://open.bigmodel.cn/api/paas/v4
 *   deepseek → deepseek-chat, https://api.deepseek.com
 * </pre>
 */
package com.mordor.kelly.kelsy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** 从 classpath providers.json 加载的提供商清单；进程内缓存，失败返回空列表。 */
public final class ProviderCatalog {

    /** providers.json 资源路径 */
    private static final String RESOURCE = "/com/mordor/kelly/kelsy/providers.json";

    /** Jackson ObjectMapper 实例（线程安全） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 缓存的提供者列表（volatile 保证可见性） */
    private static volatile List<ProviderSpec> cached;

    /** 私有构造函数，防止实例化 */
    private ProviderCatalog() {}

    /**
     * 获取所有可用的提供者列表。
     * 首次调用时从 classpath 加载，后续返回缓存。
     *
     * @return 提供者列表（加载失败时返回空列表）
     */
    public static List<ProviderSpec> all() {
        List<ProviderSpec> snapshot = cached;
        if (snapshot != null) {
            return snapshot;
        }
        // 双重检查锁定：确保只加载一次
        synchronized (ProviderCatalog.class) {
            if (cached == null) {
                cached = loadFromClasspath();
            }
            return cached;
        }
    }

    /**
     * 根据提供者 ID 查找提供者配置。
     * 匹配时不区分大小写。
     *
     * @param id 提供者标识（如 "minimax"、"kimi"）
     * @return 匹配的提供者配置，未找到时返回 Optional.empty()
     */
    public static Optional<ProviderSpec> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String needle = id.strip();
        return all().stream()
                .filter(p -> p.id() != null && p.id().equalsIgnoreCase(needle))
                .findFirst();
    }

    /**
     * 从 classpath 加载 providers.json 资源文件。
     * 加载失败（文件不存在、解析错误等）返回空列表。
     *
     * @return 提供者列表
     */
    private static List<ProviderSpec> loadFromClasspath() {
        var url = ProviderCatalog.class.getResource(RESOURCE);
        if (url == null) {
            return List.of();
        }
        try (var in = url.openStream()) {
            return MAPPER.readValue(in, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, ProviderSpec.class));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}