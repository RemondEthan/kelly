package com.mordor.kelly.model;

/**
 * 聊天室成员模型。
 * 使用 Java record 定义，表示聊天室中的一个成员。
 * 包含成员的用户ID、用户名和是否为自己的标志。
 * 
 * 主要功能：
 * - 存储成员的基本信息
 * - 提供特殊成员的常量定义（自己、AI 助手）
 * - 提供工厂方法创建 AI 助手成员
 * - 提供判断是否为 AI 助手的方法
 * 
 * @param userId 用户ID，唯一标识成员
 * @param username 用户名/昵称
 * @param self 是否为当前用户自己
 */
public record RoomMember(int userId, String username, boolean self) {
    /**
     * 自己的用户ID常量。
     * 使用 -1 作为自己的标识，表示当前登录用户。
     */
    public static final int SELF_ID = -1;

    /**
     * AI 助手的用户ID常量。
     * 使用固定的 100778 作为 AI 助手的标识。
     */
    public static final int KELSY_ID = 100778;

    /**
     * AI 助手的默认昵称。
     * 当未设置自定义昵称时，使用 "tars" 作为显示名。
     * 注意：这不是提及硬编码，而是默认的显示名。
     */
    public static final String SECRETARY_NAME = "tars";

    /**
     * 工厂方法，创建使用默认昵称的 AI 助手成员。
     * 等价于 kelsy(SECRETARY_NAME)。
     * 
     * @return AI 助手成员实例
     */
    public static RoomMember kelsy() {
        return kelsy(SECRETARY_NAME);
    }

    /**
     * 工厂方法，创建指定昵称的 AI 助手成员。
     * 如果提供的昵称为 null 或空白，则使用默认昵称 SECRETARY_NAME。
     * 
     * @param nickname 自定义昵称（可为 null 或空白）
     * @return AI 助手成员实例
     */
    public static RoomMember kelsy(String nickname) {
        String n = nickname == null || nickname.isBlank() ? SECRETARY_NAME : nickname.strip();
        return new RoomMember(KELSY_ID, n, false);
    }

    /**
     * 检查此成员是否为 AI 助手。
     * 通过比较 userId 是否等于 KELSY_ID 来判断。
     * 
     * @return 如果是 AI 助手返回 true，否则返回 false
     */
    public boolean isKelsy() {
        return userId == KELSY_ID;
    }
}