package com.mordor.kelly.kelsy.storage;

import com.mordor.kelly.kelsy.todo.*;
import com.mordor.kelly.kelsy.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class HybridStorageTest {
    @TempDir Path root;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-04T02:00:00Z"),ZoneId.of("Asia/Shanghai"));
    TodoRepository.Input task(String title,LocalDate due){return new TodoRepository.Input(title,"许可证交付 licence",due,1,List.of());}
    @Test void crudVersionAndPersistence(){
        UUID uid;
        try(var repo=new TodoRepository(root,clock)){
            Todo first=repo.create(task("许可证",LocalDate.of(2026,11,4)));uid=first.uid();
            Todo edited=repo.edit(uid,1,task("更新",null));assertEquals(2,edited.version());assertNull(edited.dueDate());
            assertEquals(StorageException.Code.CONFLICT,assertThrows(StorageException.class,()->repo.complete(uid,1)).code());
            Todo done=repo.complete(uid,2);assertNotNull(done.completedAt());assertEquals(3,done.version());
            assertEquals(3,repo.complete(uid,3).version());assertEquals(4,repo.reopen(uid,3).version());
        }
        try(var repo=new TodoRepository(root,clock)){
            assertEquals(4,repo.get(uid).version());repo.delete(uid,4);
            assertEquals(StorageException.Code.NOT_FOUND,assertThrows(StorageException.class,()->repo.get(uid)).code());
            assertTrue(repo.list(TodoRepository.Query.open()).items().isEmpty());
        }
    }
    @Test void ftsFiltersAndPagination(){
        try(var repo=new TodoRepository(root,clock)){
            var done=repo.create(task("licence",LocalDate.of(2026,11,4)));repo.complete(done.uid(),1);
            for(int i=0;i<3;i++)repo.create(task("许可证"+i,LocalDate.of(2026,11,4)));
            repo.create(task("无日期",null));
            var query=new TodoRepository.Query(TodoStatus.OPEN,null,null,TodoRepository.DueMode.DATED,"许可",1,null);
            var first=repo.list(query);assertEquals(1,first.items().size());assertNotNull(first.nextCursor());
            var next=repo.list(new TodoRepository.Query(TodoStatus.OPEN,null,null,TodoRepository.DueMode.DATED,"许可",1,first.nextCursor()));
            assertNotEquals(first.items().getFirst().uid(),next.items().getFirst().uid());
            assertEquals(1,repo.list(new TodoRepository.Query(TodoStatus.OPEN,null,null,TodoRepository.DueMode.UNDATED,"",50,null)).items().size());
            assertDoesNotThrow(()->repo.list(new TodoRepository.Query(null,null,null,TodoRepository.DueMode.ANY,"\" OR *",50,null)));
        }
    }
    @Test void failedAuditRollsBackTaskAndFts()throws Exception{
        try(var repo=new TodoRepository(root,clock)){
            var todo=repo.create(task("alpha",null));
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve(".kelly-data.db"));var s=c.createStatement()){
                s.execute("CREATE TRIGGER reject_event BEFORE INSERT ON todo_events BEGIN SELECT RAISE(ABORT,'test'); END");
            }
            assertThrows(StorageException.class,()->repo.edit(todo.uid(),1,task("beta",null)));
            assertEquals("alpha",repo.get(todo.uid()).title());assertEquals(1,repo.get(todo.uid()).version());
            assertEquals(1,repo.list(new TodoRepository.Query(null,null,null,TodoRepository.DueMode.ANY,"alpha",10,null)).items().size());
            assertTrue(repo.list(new TodoRepository.Query(null,null,null,TodoRepository.DueMode.ANY,"beta",10,null)).items().isEmpty());
        }
    }
    @Test void remindersResumeCancelAndConsumeSlots(){
        try(var repo=new TodoRepository(root,clock)){
            var task=repo.create(task("due",LocalDate.of(2026,11,7)));repo.create(task("undated",null));repo.create(task("later",LocalDate.of(2026,11,8)));
            var batch=repo.claim(LocalDateTime.of(2026,11,4,10,0)).orElseThrow();assertEquals(1,batch.todos().size());assertEquals(2,batch.slots().size());
            assertEquals(batch.uid(),repo.claim(LocalDateTime.of(2026,11,4,10,1)).orElseThrow().uid());
            repo.complete(task.uid(),1);assertTrue(repo.claim(LocalDateTime.of(2026,11,4,10,2)).isEmpty());
            repo.reopen(task.uid(),2);assertTrue(repo.claim(LocalDateTime.of(2026,11,4,10,3)).isEmpty());
            var afternoon=repo.claim(LocalDateTime.of(2026,11,4,14,0)).orElseThrow();assertEquals(EnumSet.of(ReminderSlot.FOURTEEN),afternoon.slots());
            repo.acknowledge(afternoon.uid());assertTrue(repo.claim(LocalDateTime.of(2026,11,4,14,1)).isEmpty());
        }
    }
    @Test void articlesIndexHashRenameAndCascade()throws Exception{
        var files=new MarkdownDocumentStore(root);
        var article=files.create("knowledge/articles/a.md","交付","article",List.of("licence"),List.of("产品"),LocalDate.of(2026,11,4),"# 评审\n许可证交付方案");
        try(var index=KnowledgeIndex.open(root)){
            index.reconcile();var found=index.search(new FindQuery(null,null,List.of("许可")),10);assertEquals(1,found.size());assertTrue(found.getFirst().snippet().contains("许可证"));
            assertEquals(1,index.search(new FindQuery(null,null,List.of("licence")),"article",Set.of("产品"),10).size());
            var edited=files.update(article.path(),article.hash(),article.markdown().replace("许可证交付方案","完全不同内容"));
            assertThrows(StorageException.class,()->files.update(article.path(),article.hash(),article.markdown()));
            index.upsert(article.path());assertTrue(index.search(new FindQuery(null,null,List.of("许可")),10).isEmpty());
            Path dest=root.resolve("knowledge/articles/renamed.md");Files.move(root.resolve(article.path()),dest);index.reconcile();assertEquals("knowledge/articles/renamed.md",index.pathFor(article.uid()));
            Files.delete(dest);index.reconcile();assertTrue(index.search(new FindQuery(null,null,List.of("licence")),10).isEmpty());
        }
    }
    @Test void invalidIdentityAndSymlink()throws Exception{
        var files=new MarkdownDocumentStore(root);assertThrows(StorageException.class,()->files.read("../escape",null));
        var article=files.create("knowledge/articles/a.md","标题","article",List.of(),List.of(),null,"正文");
        Files.writeString(root.resolve("knowledge/articles/copy.md"),article.markdown());
        try(var index=KnowledgeIndex.open(root)){index.reconcile();assertEquals("STALE",index.status().state());assertTrue(index.search(new FindQuery(null,null,List.of("正文")),10).isEmpty());}
        Path outside=Files.createTempDirectory("kelly-outside");
        try{Files.createSymbolicLink(root.resolve("escape"),outside);assertThrows(StorageException.class,()->files.safe("escape/a.md"));}catch(UnsupportedOperationException ignored){}finally{Files.deleteIfExists(outside);}
    }
    @Test void formalDatabaseNeverRebuiltAndBackup()throws Exception{
        Path backup=root.resolve("backup.db");UUID uid;
        try(var repo=new TodoRepository(root,clock)){uid=repo.create(task("保留",null)).uid();repo.backup(backup);}
        Path restored=root.resolve("restore");Files.createDirectories(restored);Files.copy(backup,restored.resolve(".kelly-data.db"));
        try(var repo=new TodoRepository(restored,clock)){assertEquals("保留",repo.get(uid).title());}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve(".kelly-data.db"));var s=c.createStatement()){s.execute("PRAGMA user_version=99");}
        assertEquals(StorageException.Code.UNSUPPORTED_SCHEMA,assertThrows(StorageException.class,()->new TodoRepository(root,clock)).code());
        assertTrue(Files.size(root.resolve(".kelly-data.db"))>0);
    }
    @Test void portableBackupRestoresArticleTaskAndSources() throws Exception {
        Path user=root.resolve("user"), archive=root.resolve("backup.zip"), restored=root.resolve("restored");
        try(var store=new KnowledgeStore(user)) {
            var article=new MarkdownDocumentStore(user).create("knowledge/articles/source.md","来源","article",List.of(),List.of(),null,"任务来源");
            var source=new Todo.Source(article.uid(),article.path(),article.hash(),null);
            var task=store.todos().create(new TodoRepository.Input("关联任务","正文",null,0,List.of(source)));
            HybridBackupService.backup(store,archive);
            HybridBackupService.restore(archive,restored);
            try(var copy=new KnowledgeStore(restored)) {
                assertEquals("关联任务",copy.todos().get(task.uid()).title());
                assertEquals(article.uid(),copy.todos().get(task.uid()).sources().getFirst().documentUid());
                assertEquals(article.hash(),new MarkdownDocumentStore(restored).read(article.path(),null).hash());
            }
            assertThrows(StorageException.class,()->HybridBackupService.restore(archive,restored));
        }
    }
    @Test void rejectsChangedOrForeignSources() {
        var files=new MarkdownDocumentStore(root);
        var article=files.create("knowledge/articles/a.md","标题","article",List.of(),List.of(),null,"正文");
        try(var repo=new TodoRepository(root,clock)) {
            var wrong=new Todo.Source(UUID.randomUUID(),article.path(),article.hash(),null);
            assertEquals(StorageException.Code.IDENTITY_CONFLICT,assertThrows(StorageException.class,()->repo.create(new TodoRepository.Input("任务","",null,0,List.of(wrong)))).code());
            assertTrue(repo.list(TodoRepository.Query.open()).items().isEmpty());
        }
    }
    @Test void backgroundSyncFindsNewSubdirectoryAndMissedEdit() throws Exception {
        var files=new MarkdownDocumentStore(root);
        try(var index=KnowledgeIndex.open(root)) {
            index.start();
            var article=files.create("knowledge/articles/new/a.md","唯一关键词","article",List.of(),List.of(),null,"同步正文");
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline && index.search(new FindQuery(null,null,List.of("唯一关键词")),10).isEmpty())Thread.sleep(25);
            assertEquals(1,index.search(new FindQuery(null,null,List.of("唯一关键词")),10).size());
            var oldMtime=Files.getLastModifiedTime(root.resolve(article.path()));
            Files.writeString(root.resolve(article.path()),article.markdown().replace("同步正文","修改正文"));
            Files.setLastModifiedTime(root.resolve(article.path()),oldMtime);
            index.reconcile();
            assertEquals(1,index.search(new FindQuery(null,null,List.of("修改正文")),10).size());
        }
    }
}
