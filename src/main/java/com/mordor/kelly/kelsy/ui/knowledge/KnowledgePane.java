/**
 * 知识库面板组件。
 *
 * <p>显示在聊天界面右侧，展示 AI 助手本轮对话引用的知识库文件。
 * 用户可以浏览引用来源的原文内容，理解 AI 回复的信息依据。
 *
 * <p>功能：
 * <ul>
 *   <li><b>来源列表</b> - 顶部显示本轮引用的文件路径链接</li>
 *   <li><b>内容预览</b> - 主体区域显示 Markdown 渲染的知识文件内容</li>
 *   <li><b>文件导航</b> - 点击链接可跳转到其他知识文件</li>
 *   <li><b>内存警告</b> - 当 MEMORY.md 超过 8KB 时触发警告</li>
 * </ul>
 *
 * <p>数据流：
 * <pre>
 *   CitationTurn.shown() → setSources(paths) → 显示来源链接
 *   用户点击链接 → open(path) → KnowledgeStore.read() → MarkdownView 渲染
 * </pre>
 *
 * @see KnowledgeStore
 * @see CitationTurn
 */
package com.mordor.kelly.kelsy.ui.knowledge;

import com.mordor.kelly.common.Diagnostics;
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

    /** 知识库存储实例 */
    private final KnowledgeStore store;

    /** MEMORY.md 大小警告属性（超过 8KB 时为 true） */
    private final BooleanProperty memoryWarn;

    /** 来源链接列表容器 */
    private final VBox sources = new VBox(4);

    /** 内容预览滚动容器 */
    private final ScrollPane host = new ScrollPane();

    /** 本轮引用的文件路径列表 */
    private List<String> sourcePaths = List.of();

    /** 当前正在预览的文件路径 */
    private String currentPath;

    /**
     * 创建知识库面板。
     *
     * @param store      知识库存储实例
     * @param memoryWarn MEMORY.md 大小警告属性
     */
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

    /**
     * 设置本轮引用的来源文件列表。
     * 在顶部生成可点击的文件路径链接。
     *
     * @param paths 引用的文件路径列表
     */
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
        // 检查 MEMORY.md 大小，超过阈值时触发警告
        memoryWarn.set(store.memoryBytes() > KnowledgeStore.MEMORY_WARN_BYTES);
    }

    /**
     * 刷新当前预览内容。
     */
    public void refresh() {
        if (currentPath != null) {
            open(currentPath);
        } else {
            memoryWarn.set(store.memoryBytes() > KnowledgeStore.MEMORY_WARN_BYTES);
        }
    }

    /**
     * 打开并预览指定的知识文件。
     *
     * @param relativePath 相对于工作空间的文件路径
     */
    public void open(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            showEmpty();
            return;
        }
        currentPath = relativePath;
        KnowledgeStore.Read read = store.read(relativePath);
        Diagnostics.warn("cite", "open %s -> %s @ %s", relativePath, read.getClass().getSimpleName(),
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

    /**
     * 显示空状态提示。
     */
    private void showEmpty() {
        currentPath = null;
        host.setContent(new Label("本轮没有引用原文"));
    }

    /**
     * 从完整路径中提取短文件名（用于链接显示）。
     */
    private static String shortName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
