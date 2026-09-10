package com.mordor.kelly.ui.chat;

import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelectableTextFlowTest {

    @Test
    void forText_returnsFocusableTextFlow() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        assertTrue(flow.isFocusTraversable(), "TextFlow 默认可聚焦才能显示选区");
    }

    @Test
    void forText_empty_returnsFlowWithNoChildren() {
        SelectableTextFlow flow = SelectableTextFlow.forText("");
        assertEquals(0, contentCount(flow));
    }

    @Test
    void forText_emojiSplitIntoImageView() {
        // "a🍕b" → Text("a") + ImageView(🍕) + Text("b")
        SelectableTextFlow flow = SelectableTextFlow.forText("a🍕b");
        assertEquals(3, contentCount(flow));
        assertInstanceOf(Text.class, flow.getChildren().get(0));
        assertInstanceOf(ImageView.class, flow.getChildren().get(1));
        assertInstanceOf(Text.class, flow.getChildren().get(2));
    }

    @Test
    void forText_addsSelectableStyleClass() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        assertTrue(flow.getStyleClass().contains("selectable-text-flow"));
    }

    @Test
    void currentRawSubstring_noSelection_returnsNull() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        assertNull(flow.currentRawSubstring());
    }

    @Test
    void currentRawSubstring_partialSelection_returnsSubstring() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello world");
        // 只有一个 Text 子节点,选 "hello"
        Text text = (Text) flow.getChildren().get(0);
        text.setSelectionStart(0);
        text.setSelectionEnd(5);
        assertEquals("hello", flow.currentRawSubstring());
    }

    @Test
    void currentRawSubstring_acrossEmojiImageView() {
        SelectableTextFlow flow = SelectableTextFlow.forText("a🍕b");
        // Text("a") 在 idx 0, ImageView 在 idx 1 (不参与), Text("b") 在 idx 2
        // 选 "a" + 整段 + "b" → raw[0..4) = "a🍕b"
        Text a = (Text) flow.getChildren().get(0);
        Text b = (Text) flow.getChildren().get(2);
        a.setSelectionStart(0);
        a.setSelectionEnd(1);
        b.setSelectionStart(0);
        b.setSelectionEnd(1);
        assertEquals("a🍕b", flow.currentRawSubstring());
    }

    @Test
    void currentRawSubstring_onlySecondTextSelected() {
        // 回归测试: 只选非首节点时,不应错误包含起始内容。
        // 增量合并逻辑在 mergedStart=0(合法)与 "未初始化"(非法)之间无法区分,
        // 正确做法是监听器直接全量重算,而不是 merge。
        // "abc🍕def" → Text("abc") + ImageView + Text("def")
        SelectableTextFlow flow = SelectableTextFlow.forText("abc🍕def");
        Text def = (Text) flow.getChildren().get(2);
        def.setSelectionStart(0);
        def.setSelectionEnd(3);
        assertEquals("def", flow.currentRawSubstring());
    }

    @Test
    void forSegments_textAndCodeRenderAsTextNodes() {
        List<SelectableTextFlow.Segment> segments = List.of(
                new SelectableTextFlow.Segment.Text("你好 "),
                new SelectableTextFlow.Segment.Code("println()"),
                new SelectableTextFlow.Segment.Text(" 世界"));
        SelectableTextFlow flow = SelectableTextFlow.forSegments(segments, "你好 println() 世界");
        // 全部 Text 节点（另有一层选区高亮 Path，不计入）
        assertEquals(3, contentCount(flow));
        assertInstanceOf(Text.class, flow.getChildren().get(0));
        assertInstanceOf(Text.class, flow.getChildren().get(1));
        assertInstanceOf(Text.class, flow.getChildren().get(2));
        // Code 节点带 md-inline-code 样式
        Text codeText = (Text) flow.getChildren().get(1);
        assertTrue(codeText.getStyleClass().contains("md-inline-code"));
    }

    @Test
    void forSegments_linkHasClickHandler() {
        var clickedDest = new String[]{null};
        List<SelectableTextFlow.Segment> segments = List.of(
                new SelectableTextFlow.Segment.Link("点我", "https://example.com"));
        SelectableTextFlow flow = SelectableTextFlow.forSegments(
                segments, "点我", dest -> clickedDest[0] = dest);
        Text linkText = (Text) flow.getChildren().get(0);
        assertNotNull(linkText.getOnMouseClicked(), "Link 必须注册点击回调");
        // 模拟点击
        linkText.getOnMouseClicked().handle(null);
        assertEquals("https://example.com", clickedDest[0]);
    }

    @Test
    void setText_rebuildsChildren() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        assertEquals(1, contentCount(flow));
        flow.setText("world");
        // Text 节点数变化(emoji 拆分可能不同,这里两个都是纯文本 → 都是 1)
        assertEquals(1, contentCount(flow));
        assertEquals("world", ((Text) flow.getChildren().get(0)).getText());
    }

    @Test
    void setText_toEmpty_clearsChildren() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        flow.setText("");
        assertEquals(0, contentCount(flow));
    }

    @Test
    void setText_replacesRaw() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        flow.setText("world");
        // 验证 raw 已更新: 选 "world" 后复制应得 "world"
        Text text = (Text) flow.getChildren().get(0);
        text.setSelectionStart(0);
        text.setSelectionEnd(5);
        assertEquals("world", flow.currentRawSubstring());
    }

    @Test
    void textProperty_bindingUpdatesText() {
        var prop = new javafx.beans.property.SimpleStringProperty("foo");
        SelectableTextFlow flow = SelectableTextFlow.forText("foo");
        flow.textProperty().bind(prop);
        prop.set("bar");
        assertEquals("bar", ((Text) flow.getChildren().get(0)).getText());
    }

    @Test
    void applyRawSelection_partialText() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello world");
        flow.applyRawSelection(0, 5);
        assertEquals("hello", flow.currentRawSubstring());
        Text text = (Text) flow.getChildren().get(0);
        assertEquals(0, text.getSelectionStart());
        assertEquals(5, text.getSelectionEnd());
    }

    @Test
    void applyRawSelection_acrossEmojiIncludesUnicode() {
        SelectableTextFlow flow = SelectableTextFlow.forText("a🍕b");
        flow.applyRawSelection(0, "a🍕b".length());
        assertEquals("a🍕b", flow.currentRawSubstring());
        Text a = (Text) flow.getChildren().get(0);
        Text b = (Text) flow.getChildren().get(2);
        assertEquals(0, a.getSelectionStart());
        assertEquals(1, a.getSelectionEnd());
        assertEquals(0, b.getSelectionStart());
        assertEquals(1, b.getSelectionEnd());
    }

    @Test
    void applyRawSelection_onlyTrailingText() {
        SelectableTextFlow flow = SelectableTextFlow.forText("abc🍕def");
        int start = "abc🍕".length();
        flow.applyRawSelection(start, start + 3);
        assertEquals("def", flow.currentRawSubstring());
    }

    @Test
    void applyRawSelection_emptyClears() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        flow.applyRawSelection(0, 5);
        flow.applyRawSelection(2, 2);
        assertNull(flow.currentRawSubstring());
    }

    @Test
    void selectedGlyphFillStaysDark() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hello");
        Text text = (Text) flow.getChildren().get(0);
        assertEquals(SelectableTextFlow.SELECTION_GLYPH, text.getSelectionFill());
    }

    @Test
    void setText_longerThanOriginal_selectionMapsToNewRaw() {
        SelectableTextFlow flow = SelectableTextFlow.forText("hi");
        flow.setText("hello 🍕 world");
        flow.applyRawSelection(0, 5);
        assertEquals("hello", flow.currentRawSubstring());
    }

    private static long contentCount(SelectableTextFlow flow) {
        return flow.getChildren().stream()
                .filter(n -> n instanceof Text || n instanceof ImageView)
                .count();
    }
}
