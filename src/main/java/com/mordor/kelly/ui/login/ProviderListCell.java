package com.mordor.kelly.ui.login;

import com.mordor.kelly.kelsy.provider.ProviderSpec;
import com.mordor.kelly.ui.theme.ThemeService;
import javafx.scene.control.ListCell;

/**
 * 服务商下拉列表的单元格。
 *
 * <p>与字体列表单元格同理：{@link ListCell} 会被复用，
 * {@code updateItem} 反复触发，样式类必须幂等追加，否则重复项会累积
 * 到几千份，让 JavaFX 的 CSS 重刷成本爆炸。</p>
 */
final class ProviderListCell extends ListCell<ProviderSpec> {

    /** 主题列表项的样式类名（对应 theme.css 里的 {@code .theme-list-cell}） */
    static final String STYLE_CLASS = "theme-list-cell";

    @Override
    protected void updateItem(ProviderSpec s, boolean empty) {
        super.updateItem(s, empty);
        // 幂等追加：复用 cell 上重复调用不会累积重复项
        ThemeService.addStyleClass(this, STYLE_CLASS);
        setText(empty || s == null ? null : s.displayName());
    }
}
