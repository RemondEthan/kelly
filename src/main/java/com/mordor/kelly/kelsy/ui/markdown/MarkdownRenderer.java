/**
 * Markdown 渲染器。
 *
 * <p>将 Markdown 文本解析为 {@link MdNode} AST（抽象语法树）节点列表。
 * 基于 commonmark-java 库实现，支持 GitHub Flavored Markdown（GFM）表格扩展。
 *
 * <p>支持的 Markdown 元素：
 * <ul>
 *   <li><b>标题</b> - H1~H3（限制最大 3 级）</li>
 *   <li><b>段落</b> - 普通文本段落</li>
 *   <li><b>列表</b> - 有序列表和无序列表</li>
 *   <li><b>引用</b> - 块引用（嵌套）</li>
 *   <li><b>代码块</b> - 围栏代码块和缩进代码块</li>
 *   <li><b>表格</b> - GFM 表格（表头+表体）</li>
 *   <li><b>分隔线</b> - 水平分割线</li>
 *   <li><b>行内样式</b> - 粗体、斜体、行内代码、链接</li>
 * </ul>
 *
 * <p>渲染流程：
 * <pre>
 *   Markdown 文本
 *   → commonmark Parser.parse()
 *   → 遍历 AST 节点
 *   → mapBlock() 转换为 MdNode
 *   → collectInlines() 提取行内元素为 MdSpan
 *   → List&lt;MdNode&gt; 返回给 MarkdownView 渲染
 * </pre>
 *
 * <p>使用示例：
 * <pre>
 *   List&lt;MdNode&gt; nodes = MarkdownRenderer.parse("# 标题\n\n正文内容");
 *   MarkdownView view = new MarkdownView(nodes, onLinkClick);
 * </pre>
 *
 * @see MdNode
 * @see MdSpan
 * @see MarkdownView
 */
package com.mordor.kelly.kelsy.ui.markdown;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

public final class MarkdownRenderer {

    /** commonmark 解析器实例（启用 GFM 表格扩展） */
    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    /** 私有构造函数，防止实例化 */
    private MarkdownRenderer() {
    }

    /**
     * 解析 Markdown 文本为 MdNode 节点列表。
     *
     * @param source Markdown 源文本
     * @return 解析后的节点列表，解析失败时返回纯文本段落
     */
    public static List<MdNode> parse(String source) {
        try {
            Node doc = PARSER.parse(source == null ? "" : source);
            List<MdNode> nodes = new ArrayList<>();
            for (Node child = doc.getFirstChild(); child != null; child = child.getNext()) {
                MdNode mapped = mapBlock(child);
                if (mapped != null) {
                    nodes.add(mapped);
                }
            }
            return nodes;
        } catch (RuntimeException e) {
            // 解析失败时回退到纯文本
            return List.of(new MdNode.Paragraph(List.of(new MdSpan.Text(source == null ? "" : source))));
        }
    }

    /**
     * 将 commonmark 块节点转换为 MdNode。
     * 使用 Java 17 模式匹配 switch 表达式。
     */
    private static MdNode mapBlock(Node node) {
        return switch (node) {
            case Heading h -> new MdNode.Heading(Math.min(3, Math.max(1, h.getLevel())), inlines(h));
            case Paragraph p -> new MdNode.Paragraph(inlines(p));
            case BulletList b -> new MdNode.BulletList(listItems(b));
            case OrderedList o -> new MdNode.OrderedList(listItems(o));
            case BlockQuote q -> {
                List<MdNode> children = new ArrayList<>();
                for (Node c = q.getFirstChild(); c != null; c = c.getNext()) {
                    MdNode mapped = mapBlock(c);
                    if (mapped != null) {
                        children.add(mapped);
                    }
                }
                yield new MdNode.Quote(children);
            }
            case FencedCodeBlock f -> new MdNode.FencedCode(
                    f.getInfo() == null ? "" : f.getInfo(),
                    f.getLiteral() == null ? "" : f.getLiteral());
            case IndentedCodeBlock i -> new MdNode.FencedCode("", i.getLiteral() == null ? "" : i.getLiteral());
            case TableBlock t -> mapTable(t);
            case ThematicBreak ignored -> new MdNode.ThematicBreak();
            case HtmlBlock ignored -> null;
            default -> null;
        };
    }

    /**
     * 将 GFM 表格节点转换为 MdNode.Table。
     */
    private static MdNode.Table mapTable(TableBlock table) {
        List<List<List<MdSpan>>> rows = new ArrayList<>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            if (!(section instanceof TableHead) && !(section instanceof TableBody)) {
                continue;
            }
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) {
                    continue;
                }
                List<List<MdSpan>> cells = new ArrayList<>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell tc) {
                        cells.add(inlines(tc));
                    }
                }
                rows.add(cells);
            }
        }
        return new MdNode.Table(rows);
    }

    /**
     * 提取列表项的子节点。
     */
    private static List<List<MdNode>> listItems(Node list) {
        List<List<MdNode>> items = new ArrayList<>();
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            List<MdNode> blocks = new ArrayList<>();
            for (Node c = item.getFirstChild(); c != null; c = c.getNext()) {
                MdNode mapped = mapBlock(c);
                if (mapped != null) {
                    blocks.add(mapped);
                }
            }
            items.add(blocks);
        }
        return items;
    }

    /**
     * 提取父节点的所有行内元素为 MdSpan 列表。
     */
    private static List<MdSpan> inlines(Node parent) {
        List<MdSpan> spans = new ArrayList<>();
        collectInlines(parent, spans);
        return spans;
    }

    /**
     * 递归收集行内元素。
     * 处理文本、粗体、斜体、行内代码、链接、换行等。
     */
    private static void collectInlines(Node parent, List<MdSpan> out) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            switch (n) {
                case Text t -> out.add(new MdSpan.Text(t.getLiteral() == null ? "" : t.getLiteral()));
                case StrongEmphasis s -> out.add(new MdSpan.Strong(inlines(s)));
                case Emphasis e -> out.add(new MdSpan.Emphasis(inlines(e)));
                case Code c -> out.add(new MdSpan.Code(c.getLiteral() == null ? "" : c.getLiteral()));
                case Link l -> out.add(new MdSpan.Link(l.getDestination() == null ? "" : l.getDestination(), inlines(l)));
                case SoftLineBreak ignored -> out.add(new MdSpan.Text("\n"));
                case HardLineBreak ignored -> out.add(new MdSpan.Text("\n"));
                case HtmlInline ignored -> {
                }
                case Image ignored -> {
                }
                default -> collectInlines(n, out);
            }
        }
    }
}
