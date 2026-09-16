/**
 * Kelsy AI 助手运行时管理器（单例模式）。
 *
 * <p>职责：
 * <ul>
 *   <li>管理 AI 助手（AssistantService）的完整生命周期：创建、复用、关闭</li>
 *   <li>协调 AgentScope 运行时环境的初始化与销毁</li>
 *   <li>确保工作空间（workspace）目录结构在首次使用时被正确播种（seed）</li>
 *   <li>提供全局唯一的运行时实例，避免重复初始化</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>采用懒加载单例模式：首次调用 {@link #shared(String)} 时才创建实例</li>
 *   <li>当工作空间路径发生变化时（如用户切换），会自动关闭旧实例并重新创建</li>
 *   <li>实现了 {@link AutoCloseable} 接口，支持优雅关闭</li>
 *   <li>工厂函数模式：通过 {@code Function<KelsyConfig, AssistantService>} 注入服务创建逻辑，
 *       便于测试和替换实现</li>
 * </ul>
 *
 * <p>典型使用流程：
 * <pre>
 *   // 获取共享运行时（首次调用会初始化）
 *   KelsyRuntime runtime = KelsyRuntime.shared("alice");
 *   // 确保助手已创建（懒加载）
 *   AssistantService assistant = runtime.ensureAssistant();
 *   // 使用助手进行对话...
 *   // 关闭运行时
 *   KelsyRuntime.shutdown();
 * </pre>
 */
package com.mordor.kelly.kelsy;

import com.mordor.kelly.common.Diagnostics;
import com.mordor.kelly.kelsy.config.ConfigLoader;
import com.mordor.kelly.kelsy.config.KelsyConfig;
import com.mordor.kelly.kelsy.service.AssistantService;
import com.mordor.kelly.kelsy.service.KnowledgeStore;
import com.mordor.kelly.kelsy.service.LocalAssistantService;
import com.mordor.kelly.kelsy.service.MemoryCompactor;
import com.mordor.kelly.kelsy.service.WorkspaceSeeder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.function.Function;

public final class KelsyRuntime implements AutoCloseable {

    /** 全局唯一的运行时实例（懒加载单例） */
    private static KelsyRuntime instance;

    /** Kelsy 文件系统路径配置（配置文件、工作空间等） */
    private final KelsyPaths paths;

    /** 助手服务的工厂函数：接收配置，返回 AssistantService 实例 */
    private final Function<KelsyConfig, AssistantService> factory;

    /** 当前活跃的助手服务实例（懒加载，首次调用 ensureAssistant 时创建） */
    private AssistantService assistant;

    /** 缓存的用户知识库（每个运行时一条 SQLite 连接） */
    private KnowledgeStore store;

    /** 运行时是否已关闭的标志位，关闭后不可再使用 */
    private boolean closed;

    /**
     * 解析实际的工作空间路径。
     * 从配置文件中读取 workspacePath，覆盖默认路径。
     *
     * @param homePaths 默认的路径配置
     * @return 包含实际工作空间路径的新 KelsyPaths
     */
    public static KelsyPaths resolve(KelsyPaths homePaths) {
        return homePaths.withWorkspace(ConfigLoader.peek(homePaths).workspacePath());
    }

    /**
     * 获取共享的运行时实例（线程安全的懒加载单例）。
     *
     * <p>如果当前实例的工作空间路径与期望路径不一致（例如用户切换），
     * 会自动关闭旧实例并重新创建。首次调用时会执行完整的初始化流程：
     * <ol>
     *   <li>加载配置文件</li>
     *   <li>创建工作空间目录结构（AGENTS.md、MEMORY.md 等模板文件）</li>
     *   <li>创建 LocalAssistantService 实例</li>
     * </ol>
     *
     * @param username 当前用户名，用于确定用户专属知识库目录
     * @return 全局共享的 KelsyRuntime 实例
     */
    public static synchronized KelsyRuntime shared(String username) {
        KelsyPaths paths = resolve(KelsyPaths.defaults());
        // 检查是否需要切换用户（工作空间路径变化）
        if (instance != null
                && !instance.paths.workspace().toAbsolutePath().normalize()
                .equals(paths.workspace().toAbsolutePath().normalize())) {
            shutdown();
        }
        if (instance == null) {
            instance = open(paths, username,
                    cfg -> LocalAssistantService.create(cfg, username, instance.store(username)));
        }
        return instance;
    }

    /**
     * 全局关闭运行时，释放所有资源。
     * 关闭后会销毁助手服务实例，释放 AgentScope 运行时上下文。
     * 线程安全：通过 synchronized 保证并发安全。
     */
    public static synchronized void shutdown() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    /**
     * 创建新的运行时实例。
     *
     * <p>初始化步骤：
     * <ol>
     *   <li>确保配置文件存在且包含有效的 API Key</li>
     *   <li>播种工作空间模板文件（AGENTS.md、MEMORY.md 等）</li>
     *   <li>播种用户专属的知识库目录</li>
     * </ol>
     *
     * @param paths    文件系统路径配置
     * @param username 当前用户名
     * @param factory  助手服务工厂函数，用于创建 AssistantService 实例
     * @return 新创建的 KelsyRuntime 实例
     * @throws UncheckedIOException 如果配置文件创建失败
     */
    public static KelsyRuntime open(KelsyPaths paths, String username,
                                    Function<KelsyConfig, AssistantService> factory) {
        ConfigLoader.ensureAndHasApiKey(paths);
        WorkspaceSeeder.seed(paths.workspace());
        WorkspaceSeeder.seed(KnowledgeStore.knowledgeRoot(paths.workspace(), username));
        upgradeKnowledge(KnowledgeStore.knowledgeRoot(paths.workspace(), username));
        return new KelsyRuntime(paths, factory);
    }

    public static void upgradeKnowledge(Path userRoot) {
        try (KnowledgeStore store = new KnowledgeStore(userRoot)) {
            if (store.index() != null) {
                store.index().reconcile();
            }
            if (store.memoryBytes() > MemoryCompactor.LIMIT_BYTES) {
                String raw = Files.readString(userRoot.resolve("MEMORY.md"));
                var result = MemoryCompactor.compact(raw, LocalDate.now());
                MemoryCompactor.apply(userRoot, result);
                if (store.index() != null) {
                    store.upsert("MEMORY.md");
                    for (var card : result.inboxCards()) {
                        store.upsert(card.relativePath());
                    }
                    store.index().reconcile();
                }
            }
        } catch (Exception e) {
            Diagnostics.warn("kelsy", "knowledge upgrade skipped: %s", e.toString());
        }
    }

    /**
     * 私有构造函数，通过 {@link #open} 工厂方法创建实例。
     */
    private KelsyRuntime(KelsyPaths paths, Function<KelsyConfig, AssistantService> factory) {
        this.paths = paths;
        this.factory = factory;
    }

    /**
     * 检查是否已配置有效的 API Key。
     * 如果配置文件不存在，会自动创建模板文件。
     *
     * @return true 如果 API Key 已配置且非空
     */
    public boolean hasApiKey() {
        return ConfigLoader.ensureAndHasApiKey(paths);
    }

    /**
     * 获取指定用户的知识库存储实例。
     * 知识库位于工作空间下的用户子目录中。
     *
     * @param username 用户名
     * @return 该用户的 KnowledgeStore 实例
     */
    public synchronized KnowledgeStore store(String username) {
        Path root = KnowledgeStore.knowledgeRoot(paths.workspace(), username);
        if (store != null && store.workspace().equals(root)) {
            return store;
        }
        if (store != null) {
            store.close();
        }
        store = KnowledgeStore.forUser(paths.workspace(), username);
        return store;
    }

    /**
     * 确保助手服务已创建并返回（懒加载）。
     *
     * <p>创建条件：
     * <ul>
     *   <li>运行时未关闭</li>
     *   <li>助手服务尚未创建</li>
     *   <li>API Key 已配置</li>
     * </ul>
     *
     * <p>如果 API Key 未配置，返回 null，UI 层可据此提示用户配置。
     *
     * @return 已创建的 AssistantService 实例，或 null（未配置 API Key 时）
     */
    public synchronized AssistantService ensureAssistant() {
        if (closed) {
            return null;
        }
        if (assistant != null) {
            return assistant;
        }
        if (!hasApiKey()) {
            return null;
        }
        assistant = factory.apply(ConfigLoader.loadOrThrow(paths));
        return assistant;
    }

    /**
     * 获取当前的助手服务实例（可能为 null，如果尚未调用 ensureAssistant）。
     *
     * @return 当前助手服务，或 null
     */
    public AssistantService assistant() {
        return assistant;
    }

    /**
     * 获取 Kelsy 文件系统路径配置。
     *
     * @return 路径配置对象
     */
    public KelsyPaths paths() {
        return paths;
    }

    /**
     * 关闭运行时，释放助手服务资源。
     * 关闭后 closed 标志位为 true，ensureAssistant 将返回 null。
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (assistant != null) {
            assistant.close();
            assistant = null;
        }
        if (store != null) {
            store.close();
            store = null;
        }
    }
}

