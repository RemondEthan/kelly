package com.mordor.kelly.model;

public record RoomMember(int userId, String username, boolean self) {
    public static final int SELF_ID = -1;
    public static final int KELSY_ID = 100778;
    /** 未设置昵称时的默认显示名，不是提及硬编码。 */
    public static final String SECRETARY_NAME = "tars";

    public static RoomMember kelsy() {
        return kelsy(SECRETARY_NAME);
    }

    public static RoomMember kelsy(String nickname) {
        String n = nickname == null || nickname.isBlank() ? SECRETARY_NAME : nickname.strip();
        return new RoomMember(KELSY_ID, n, false);
    }

    public boolean isKelsy() {
        return userId == KELSY_ID;
    }
}
