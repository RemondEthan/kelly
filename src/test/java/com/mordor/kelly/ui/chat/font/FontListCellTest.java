package com.mordor.kelly.ui.chat.font;

import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：字体下拉列表项的样式类必须幂等追加。
 *
 * <p>{@link javafx.scene.control.ListCell} 是复用控件，{@code updateItem}
 * 在滚动、选中、重新布局时会被反复调用。如果每次调用都往
 * {@code getStyleClass()} 里塞同一个类，重复项会累积到几千个；JavaFX
 * 每次样式类列表变化都会触发 {@code reapplyCSS()}，CSS 匹配成本随之爆炸
 * ——本机两千多个字体族时，下拉框一展开就长时间卡死。</p>
 */
class FontListCellTest {


    @BeforeAll
    static void startToolkit() throws Exception {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException already) {
        }
    }

    // JavaFX is process-scoped; do not exit it between test classes.

    /**
     * 模拟 ListCell 被反复复用：多次调用 updateItem 后，
     * 样式类列表里仍然只有一份 {@code theme-list-cell}。
     */
    @Test
    void repeatedUpdateItem_doesNotAccumulateStyleClass() {
        FontListCell cell = new FontListCell();

        for (int i = 0; i < 500; i++) {
            cell.updateItem("font-" + i, false);
        }

        int occurrences = 0;
        for (String sc : cell.getStyleClass()) {
            if (FontListCell.STYLE_CLASS.equals(sc)) {
                occurrences++;
            }
        }
        assertEquals(1, occurrences,
                "theme-list-cell must appear exactly once after repeated updateItem, got "
                        + occurrences + " in " + cell.getStyleClass());
    }

    /**
     * 样式类不能因为去重而丢失（去重是为了幂等，不是为了不生效）。
     */
    @Test
    void updateItem_appliesStyleClass() {
        FontListCell cell = new FontListCell();
        cell.updateItem("DejaVu Sans", false);

        assertTrue(cell.getStyleClass().contains(FontListCell.STYLE_CLASS),
                "style class must be applied, got " + cell.getStyleClass());
        assertEquals("DejaVu Sans", cell.getText(), "cell text must show the font family");
    }

    /**
     * 空项也要带上样式类（否则空行在深色主题下没有底色）。
     */
    @Test
    void emptyItem_stillAppliesStyleClass() {
        FontListCell cell = new FontListCell();
        cell.updateItem(null, true);

        assertTrue(cell.getStyleClass().contains(FontListCell.STYLE_CLASS),
                "empty cell must still carry the style class, got " + cell.getStyleClass());
        assertEquals(null, cell.getText(), "empty cell must show no text");
    }

    /**
     * 样式类列表长度不应随 updateItem 调用次数增长。
     */
    @Test
    void styleClassListLength_doesNotGrowWithUpdateCount() {
        FontListCell cell = new FontListCell();
        cell.updateItem("a", false);
        int sizeAfterFirst = cell.getStyleClass().size();

        for (int i = 0; i < 200; i++) {
            cell.updateItem("font-" + i, false);
        }

        assertEquals(sizeAfterFirst, cell.getStyleClass().size(),
                "style class list size must stay constant, was " + sizeAfterFirst
                        + " now " + cell.getStyleClass().size());
    }
}
