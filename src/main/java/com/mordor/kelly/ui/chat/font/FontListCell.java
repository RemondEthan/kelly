package com.mordor.kelly.ui.chat.font;

import com.mordor.kelly.ui.theme.ThemeService;
import javafx.scene.control.ListCell;

/**
 * 字体下拉列表的单元格。
 *
 * <p><strong>为什么单独成类</strong>：{@link ListCell} 是复用控件，
 * {@code updateItem} 会在滚动、选中、重排时反复调用。样式类必须
 * <strong>幂等</strong>追加——否则同一个类会重复累积到几千份，
 * 而 JavaFX 每次样式类列表变化都会触发 {@code reapplyCSS()}，
 * CSS 匹配成本随之爆炸（本机两千多个字体族时，下拉框一展开就长时间卡死）。</p>
 */
final class FontListCell extends ListCell<String> {

    /** 主题列表项的样式类名（对应 theme.css 里的 {@code .theme-list-cell}） */
    static final String STYLE_CLASS = "theme-list-cell";

    @Override
    protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        // 幂等追加：复用 cell 上重复调用不会累积重复项
        ThemeService.addStyleClass(this, STYLE_CLASS);
        setText(empty ? null : item);
    }
}
