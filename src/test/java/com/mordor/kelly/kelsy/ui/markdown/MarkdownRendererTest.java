package com.mordor.kelly.kelsy.ui.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownRendererTest {

    @Test
    void headingAndList() {
        var nodes = MarkdownRenderer.parse("## 标题\n\n- 甲\n- 乙\n");
        assertInstanceOf(MdNode.Heading.class, nodes.get(0));
        assertEquals(2, ((MdNode.Heading) nodes.get(0)).level());
        assertInstanceOf(MdNode.BulletList.class, nodes.get(1));
    }

    @Test
    void strongAndCode() {
        var nodes = MarkdownRenderer.parse("这是 **粗** 和 `x`");
        var p = (MdNode.Paragraph) nodes.get(0);
        assertTrue(p.spans().stream().anyMatch(s -> s instanceof MdSpan.Strong));
        assertTrue(p.spans().stream().anyMatch(s -> s instanceof MdSpan.Code));
    }

    @Test
    void linkKept() {
        var nodes = MarkdownRenderer.parse("[人](knowledge/people/a.md)");
        var p = (MdNode.Paragraph) nodes.get(0);
        var link = (MdSpan.Link) p.spans().stream().filter(s -> s instanceof MdSpan.Link).findFirst().orElseThrow();
        assertEquals("knowledge/people/a.md", link.dest());
    }

    @Test
    void fence() {
        var nodes = MarkdownRenderer.parse("```java\nint a;\n```\n");
        var code = (MdNode.FencedCode) nodes.get(0);
        assertEquals("java", code.language());
        assertTrue(code.code().contains("int a"));
    }

    @Test
    void table() {
        var nodes = MarkdownRenderer.parse("| a | b |\n| --- | --- |\n| 1 | 2 |\n");
        assertInstanceOf(MdNode.Table.class, nodes.get(0));
    }

    @Test
    void rawHtmlIsNotANode() {
        var nodes = MarkdownRenderer.parse("hello <script>x</script>");
        String flat = nodes.toString();
        assertTrue(flat.contains("hello"));
        assertTrue(!flat.contains("MdNode.Html") && !flat.toLowerCase().contains("script>"));
    }
}
