/**
 * AI 模型提供者元数据。
 *
 * <p>表示一个大语言模型（LLM）服务提供商的配置信息。
 * 数据来源于 classpath 资源文件 {@code providers.json}。
 *
 * <p>字段说明：
 * <ul>
 *   <li><b>id</b> - 提供者唯一标识（如 "minimax"、"kimi"、"glm"、"deepseek"）</li>
 *   <li><b>displayName</b> - 显示名称（用于 UI 展示，如 "MiniMax"、"月之暗面"）</li>
 *   <li><b>baseUrl</b> - API 基础 URL（兼容 OpenAI Chat Completions 格式）</li>
 *   <li><b>defaultModelName</b> - 默认模型名称（如 "MiniMax-M3"、"moonshot-v1-8k"）</li>
 * </ul>
 *
 * <p>使用 Java record 类型，JSON 序列化由 Jackson 处理。
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} 确保向前兼容。
 *
 * @see ProviderCatalog
 * @see com.mordor.kelly.kelsy.service.ModelFactory
 */
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