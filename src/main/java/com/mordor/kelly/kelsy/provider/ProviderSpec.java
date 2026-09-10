package com.mordor.kelly.kelsy.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 一家大模型服务商的元数据。来自 classpath providers.json。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderSpec(
        String id,
        String displayName,
        String baseUrl,
        String defaultModelName) {
}