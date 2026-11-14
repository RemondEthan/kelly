package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class IndexConcurrencyTest {
    @TempDir Path root;
    @Test void pausedFileReadDoesNotBlockQueriesOrStatus() throws Exception {
        var files=new MarkdownDocumentStore(root);
        files.create("knowledge/a.md","Title","article",List.of(),List.of(),null,"alpha");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pause=new AtomicBoolean();
        try(var index=KnowledgeIndex.open(root,path->{
            if(pause.get()){
                entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("read timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            }
            return files.readText(path);
        });var executor=Executors.newFixedThreadPool(2)){
            index.reconcile();pause.set(true);
            var scan=executor.submit(index::reconcile);
            try{
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var query=executor.submit(()->index.search(new FindQuery(null,null,List.of("alpha")),10));
                assertEquals(1,query.get(2,TimeUnit.SECONDS).size());
                assertEquals("READY",executor.submit(index::status).get(2,TimeUnit.SECONDS).state());
            }finally{release.countDown();}
            scan.get(10,TimeUnit.SECONDS);
        }
    }
    @Test void caseOnlyRenamePreservesIdentityButDuplicateIdsRemainRejected() throws Exception {
        var files=new MarkdownDocumentStore(root);
        var article=files.create("knowledge/Title.md","Title","article",List.of(),List.of(),null,"alpha");
        try(var index=KnowledgeIndex.open(root)){
            index.reconcile();Files.move(root.resolve(article.path()),root.resolve("knowledge/title.md"));
            index.upsert("knowledge/title.md");
            assertEquals("knowledge/title.md",index.pathFor(article.uid()));
            assertEquals(1,index.search(new FindQuery(null,null,List.of("alpha")),10).size());
            Files.copy(root.resolve("knowledge/title.md"),root.resolve("knowledge/copy.md"));
            assertThrows(com.mordor.kelly.kelsy.storage.StorageException.class,()->index.upsert("knowledge/copy.md"));
        }
    }
}
