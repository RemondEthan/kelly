package com.mordor.kelly.service;

/**
 * 待发送图片草稿
 *
 * 本类表示用户选择但尚未发送的图片
 * 包含图片的原始数据、MIME 类型和说明文字
 *
 * 使用 Java Record 类实现，提供不可变的数据对象
 * 构造时会自动处理空值，设置默认值
 */
public record ImageDraft(
        /**
         * 图片原始字节数据
         * 如果为 null，会设置为空数组
         */
        byte[] bytes,

        /**
         * MIME 类型
         * 例如：image/png, image/jpeg, image/gif, image/webp
         * 如果为 null 或空白，会设置为 "image/png"
         */
        String mime,

        /**
         * 图片说明文字（可选）
         * 如果为 null，会设置为空字符串
         */
        String caption
) {
    /**
     * 紧凑构造方法
     * 自动处理空值，设置合理的默认值
     */
    public ImageDraft {
        bytes = bytes == null ? new byte[0] : bytes;
        mime = mime == null || mime.isBlank() ? "image/png" : mime;
        caption = caption == null ? "" : caption;
    }
}
