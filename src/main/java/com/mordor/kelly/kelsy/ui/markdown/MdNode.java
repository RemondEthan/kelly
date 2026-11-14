/**
 * Markdown AST（抽象语法树）节点类型。
 *
 * <p>使用 Java 密封接口定义 Markdown 块级元素的类型层次。
 * 每种节点类型对应一种 Markdown 块级元素，包含其渲染所需的数据。
 *
 * <p>节点类型：
 * <ul>
 *   <li><b>Heading</b> - 标题（H1~H3，level 限制在 1-3）</li>
 *   <li><b>Paragraph</b> - 段落（包含行内元素）</li>
 *   <li><b>BulletList</b> - 无序列表</li>
 *   <li><b>OrderedList</b> - 有序列表</li>
 *   <li><b>Quote</b> - 块引用（可嵌套）</li>
 *   <li><b>FencedCode</b> - 围栏代码块（含语言标识）</li>
 *   <li><b>Table</b> - GFM 表格（行×列的单元格矩阵）</li>
 *   <li><b>ThematicBreak</b> - 水平分隔线</li>
 * </ul>
 *
 * <p>行内元素由 {@link MdSpan} 定义，嵌套在 Paragraph、Heading 等节点中。
 *
 * @see MdSpan
 * @see MarkdownRenderer
 * @see MarkdownView
 */
package com.mordor.kelly.kelsy.ui.markdown;

import java.util.List;

public sealed interface MdNode {

    /**
     * 标题节点。
     *
     * @param level 标题级别（1-3）
     * @param spans 标题内容的行内元素
     */
    record Heading(int level, List<MdSpan> spans) implements MdNode {
    }

    /**
     * 段落节点。
     *
     * @param spans 段落内容的行内元素
     */
    record Paragraph(List<MdSpan> spans) implements MdNode {
    }

    /**
     * 无序列表节点。
     *
     * @param items 列表项，每项包含嵌套的块级节点
     */
    record BulletList(List<List<MdNode>> items) implements MdNode {
    }

    /**
     * 有序列表节点。
     *
     * @param items 列表项，每项包含嵌套的块级节点
     */
    record OrderedList(List<List<MdNode>> items) implements MdNode {
    }

    /**
     * 块引用节点。
     *
     * @param children 引用内容的块级节点列表（支持嵌套）
     */
    record Quote(List<MdNode> children) implements MdNode {
    }

    /**
     * 围栏代码块节点。
     *
     * @param language 代码语言标识（如 "java"、"python"，可为空）
     * @param code     代码内容
     */
    record FencedCode(String language, String code) implements MdNode {
    }

    /**
     * GFM 表格节点。
     *
     * @param rows 表格行列表，每行包含列列表，每列包含行内元素
     */
    record Table(List<List<List<MdSpan>>> rows) implements MdNode {
    }

    /**
     * 水平分隔线节点（无数据）。
     */
    record ThematicBreak() implements MdNode {
    }
}
