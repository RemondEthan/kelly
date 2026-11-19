package com.mordor.kelly.kelsy.service;

import com.mordor.kelly.kelsy.storage.StorageException;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class WorkspaceReadTool implements AgentTool {
    private static final int PAGE_SIZE = 200;
    private final MarkdownDocumentStore files;
    private final Path root;
    private final String name;

    private WorkspaceReadTool(Path root, String name) {
        this.root = root.toAbsolutePath().normalize();
        this.files = new MarkdownDocumentStore(root);
        this.name = name;
    }

    static void install(Toolkit toolkit, Path root) {
        // Recursive SDK tools resolve descendants themselves, outside our per-path checks.
        for (String name : List.of("read_file", "list_files", "memory_get", "grep_files", "glob_files")) {
            toolkit.removeTool(name);
        }
        for (String name : List.of("read_file", "list_files", "memory_get")) {
            toolkit.registerAgentTool(new WorkspaceReadTool(root, name));
        }
    }

    @Override public String getName() { return name; }

    @Override public String getDescription() {
        return switch (name) {
            case "list_files" -> "List directories and files in the current user workspace.";
            case "memory_get" -> "Read numbered lines from a memory file in the current user workspace.";
            default -> "Read a UTF-8 text file in the current user workspace with line numbers and pagination.";
        };
    }

    @Override public Map<String, Object> getParameters() {
        boolean list = name.equals("list_files");
        var path = Map.of("type", "string", "description",
                list ? "Workspace-relative directory, or . for the root" : "Workspace-relative file path");
        var properties = switch (name) {
            case "list_files" -> Map.of("path", path);
            case "memory_get" -> Map.of("path", path,
                    "startLine", Map.of("type", "integer", "minimum", 1),
                    "endLine", Map.of("type", "integer", "minimum", 1));
            default -> Map.of("path", path,
                    "offset", Map.of("type", "integer", "minimum", 0, "description", "Zero-based line offset"),
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", PAGE_SIZE));
        };
        return Map.of("type", "object", "properties", properties,
                "required", list ? List.of() : List.of("path"));
    }

    @Override public boolean isReadOnly() { return true; }

    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> execute(param == null || param.getInput() == null
                ? Map.of() : param.getInput())).subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock execute(Map<String, Object> input) {
        try {
            boolean list = name.equals("list_files");
            String path = (String) input.getOrDefault("path", list ? "." : "");
            Path resolved = files.safe(path);
            if (internal(resolved)) {
                return ToolResultBlock.text("PATH_REJECTED: Internal storage file");
            }
            return ToolResultBlock.text(list ? list(resolved) : read(path, input));
        } catch (StorageException e) {
            return ToolResultBlock.text(e.code() + ": " + e.getMessage());
        } catch (IOException e) {
            return ToolResultBlock.text("IO_ERROR: Could not list the workspace directory");
        } catch (IllegalArgumentException | ClassCastException e) {
            return ToolResultBlock.text("INVALID_ARGUMENT: Invalid path or pagination");
        }
    }

    private String list(Path path) throws IOException {
        try (var entries = Files.list(path)) {
            return entries.filter(entry -> !internal(entry))
                    .filter(entry -> !Files.isSymbolicLink(entry))
                    .sorted().limit(PAGE_SIZE)
                    .map(entry -> (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ? "[DIR] " : "[FILE] ")
                            + entry.getFileName())
                    .collect(Collectors.joining("\n"));
        }
    }

    private String read(String path, Map<String, Object> input) {
        if (name.equals("memory_get")) {
            int start = number(input, "startLine", 1);
            int end = number(input, "endLine", Integer.MAX_VALUE);
            if (start < 1 || end < start) {
                throw new IllegalArgumentException("Invalid memory line range");
            }
            return lines(path, start - 1, end, "|");
        }
        int offset = number(input, "offset", 0);
        int limit = number(input, "limit", PAGE_SIZE);
        if (offset < 0 || limit < 1 || limit > PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid pagination");
        }
        return lines(path, offset, (long) offset + limit, ": ");
    }

    private String lines(String path, int offset, long end, String separator) {
        var lines = files.readText(path).lines().toList();
        return IntStream.range(offset, (int) Math.min(lines.size(), end))
                .mapToObj(i -> (i + 1) + separator + lines.get(i))
                .collect(Collectors.joining("\n"));
    }

    private static int number(Map<String, Object> input, String name, int defaultValue) {
        Object value = input.getOrDefault(name, defaultValue);
        if (value instanceof Number number) {
            return Integer.parseInt(number.toString());
        }
        throw new IllegalArgumentException("Expected an integer");
    }

    private boolean internal(Path path) {
        for (Path part : root.relativize(path)) {
            if (part.toString().toLowerCase(Locale.ROOT).startsWith(".kelly-")) {
                return true;
            }
        }
        return false;
    }
}
