package com.mordor.kelly.kelsy.ui.knowledge;

import com.mordor.kelly.common.Diag;
import com.mordor.kelly.kelsy.service.KnowledgeStore;
import com.mordor.kelly.kelsy.ui.markdown.MarkdownRenderer;
import com.mordor.kelly.kelsy.ui.markdown.MarkdownView;

import javafx.beans.property.BooleanProperty;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;

import java.util.List;

/** 右侧知识库：本轮来源 + Markdown 预览。 */
public final class KnowledgePane extends BorderPane {

    private final KnowledgeStore store;
    private final BooleanProperty memoryWarn;
    private final VBox sources = new VBox(4);
    private final ScrollPane host = new ScrollPane();
    private List<String> sourcePaths = List.of();
    private String currentPath;

    public KnowledgePane(KnowledgeStore store, BooleanProperty memoryWarn) {
        this.store = store;
        this.memoryWarn = memoryWarn;
        getStyleClass().add("knowledge-pane");
        sources.getStyleClass().add("knowledge-sources");
        host.setFitToWidth(true);
        host.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        host.setVbarPolicy(ScrollPane.ScrollBarPolicy.ALWAYS);
        host.getStyleClass().add("knowledge-host");
        host.setMinWidth(0);
        host.setMinHeight(0);
        host.setPrefHeight(1);
        host.setMaxHeight(Double.MAX_VALUE);
        setTop(sources);
        setCenter(host);
        showEmpty();
    }

    public void setSources(List<String> paths) {
        sourcePaths = paths == null ? List.of() : List.copyOf(paths);
        sources.getChildren().clear();
        for (String path : sourcePaths) {
            Hyperlink link = new Hyperlink(shortName(path));
            link.getStyleClass().add("knowledge-source");
            if (path.equals(currentPath)) {
                link.getStyleClass().add("knowledge-source-active");
            }
            link.setOnAction(e -> open(path));
            sources.getChildren().add(link);
        }
        memoryWarn.set(store.memoryBytes() > KnowledgeStore.MEMORY_WARN_BYTES);
    }

    public void refresh() {
        if (currentPath != null) {
            open(currentPath);
        } else {
            memoryWarn.set(store.memoryBytes() > KnowledgeStore.MEMORY_WARN_BYTES);
        }
    }

    public void open(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            showEmpty();
            return;
        }
        currentPath = relativePath;
        KnowledgeStore.Read read = store.read(relativePath);
        Diag.warn("cite", "open %s -> %s @ %s", relativePath, read.getClass().getSimpleName(),
                store.workspace());
        switch (read) {
            case KnowledgeStore.Read.Ok ok -> {
                try {
                    host.setContent(
                            new MarkdownView(MarkdownRenderer.parse(ok.markdown()), this::open));
                } catch (RuntimeException e) {
                    host.setContent(new Label("Markdown 无法解析，已显示原文\n\n" + ok.markdown()));
                }
            }
            case KnowledgeStore.Read.Missing ignored ->
                    host.setContent(new Label("文件不存在"));
            case KnowledgeStore.Read.TooLarge ignored ->
                    host.setContent(new Label("文件过大，未渲染"));
            case KnowledgeStore.Read.Rejected r ->
                    host.setContent(new Label(r.reason()));
        }
        setSources(sourcePaths);
    }

    private void showEmpty() {
        currentPath = null;
        host.setContent(new Label("本轮没有引用原文"));
    }

    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
