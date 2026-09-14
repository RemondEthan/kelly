/**
 * Markdown 视图组件。
 *
 * <p>将 {@link MdNode} AST 节点列表渲染为 JavaFX UI 节点。
 * 支持标题、段落、列表、引用、代码块、表格等 Markdown 元素的可视化渲染。
 *
 * <p>渲染流程：
 * <pre>
 *   List&lt;MdNode&gt;（由 MarkdownRenderer.parse 生成）
 *   → renderBlock() 递归渲染每个节点
 *   → 生成 JavaFX Node（TextFlow、Label、VBox、HBox 等）
 *   → 添加到 MarkdownView（VBox）中显示
 * </pre>
 *
 * <p>布局约束：
 * <ul>
 *   <li>自适应父容器宽度（通过 layoutChildren 动态调整）</li>
 *   <li>文本自动换行</li>
 *   <li>代码块使用等宽字体</li>
 *   <li>表格使用管道符分隔显示</li>
 * </ul>
 *
 * <p>链接处理：
 * <ul>
 *   <li>HTTP/HTTPS 链接 → 使用系统浏览器打开</li>
 *   <li>.md 文件链接 → 通过 onWorkspaceLink 回调打开知识库文件</li>
 * </ul>
 *
 * @see MarkdownRenderer
 * @see MdNode
 * @see MdSpan
 */
package com.mordor.kelly.kelsy.ui.markdown;

import com.mordor.kelly.ui.chat.SelectableTextFlow;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.TextFlow;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class MarkdownView extends VBox {

    /** 默认文本颜色 */
    private static final Color INK = Color.web("#212121");

    /** 工作空间文件链接点击回调 */
    private final Consumer<String> onWorkspaceLink;

    /** 外部链接点击回调（用于打开 HTTP 链接） */
    private final Consumer<String> onLinkClick;

    /** 上次布局时的宽度（避免重复约束） */
    private double lastWrap = -1;

    /**
     * 创建 Markdown 视图。
     *
     * @param nodes           Markdown AST 节点列表
     * @param onWorkspaceLink 工作空间文件链接点击回调
     */
    public MarkdownView(List<MdNode> nodes, Consumer<String> onWorkspaceLink) {
        this.onWorkspaceLink = onWorkspaceLink == null ? path -> {
        } : onWorkspaceLink;
        this.onLinkClick = dest -> openLink(dest);
        getStyleClass().add("md-view");
        setSpacing(6);
        setFillWidth(true);
        setMinWidth(0);
        setMaxWidth(Double.MAX_VALUE);
        if (nodes != null) {
            for (MdNode node : nodes) {
                getChildren().add(renderBlock(node));
            }
        }
    }

    /**
     * 布局子节点时动态调整宽度约束。
     * 当容器宽度变化时，重新约束所有子节点的最大宽度。
     */
    @Override
    protected void layoutChildren() {
        double inner = Math.max(1, getWidth() - snappedLeftInset() - snappedRightInset());
        if (Math.abs(inner - lastWrap) >= 1.0) {
            lastWrap = inner;
            for (Node child : getChildren()) {
                constrain(child, inner);
            }
        }
        super.layoutChildren();
    }

    /**
     * 递归约束节点的最大宽度。
     * 支持 TextFlow、Label、HBox、VBox 等容器类型。
     */
    private static void constrain(Node node, double width) {
        if (node instanceof TextFlow flow) {
            flow.setMinWidth(0);
            flow.setMaxWidth(width);
            return;
        }
        if (node instanceof Label label) {
            label.setWrapText(true);
            label.setMinWidth(0);
            label.setMaxWidth(width);
            Node graphic = label.getGraphic();
            if (graphic != null) {
                constrain(graphic, width);
            }
            return;
        }
        if (node instanceof HBox box) {
            box.setMinWidth(0);
            box.setMaxWidth(width);
            double rest = width;
            for (Node child : box.getChildren()) {
                if (child instanceof Label prefix && prefix.getText() != null && prefix.getText().length() <= 4) {
                    rest = Math.max(1, width - 22);
                    continue;
                }
                constrain(child, rest);
            }
            return;
        }
        if (node instanceof VBox box) {
            box.setMinWidth(0);
            box.setMaxWidth(width);
            box.setFillWidth(true);
            for (Node child : box.getChildren()) {
                constrain(child, width);
            }
        }
    }

    /**
     * 将 MdNode 渲染为对应的 JavaFX Node。
     * 使用模式匹配 switch 表达式处理不同类型的节点。
     */
    private Node renderBlock(MdNode node) {
        return switch (node) {
            case MdNode.Heading h -> {
                TextFlow heading = flow(h.spans());
                heading.getStyleClass().add("md-h" + h.level());
                yield heading;
            }
            case MdNode.Paragraph p -> flow(p.spans());
            case MdNode.BulletList b -> listBox(b.items(), false);
            case MdNode.OrderedList o -> listBox(o.items(), true);
            case MdNode.Quote q -> {
                VBox box = new VBox(4);
                box.setMinWidth(0);
                box.getStyleClass().add("md-quote");
                for (MdNode child : q.children()) {
                    box.getChildren().add(renderBlock(child));
                }
                yield box;
            }
            case MdNode.FencedCode f -> {
                SelectableTextFlow code = SelectableTextFlow.forText(f.code());
                code.getStyleClass().add("md-code");
                code.setMinWidth(0);
                yield code;
            }
            case MdNode.Table t -> tableBox(t);
            case MdNode.ThematicBreak ignored -> {
                Label line = new Label("——");
                line.getStyleClass().add("md-hr");
                yield line;
            }
        };
    }

    /**
     * 渲染列表（有序或无序）。
     */
    private VBox listBox(List<List<MdNode>> items, boolean ordered) {
        VBox box = new VBox(2);
        box.setMinWidth(0);
        int i = 1;
        for (List<MdNode> item : items) {
            Label prefix = new Label(ordered ? i + ". " : "• ");
            prefix.setMinWidth(Region.USE_PREF_SIZE);
            prefix.setTextFill(INK);
            i++;
            VBox itemBody = new VBox(2);
            itemBody.setMinWidth(0);
            HBox.setHgrow(itemBody, Priority.ALWAYS);
            if (item.isEmpty()) {
                itemBody.getChildren().add(new Label(""));
            } else {
                for (MdNode child : item) {
                    itemBody.getChildren().add(renderBlock(child));
                }
            }
            HBox row = new HBox(4, prefix, itemBody);
            row.setMinWidth(0);
            box.getChildren().add(row);
        }
        return box;
    }

    /**
     * 渲染表格（简化为管道符分隔的文本格式）。
     */
    private VBox tableBox(MdNode.Table table) {
        VBox box = new VBox(2);
        box.setMinWidth(0);
        box.getStyleClass().add("md-table");
        for (List<List<MdSpan>> row : table.rows()) {
            List<MdSpan> spans = new java.util.ArrayList<>();
            boolean first = true;
            for (List<MdSpan> cell : row) {
                if (!first) {
                    spans.add(new MdSpan.Text(" | "));
                }
                first = false;
                spans.addAll(cell);
            }
            box.getChildren().add(flow(spans));
        }
        return box;
    }

    /**
     * 将 MdSpan 列表转换为 SelectableTextFlow 组件。
     */
    private SelectableTextFlow flow(List<MdSpan> spans) {
        List<SelectableTextFlow.Segment> segments = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        for (MdSpan span : spans) {
            collectSegments(span, segments, raw, false, false);
        }
        SelectableTextFlow sel = SelectableTextFlow.forSegments(segments, raw.toString(), onLinkClick);
        sel.setMinWidth(0);
        return sel;
    }

    /**
     * 递归收集 MdSpan 为 SelectableTextFlow.Segment。
     * 处理粗体、斜体、行内代码、链接等样式。
     */
    private void collectSegments(MdSpan span, List<SelectableTextFlow.Segment> out, StringBuilder raw,
                                  boolean bold, boolean italic) {
        switch (span) {
            case MdSpan.Text t -> {
                if (bold || italic) {
                    out.add(new SelectableTextFlow.Segment.Text(t.value(), bold, italic));
                } else {
                    out.add(new SelectableTextFlow.Segment.Text(t.value()));
                }
                raw.append(t.value());
            }
            case MdSpan.Strong s -> {
                for (MdSpan child : s.children()) collectSegments(child, out, raw, true, italic);
            }
            case MdSpan.Emphasis e -> {
                for (MdSpan child : e.children()) collectSegments(child, out, raw, bold, true);
            }
            case MdSpan.Code c -> {
                out.add(new SelectableTextFlow.Segment.Code(c.value()));
                raw.append(c.value());
            }
            case MdSpan.Link l -> {
                String text = linkText(l);
                out.add(new SelectableTextFlow.Segment.Link(text, l.dest()));
                raw.append(text);
            }
        }
    }

    /**
     * 从链接节点中提取显示文本。
     */
    private static String linkText(MdSpan.Link link) {
        StringBuilder sb = new StringBuilder();
        for (MdSpan child : link.children()) {
            if (child instanceof MdSpan.Text t) {
                sb.append(t.value());
            }
        }
        return sb.isEmpty() ? link.dest() : sb.toString();
    }

    /**
     * 打开链接：HTTP 链接用浏览器，.md 链接用知识库导航。
     */
    private void openLink(String dest) {
        if (dest == null || dest.isBlank()) {
            return;
        }
        if (dest.startsWith("http://") || dest.startsWith("https://")) {
            try {
                Desktop.getDesktop().browse(URI.create(dest));
            } catch (Exception ignored) {
            }
            return;
        }
        if (dest.endsWith(".md") && !dest.contains("://")) {
            onWorkspaceLink.accept(dest);
        }
    }
}
