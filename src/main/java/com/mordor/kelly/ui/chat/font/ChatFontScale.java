package com.mordor.kelly.ui.chat.font;

/**
 * 聊天字体缩放等级枚举。
 *
 * <p>定义四个预设缩放等级，每个等级对应一个缩放因子（factor），
 * 用于 {@link ChatFontApplier} 在遍历 UI 节点树时按比例调整字号。</p>
 *
 * <h3>缩放因子说明</h3>
 * <ul>
 *   <li>{@link #SMALL} (0.9) - 小号：缩小 10%</li>
 *   <li>{@link #MEDIUM} (1.0) - 中号：原始大小（默认）</li>
 *   <li>{@link #LARGE} (1.1) - 大号：放大 10%</li>
 *   <li>{@link #XLARGE} (1.2) - 特大号：放大 20%</li>
 * </ul>
 *
 * <p>使用方式：{@code double scaledSize = originalSize * ChatFontScale.LARGE.factor();}</p>
 */
public enum ChatFontScale {
    /** 小号字体，缩放因子 0.9 */
    SMALL(0.9),
    /** 中号字体（默认），缩放因子 1.0 */
    MEDIUM(1.0),
    /** 大号字体，缩放因子 1.1 */
    LARGE(1.1),
    /** 特大号字体，缩放因子 1.2 */
    XLARGE(1.2);

    /** 缩放因子：乘以原始字号得到目标字号 */
    private final double factor;

    ChatFontScale(double factor) {
        this.factor = factor;
    }

    /**
     * 获取缩放因子。
     *
     * @return 缩放因子值（如 0.9、1.0、1.1、1.2）
     */
    public double factor() {
        return factor;
    }
}