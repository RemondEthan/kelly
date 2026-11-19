package com.mordor.kelly.kelsy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class MigrationAgentTool implements AgentTool {
    static final String CONFIRM = "确认迁移 ";
    private static final ObjectMapper JSON = new ObjectMapper();

    private enum Operation { PLAN, PREVIEW, VERIFY }

    private final LegacyKnowledgeMigration migration;
    private final Operation operation;

    private MigrationAgentTool(LegacyKnowledgeMigration migration, Operation operation) {
        this.migration = migration;
        this.operation = operation;
    }

    static void install(Toolkit tools, LegacyKnowledgeMigration migration) {
        for (Operation operation : Operation.values()) tools.registerAgentTool(new MigrationAgentTool(migration, operation));
    }

    @Override public String getName() {
        return switch (operation) {
            case PLAN -> "migration_plan";
            case PREVIEW -> "migration_preview";
            case VERIFY -> "migration_verify";
        };
    }

    @Override public String getDescription() {
        return switch (operation) {
            case PLAN -> "Inspect legacy knowledge and TODO documents; save a preview without changing source documents or TODOs. "
                    + "Show the user the batch ID and review items. To apply, the user must send the exact message 确认迁移 <batch ID>. "
                    + "Preserve legacy documents. Present meeting action items only as optional candidates with source lines until the user explicitly adopts them.";
            case PREVIEW -> "Read a page of the migration preview by batch ID and offset.";
            case VERIFY -> "Independently verify the migration, original archive contents, live TODOs and knowledge index by batch ID.";
        };
    }

    @Override public boolean isReadOnly() { return operation != Operation.PLAN; }

    @Override public Map<String, Object> getParameters() {
        if (operation == Operation.PLAN) return Map.of("type", "object", "additionalProperties", false);
        return Map.of("type", "object", "additionalProperties", false, "required", java.util.List.of("batch"),
                "properties", Map.of("batch", Map.of("type", "string", "format", "uuid"),
                        "offset", Map.of("type", "integer", "minimum", 0)));
    }

    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            try {
                if (operation == Operation.PLAN) return ToolResultBlock.text(JSON.writeValueAsString(migration.plan()));
                var input = param.getInput();
                UUID batch = UUID.fromString(Objects.toString(input.get("batch")));
                int offset = Integer.parseInt(Objects.toString(input.get("offset"), "0"));
                var report = operation == Operation.PREVIEW ? migration.preview(batch, offset) : migration.verify(batch, offset);
                return ToolResultBlock.text(JSON.writeValueAsString(report));
            } catch (Exception e) {
                return ToolResultBlock.text(JSON.writeValueAsString(Map.of("error", Objects.toString(e.getMessage(), "迁移失败"))));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    static boolean isConfirmation(String message) {
        return message != null && message.strip().startsWith(CONFIRM);
    }

    static String apply(LegacyKnowledgeMigration migration, String input) {
        try {
            String id = input.substring(CONFIRM.length()).strip();
            UUID batch = UUID.fromString(id);
            if (!batch.toString().equalsIgnoreCase(id)) throw new IllegalArgumentException("批次标识无效");
            var report = migration.apply(batch);
            return (report.state().equals("complete") ? "迁移已完成并复核。" : "迁移部分完成，仍有待核对或失败项。")
                    + "\n\n批次 `" + batch + "`，通过 " + report.counts().getOrDefault("verified", 0L)
                    + "，待核对 " + report.counts().getOrDefault("review", 0L)
                    + "，失败 " + report.counts().getOrDefault("failed", 0L) + "。"
                    + "\n\n原件归档 `" + report.archive() + "`。"
                    + "\n\n可以请我复核此批次，查看各项状态和差异。";
        } catch (Exception e) {
            return "迁移未能完成。 " + Objects.toString(e.getMessage(), "请复核批次状态。");
        }
    }
}
