package com.mordor.kelly.kelsy;

import com.mordor.kelly.kelsy.config.ConfigLoader;
import com.mordor.kelly.kelsy.config.KelsyConfig;
import com.mordor.kelly.kelsy.service.AssistantService;
import com.mordor.kelly.kelsy.service.KnowledgeStore;
import com.mordor.kelly.kelsy.service.LocalAssistantService;
import com.mordor.kelly.kelsy.service.WorkspaceSeeder;

import java.util.function.Function;

public final class KelsyRuntime implements AutoCloseable {

    private static KelsyRuntime instance;

    private final KelsyPaths paths;
    private final Function<KelsyConfig, AssistantService> factory;
    private AssistantService assistant;
    private boolean closed;

    public static KelsyPaths resolve(KelsyPaths homePaths) {
        return homePaths.withWorkspace(ConfigLoader.peek(homePaths).workspacePath());
    }

    public static synchronized KelsyRuntime shared(String username) {
        KelsyPaths paths = resolve(KelsyPaths.defaults());
        if (instance != null
                && !instance.paths.workspace().toAbsolutePath().normalize()
                .equals(paths.workspace().toAbsolutePath().normalize())) {
            shutdown();
        }
        if (instance == null) {
            instance = open(paths, username,
                    cfg -> LocalAssistantService.create(cfg, username));
        }
        return instance;
    }

    public static synchronized void shutdown() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    public static KelsyRuntime open(KelsyPaths paths, String username,
                                    Function<KelsyConfig, AssistantService> factory) {
        ConfigLoader.ensureAndHasApiKey(paths);
        WorkspaceSeeder.seed(paths.workspace());
        WorkspaceSeeder.seed(KnowledgeStore.knowledgeRoot(paths.workspace(), username));
        return new KelsyRuntime(paths, factory);
    }

    private KelsyRuntime(KelsyPaths paths, Function<KelsyConfig, AssistantService> factory) {
        this.paths = paths;
        this.factory = factory;
    }

    public boolean hasApiKey() {
        return ConfigLoader.ensureAndHasApiKey(paths);
    }

    public KnowledgeStore store(String username) {
        return KnowledgeStore.forUser(paths.workspace(), username);
    }

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

    public AssistantService assistant() {
        return assistant;
    }

    public KelsyPaths paths() {
        return paths;
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (assistant != null) {
            assistant.close();
            assistant = null;
        }
    }
}

