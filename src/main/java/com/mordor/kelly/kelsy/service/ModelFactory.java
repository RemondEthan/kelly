/**
 * AI 模型工厂。
 *
 * <p>负责根据用户配置创建对应的 AI 模型实例。
 * 支持多种模型提供者（MiniMax、Kimi、GLM、DeepSeek），
 * 每种提供者使用不同的消息格式化器（Formatter）。
 *
 * <p>模型创建流程：
 * <pre>
 *   1. 解析配置（ModelSettings）→ 提供者、API Key、URL、模型名
 *   2. 查找提供者元数据（ProviderCatalog）→ 补全默认 URL 和模型名
 *   3. 选择格式化器（Formatter）→ 处理不同 API 的消息格式差异
 *   4. 构建 OpenAIChatModel → 配置 API Key、URL、模型名、格式化器
 * </pre>
 *
 * <p>支持的提供者及格式化器：
 * <ul>
 *   <li><b>minimax</b> → MiniMaxFormatter（MiniMax API 格式）</li>
 *   <li><b>kimi</b> → KimiFormatter（月之暗面 API 格式）</li>
 *   <li><b>glm</b> → GLMFormatter（智谱 API 格式）</li>
 *   <li><b>deepseek</b> → DeepSeekFormatter（深度求索 API 格式）</li>
 * </ul>
 *
 * <p>格式化器（Formatter）的作用：
 * AI 模型提供者虽然都兼容 OpenAI Chat Completions API，
 * 但在消息格式、系统提示词处理、工具调用格式等方面存在细微差异。
 * 格式化器负责将标准消息格式转换为各提供者所需的特定格式。
 *
 * @see ProviderCatalog
 * @see com.mordor.kelly.kelsy.config.KelsyConfig.ModelSettings
 */
package com.mordor.kelly.kelsy.service;

import com.mordor.kelly.kelsy.config.KelsyConfig.ModelSettings;
import com.mordor.kelly.kelsy.provider.ProviderCatalog;
import com.mordor.kelly.kelsy.provider.ProviderSpec;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import io.agentscope.extensions.model.openai.compat.glm.GLMFormatter;
import io.agentscope.extensions.model.openai.compat.kimi.KimiFormatter;
import io.agentscope.extensions.model.openai.compat.minimax.MiniMaxFormatter;
import io.agentscope.extensions.model.openai.formatter.OpenAIBaseFormatter;

public final class ModelFactory {

    /**
     * 解析后的模型配置结果。
     * 包含最终使用的提供者、API Key、URL、模型名和格式化器。
     *
     * @param provider    提供者标识
     * @param apiKey      API 认证密钥
     * @param baseUrl     API 基础 URL
     * @param modelName   模型名称
     * @param formatter   消息格式化器（处理不同 API 的格式差异）
     */
    public record Resolved(
            String provider,
            String apiKey,
            String baseUrl,
            String modelName,
            OpenAIBaseFormatter formatter) {
    }

    /** 私有构造函数，防止实例化 */
    private ModelFactory() {}

    /**
     * 解析模型配置：将用户配置转换为最终的模型参数。
     *
     * <p>解析优先级：
     * <ol>
     *   <li>用户提供者标识（配置文件中的 provider）</li>
     *   <li>ProviderCatalog 中的默认值（baseUrl、modelName）</li>
     * </ol>
     *
     * @param settings 用户的模型配置
     * @return 解析后的完整配置
     * @throws IllegalArgumentException 如果提供者不被支持
     */
    public static Resolved resolve(ModelSettings settings) {
        String provider = settings.provider() == null || settings.provider().isBlank()
                ? "minimax"
                : settings.provider().strip().toLowerCase();
        String apiKey = settings.apiKey();
        ProviderSpec spec = ProviderCatalog.findById(provider).orElse(null);
        // 优先使用用户配置，其次使用提供者默认值
        String baseUrl = firstNonBlank(settings.baseUrl(),
                spec != null ? spec.baseUrl() : null);
        String modelName = firstNonBlank(settings.modelName(),
                spec != null ? spec.defaultModelName() : null);
        OpenAIBaseFormatter formatter = formatterFor(provider);
        if (formatter == null) {
            throw new IllegalArgumentException(
                    "未知 model.provider：" + provider
                            + "。合法值：minimax, kimi, glm, deepseek");
        }
        return new Resolved(provider, apiKey, baseUrl, modelName, formatter);
    }

    /**
     * 创建 AI 模型实例。
     *
     * <p>构建 AgentScope 的 OpenAIChatModel，配置：
     * <ul>
     *   <li>API Key 和基础 URL</li>
     *   <li>模型名称</li>
     *   <li>消息格式化器</li>
     *   <li>启用流式响应（stream=true）</li>
     * </ul>
     *
     * @param settings 用户的模型配置
     * @return 可用于 HarnessAgent 的 Model 实例
     */
    public static Model create(ModelSettings settings) {
        Resolved r = resolve(settings);
        return OpenAIChatModel.builder()
                .apiKey(r.apiKey())
                .baseUrl(r.baseUrl())
                .modelName(r.modelName())
                .formatter(r.formatter())
                .stream(true)
                .build();
    }

    /**
     * 根据提供者标识返回对应的消息格式化器。
     * 格式化器处理不同 AI API 的消息格式差异。
     *
     * @param provider 提供者标识（小写）
     * @return 对应的格式化器，不支持的提供者返回 null
     */
    private static OpenAIBaseFormatter formatterFor(String provider) {
        return switch (provider) {
            case "minimax"  -> new MiniMaxFormatter();
            case "kimi"     -> new KimiFormatter();
            case "glm"      -> new GLMFormatter();
            case "deepseek" -> new DeepSeekFormatter();
            default         -> null;
        };
    }

    /**
     * 返回第一个非空白字符串。
     * 用于配置优先级：用户配置 > 提供者默认值。
     *
     * @param a 第一个候选值
     * @param b 第二个候选值
     * @return 第一个非空白值，都为空时返回 null
     */
    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
