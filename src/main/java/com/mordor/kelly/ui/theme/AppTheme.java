package com.mordor.kelly.ui.theme;

/**
 * 界面主题。
 *
 * <p>目前提供亮色与深色两套。主题本身不携带任何样式，
 * 只是给 {@link ThemeService} 一个标识：深色主题对应给场景根节点
 * 加上 {@code theme-dark} 样式类，CSS 色板随之切换。</p>
 */
public enum AppTheme {

    /** 亮色主题（默认） */
    LIGHT("浅色"),

    /** 深色主题 */
    DARK("深色");

    /** 中文名称，用于按钮文案 */
    private final String label;

    AppTheme(String label) {
        this.label = label;
    }

    /**
     * 中文名称。
     *
     * @return 主题的中文名称
     */
    public String label() {
        return label;
    }

    /**
     * 切换到另一个主题。
     *
     * @return 与当前主题相反的主题
     */
    public AppTheme toggled() {
        return this == LIGHT ? DARK : LIGHT;
    }
}
