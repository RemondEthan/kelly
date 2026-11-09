package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SyncRecoveryTest {
    @TempDir Path root;
    @Test void watcherFailureIsVisibleAndPeriodicRepairStillFindsExternalFiles() throws Exception {
        var attempts=new java.util.concurrent.atomic.AtomicInteger();var fail=new AtomicBoolean(true);
        try(var index=KnowledgeIndex.open(root);
            var sync=new KnowledgeSyncService(root,index,()->{
                attempts.incrementAndGet();if(fail.get())throw new java.io.IOException("injected watcher failure");
                return root.getFileSystem().newWatchService();
            },Duration.ofMillis(10),Duration.ofMillis(40),Duration.ofMillis(30))){
            sync.start();await(()->index.status().errors().containsKey("<watch>"));
            new MarkdownDocumentStore(root).create("knowledge/a.md","Title","article",List.of(),List.of(),null,"alpha");
            await(()->!index.search(new FindQuery(null,null,List.of("alpha")),10).isEmpty());
            assertEquals("STALE",index.status().state());fail.set(false);
            await(()->!index.status().errors().containsKey("<watch>"));assertTrue(attempts.get()>1);
            // A real filesystem event must work after watcher recovery, not just a simulated notification.
            new MarkdownDocumentStore(root).create("knowledge/b.md","Beta","article",List.of(),List.of(),null,"beta");
            await(()->!index.search(new FindQuery(null,null,List.of("beta")),10).isEmpty());
        }
    }
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(10);}fail("sync timeout");
    }
}
