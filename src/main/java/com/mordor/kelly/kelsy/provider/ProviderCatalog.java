package com.mordor.kelly.kelsy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** 从 classpath providers.json 加载的提供商清单；进程内缓存，失败返回空列表。 */
public final class ProviderCatalog {

    private static final String RESOURCE = "/com/mordor/kelly/kelsy/providers.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile List<ProviderSpec> cached;

    private ProviderCatalog() {}

    public static List<ProviderSpec> all() {
        List<ProviderSpec> snapshot = cached;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (ProviderCatalog.class) {
            if (cached == null) {
                cached = loadFromClasspath();
            }
            return cached;
        }
    }

    public static Optional<ProviderSpec> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String needle = id.strip();
        return all().stream()
                .filter(p -> p.id() != null && p.id().equalsIgnoreCase(needle))
                .findFirst();
    }

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