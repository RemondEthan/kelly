package com.mordor.kelly.kelsy.ui.markdown;

import java.util.List;

public sealed interface MdNode {

    record Heading(int level, List<MdSpan> spans) implements MdNode {
    }

    record Paragraph(List<MdSpan> spans) implements MdNode {
    }

    record BulletList(List<List<MdNode>> items) implements MdNode {
    }

    record OrderedList(List<List<MdNode>> items) implements MdNode {
    }

    record Quote(List<MdNode> children) implements MdNode {
    }

    record FencedCode(String language, String code) implements MdNode {
    }

    record Table(List<List<List<MdSpan>>> rows) implements MdNode {
    }

    record ThematicBreak() implements MdNode {
    }
}
