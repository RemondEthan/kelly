package com.mordor.kelly.ui.chat.font;

/**
 * 聊天字体设置的不可变数据模型。
 *
 * <p>使用 Java 16+ 的 {@code record} 特性定义，自动生成构造器、getter、equals/hashCode/toString。</p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@link #fontFamily} - 字体族名称（如 "Microsoft YaHei"），null 表示使用系统默认字体</li>
 *   <li>{@link #scale} - 字体缩放等级，参见 {@link ChatFontScale}</li>
 * </ul>
 *
 * <p>持久化由 {@link ChatFontSettingsService} 负责，存储到 Java Preferences 中。</p>
 *
 * @param fontFamily 字体族名称，可为 null（使用系统默认）
 * @param scale      字体缩放等级
 */
public record ChatFontSettings(String fontFamily, ChatFontScale scale) {

    /**
     * 返回默认字体设置：使用系统默认字体族，中号缩放。
     *
     * @return 默认字体设置实例
     */
    public static ChatFontSettings defaults() {
        return new ChatFontSettings(null, ChatFontScale.MEDIUM);
    }
}