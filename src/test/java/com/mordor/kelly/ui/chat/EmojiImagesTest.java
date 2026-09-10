package com.mordor.kelly.ui.chat;

import com.mordor.kelly.ui.chat.EmojiImages.FlowParts;
import javafx.scene.image.ImageView;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmojiImagesTest {

    @Test
    void flowWithMap_textOnly_returnsSingleTextOffsetZero() {
        FlowParts parts = EmojiImages.flowWithMap("hello");
        assertEquals(1, parts.flow().getChildren().size());
        assertEquals(0, parts.charOffsets()[0]);
        assertEquals("hello", ((Text) parts.flow().getChildren().get(0)).getText());
    }

    @Test
    void flowWithMap_empty_returnsEmptyFlowAndEmptyOffsets() {
        FlowParts parts = EmojiImages.flowWithMap("");
        assertEquals(0, parts.flow().getChildren().size());
        assertEquals(0, parts.charOffsets().length);
    }

    @Test
    void flowWithMap_offsetsAlignWithRaw() {
        // "a🍕b" — 1 char "a" + 1 emoji (2 UTF-16 code units) + 1 char "b"
        // raw 长度 4；emoji 在 raw offset 1（code unit 偏移）
        String raw = "a🍕b";
        FlowParts parts = EmojiImages.flowWithMap(raw);
        // 子节点：Text("a") + ImageView(🍕) + Text("b")
        // charOffsets：每个子节点对应 raw 中的起始偏移（UTF-16 code unit）
        assertEquals(3, parts.flow().getChildren().size());
        assertEquals(0, parts.charOffsets()[0]);
        assertEquals(1, parts.charOffsets()[1]); // emoji 占 [1, 3)
        assertEquals(3, parts.charOffsets()[2]);
        // 用 charOffsets + raw 重构"a🍕" = raw.substring(0, 3)
        assertEquals("a🍕", raw.substring(parts.charOffsets()[0], parts.charOffsets()[2]));
        // 还原 "🍕b" = raw.substring(1, 4)
        assertEquals("🍕b", raw.substring(parts.charOffsets()[1], raw.length()));
    }

    @Test
    void flowWithMap_consecutiveEmoji_eachTakesOneSlot() {
        String raw = "🍕🍕";
        FlowParts parts = EmojiImages.flowWithMap(raw);
        // 两个 ImageView
        assertTrue(parts.flow().getChildren().get(0) != null);
        assertEquals(0, parts.charOffsets()[0]);
        assertEquals(2, parts.charOffsets()[1]); // 每个 emoji 占 2 code units
    }

    @Test
    void flowWithMap_recognizesGreenCircle() {
        // 在线状态指示器：ChatHeader 把 🟢 拼到用户名后面,
        // Windows 上必须能被识别为 emoji 节点才能用 Twemoji PNG 渲染
        String raw = "u 🟢";
        FlowParts parts = EmojiImages.flowWithMap(raw);
        // Text("u ") + ImageView(🟢)
        assertEquals(2, parts.flow().getChildren().size());
        assertEquals("u ", ((Text) parts.flow().getChildren().get(0)).getText());
        assertTrue(parts.flow().getChildren().get(1) instanceof ImageView,
                "🟢 应该被识别为 ImageView，否则 Windows 渲染会乱码");
        // "u " 占 2 code units，🟢 在 raw offset 2
        assertEquals(0, parts.charOffsets()[0]);
        assertEquals(2, parts.charOffsets()[1]);
    }

    @Test
    void flowWithMap_recognizesRedCircle() {
        String raw = "u 🔴";
        FlowParts parts = EmojiImages.flowWithMap(raw);
        assertEquals(2, parts.flow().getChildren().size());
        assertEquals("u ", ((Text) parts.flow().getChildren().get(0)).getText());
        assertTrue(parts.flow().getChildren().get(1) instanceof ImageView,
                "🔴 应该被识别为 ImageView，否则 Windows 渲染会乱码");
        assertEquals(0, parts.charOffsets()[0]);
        assertEquals(2, parts.charOffsets()[1]);
    }
}
