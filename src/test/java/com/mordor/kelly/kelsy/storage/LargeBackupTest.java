package com.mordor.kelly.kelsy.storage;

import com.mordor.kelly.kelsy.service.KnowledgeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class LargeBackupTest {
    @TempDir Path root;
    @Test void archivesLargerThanOldEntryLimitRoundTrip() throws Exception {
        Path user=root.resolve("user"),zip=root.resolve("backup.zip"),restored=root.resolve("restored");
        try(var store=new KnowledgeStore(user)) {
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+user.resolve(".kelly-data.db"));var s=c.createStatement()) {
                s.execute("INSERT INTO app_meta VALUES('large',zeroblob(65*1024*1024))");
            }
            HybridBackupService.backup(store,zip);
        }
        HybridBackupService.restore(zip,restored);
        try(var c=DatabaseBootstrap.open(restored,false);var s=c.createStatement();var r=s.executeQuery("SELECT length(value) FROM app_meta WHERE key='large'")) {
            assertTrue(r.next());assertEquals(65*1024*1024,r.getLong(1));
        }
    }
}
