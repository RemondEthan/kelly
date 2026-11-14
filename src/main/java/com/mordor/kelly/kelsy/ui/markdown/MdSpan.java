/**
 * Markdown 行内元素类型。
 *
 * <p>使用 Java 密封接口定义 Markdown 行内（inline）元素的类型层次。
 * 行内元素嵌套在 {@link MdNode} 的块级元素中，表示文本的样式和链接。
 *
 * <p>元素类型：
 * <ul>
 *   <li><b>Text</b> - 纯文本（无样式）</li>
 *   <li><b>Strong</b> - 粗体（**文本**）</li>
 *   <li><b>Emphasis</b> - 斜体（*文本*）</li>
 *   <li><b>Code</b> - 行内代码（`代码`）</li>
 *   <li><b>Link</b> - 超链接（[文本](URL)）</li>
 * </ul>
 *
 * <p>嵌套结构：
 * 粗体和斜体可以嵌套其他行内元素，例如：
 * **_粗斜体_** → Strong(children=[Emphasis(children=[Text("粗斜体")])])
 *
 * @see MdNode
 * @see MarkdownRenderer
 */
package com.mordor.kelly.kelsy.ui.markdown;

import java.util.List;

public sealed interface MdSpan {

    /**
     * 纯文本节点。
     *
     * @param value 文本内容
     */
    record Text(String value) implements MdSpan {
    }

    /**
     * 粗体节点（**文本**）。
     *
     * @param children 粗体内的行内元素（可嵌套）
     */
    record Strong(List<MdSpan> children) implements MdSpan {
    }

    /**
     * 斜体节点（*文本*）。
     *
     * @param children 斜体内的行内元素（可嵌套）
     */
    record Emphasis(List<MdSpan> children) implements MdSpan {
    }

    /**
     * 行内代码节点（`代码`）。
     *
     * @param value 代码文本
     */
    record Code(String value) implements MdSpan {
    }

    /**
     * 超链接节点（[文本](URL)）。
     *
     * @param dest     链接目标 URL
     * @param children 链接文本的行内元素
     */
    record Link(String dest, List<MdSpan> children) implements MdSpan {
    }
}
