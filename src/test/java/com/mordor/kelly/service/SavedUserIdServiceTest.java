package com.mordor.kelly.service;

import com.mordor.kelly.kelsy.KelsyRoomSettingsTest.MemoryPrefs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SavedUserIdServiceTest {

    @Test
    void isolatesByImCodeAndUsernameAndOverwrites() {
        MemoryPrefs prefs = new MemoryPrefs();
        SavedUserIdService ids = new SavedUserIdService(prefs);
        assertEquals(0, ids.get("ROOM", "Ada"));
        ids.put("ROOM", "Ada", 200003);
        ids.put("ROOM", "Bob", 200004);
        ids.put("OTHER", "Ada", 200010);
        assertEquals(200003, ids.get("ROOM", "Ada"));
        assertEquals(200004, ids.get("ROOM", "Bob"));
        assertEquals(200010, ids.get("OTHER", "Ada"));
        ids.put("ROOM", "Ada", 200099);
        assertEquals(200099, ids.get("ROOM", "Ada"));
    }
}
