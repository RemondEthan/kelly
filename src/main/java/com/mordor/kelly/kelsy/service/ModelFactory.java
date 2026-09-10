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

    public record Resolved(
            String provider,
            String apiKey,
            String baseUrl,
            String modelName,
            OpenAIBaseFormatter formatter) {
    }

    private ModelFactory() {}

    public static Resolved resolve(ModelSettings settings) {
        String provider = settings.provider() == null || settings.provider().isBlank()
                ? "minimax"
                : settings.provider().strip().toLowerCase();
        String apiKey = settings.apiKey();
        ProviderSpec spec = ProviderCatalog.findById(provider).orElse(null);
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

    private static OpenAIBaseFormatter formatterFor(String provider) {
        return switch (provider) {
            case "minimax"  -> new MiniMaxFormatter();
            case "kimi"     -> new KimiFormatter();
            case "glm"      -> new GLMFormatter();
            case "deepseek" -> new DeepSeekFormatter();
            default         -> null;
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
