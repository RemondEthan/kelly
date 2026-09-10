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

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    private MarkdownRenderer() {
    }

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
            return List.of(new MdNode.Paragraph(List.of(new MdSpan.Text(source == null ? "" : source))));
        }
    }

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

    private static List<MdSpan> inlines(Node parent) {
        List<MdSpan> spans = new ArrayList<>();
        collectInlines(parent, spans);
        return spans;
    }

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
