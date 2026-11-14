package com.mordor.kelly.kelsy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.*;
import io.agentscope.core.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HybridAgentToolsTest {
    @TempDir Path root;
    @Test void invalidatedArticleDropsCachedChunksAndRecoversAfterCorrection() throws Exception {
        var files=new MarkdownDocumentStore(root);
        var article=files.create("knowledge/inbox/test.md","测试","article",List.of(),List.of(),null,"唯一检索词");
        try(var index=KnowledgeIndex.open(root)) {
            index.reconcile();
            assertEquals(1,index.search(new FindQuery(null,null,List.of("唯一检索词")),10).size());
            Files.writeString(root.resolve(article.path()),"# 缺元数据\n唯一检索词");
            index.reconcile();
            index.reconcile();
            assertTrue(index.search(new FindQuery(null,null,List.of("唯一检索词")),10).isEmpty());
            assertTrue(index.status().errors().containsKey(article.path()));
            Files.writeString(root.resolve(article.path()),article.markdown());
            index.reconcile();
            assertEquals("READY",index.status().state());
            assertEquals(1,index.search(new FindQuery(null,null,List.of("唯一检索词")),10).size());
        }
    }
    @Test void malformedArticleDoesNotPoisonSearchAndCanBeExplicitlyMigrated() throws Exception {
        String path="knowledge/inbox/legacy.md";
        Files.createDirectories(root.resolve("knowledge/inbox"));
        Files.writeString(root.resolve(path),"# 旧文件\n测试正文");
        try(var store=new KnowledgeStore(root)) {
            new MarkdownDocumentStore(root).create("knowledge/inbox/good.md","正常文章","article",List.of(),List.of(),null,"测试正文");
            store.index().reconcile();
            var search=call(new KnowledgeSearchTool(store),Map.of("query","测试正文"));
            assertTrue(search.path("ok").asBoolean());
            assertEquals(1,search.path("data").path("unindexedFileCount").asInt());
            assertEquals(1,search.path("data").path("hits").size());
            assertFalse(search.toString().contains(path));
            assertFalse(search.path("data").has("errors"));
            assertEquals("# 旧文件\n测试正文",Files.readString(root.resolve(path)));
            var read=call(new KnowledgeAgentTool("get",store),Map.of("path",path));
            assertTrue(read.path("data").path("metadataRequired").asBoolean());
            String migrated="---\nid: "+UUID.randomUUID()+"\ntitle: 旧文件\ntype: article\n---\n测试正文";
            var stale=call(new KnowledgeAgentTool("update",store),Map.of("path",path,"expectedHash","stale","markdown",migrated));
            assertEquals("CONFLICT",stale.path("error").path("code").asText());
            var saved=call(new KnowledgeAgentTool("update",store),Map.of("path",path,"expectedHash",read.path("data").path("hash").asText(),"markdown",migrated));
            assertTrue(saved.path("ok").asBoolean());
            store.index().reconcile();
            assertEquals("READY",store.index().status().state());
        }
    }
    JsonNode call(AgentTool tool,Map<String,Object> values)throws Exception {
        ToolResultBlock result=tool.callAsync(ToolCallParam.builder().input(values).build()).block();
        return new ObjectMapper().readTree(((TextBlock)result.getOutput().getFirst()).getText());
    }
    @Test void taskToolLifecycleAndConflict()throws Exception {
        try(var store=new KnowledgeStore(root)) {
            var created=call(new TodoAgentTool("create",store.todos()),Map.of("title","提交报告"));assertTrue(created.path("ok").asBoolean());
            String uid=created.path("data").path("uid").asText();
            var done=call(new TodoAgentTool("complete",store.todos()),Map.of("uid",uid,"expectedVersion",1));assertEquals("closed",done.path("data").path("status").asText());
            var conflict=call(new TodoAgentTool("reopen",store.todos()),Map.of("uid",uid,"expectedVersion",1));assertEquals("CONFLICT",conflict.path("error").path("code").asText());
            var page=call(new TodoAgentTool("list",store.todos()),Map.of("status","all"));assertEquals(1,page.path("data").path("items").size());
            assertInstanceOf(KnowledgeStore.Read.Ok.class,store.read("todo:"+uid));
        }
    }
    @Test void articleToolsHashReadAndUpdate()throws Exception {
        try(var store=new KnowledgeStore(root)) {
            var created=call(new KnowledgeAgentTool("create",store),Map.of("path","knowledge/articles/a.md","title","方案","kind","article","bodyMarkdown","原文"));
            assertTrue(created.path("ok").asBoolean());String uid=created.path("data").path("uid").asText(),hash=created.path("data").path("hash").asText();
            var read=call(new KnowledgeAgentTool("get",store),Map.of("uid",uid,"expectedHash",hash));assertTrue(read.path("data").path("markdown").asText().contains("原文"));
            var edited=call(new KnowledgeAgentTool("update",store),Map.of("path","knowledge/articles/a.md","expectedHash",hash,"markdown",read.path("data").path("markdown").asText().replace("原文","新文")));assertTrue(edited.path("ok").asBoolean());
            var stale=call(new KnowledgeAgentTool("get",store),Map.of("uid",uid,"expectedHash",hash));assertEquals("CONFLICT",stale.path("error").path("code").asText());
            assertInstanceOf(KnowledgeStore.Read.Ok.class,store.read("article:"+uid));
        }
    }
}
