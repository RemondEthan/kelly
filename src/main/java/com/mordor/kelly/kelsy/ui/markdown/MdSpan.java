package com.mordor.kelly.kelsy.ui.markdown;

import java.util.List;

public sealed interface MdSpan {

    record Text(String value) implements MdSpan {
    }

    record Strong(List<MdSpan> children) implements MdSpan {
    }

    record Emphasis(List<MdSpan> children) implements MdSpan {
    }

    record Code(String value) implements MdSpan {
    }

    record Link(String dest, List<MdSpan> children) implements MdSpan {
    }
}
