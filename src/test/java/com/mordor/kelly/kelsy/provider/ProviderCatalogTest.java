package com.mordor.kelly.kelsy.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCatalogTest {

    @Test
    void loadsProvidersFromClasspath() {
        var providers = ProviderCatalog.all();
        assertFalse(providers.isEmpty(), "providers.json 应当能从 classpath 加载；路径写错时 loadFromClasspath 会静默返回空列表");
        assertTrue(providers.stream().anyMatch(p -> p.id() != null && !p.id().isBlank()),
                "至少需要一个非空 id 的 provider");
    }
}