package com.mordor.kelly.kelsy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.*;
import io.agentscope.core.message.TextBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DegradedIndexTest {
    @TempDir Path root;
    @Test void missingIndexDoesNotBlockGeneralRequestsOrPathBasedReads()throws Exception{
        try(var store=new KnowledgeStore(root,null)){
            var article=new MarkdownDocumentStore(root).create("knowledge/a.md","Title","article",List.of(),List.of(),null,"alpha");
            var grounding=AskGrounding.prepare(store,"帮我写一个项目计划",LocalDate.of(2026,11,9));
            assertFalse(grounding.searched());assertTrue(grounding.messageForModel().contains("帮我写一个项目计划"));
            var read=call(new KnowledgeAgentTool("get",store),Map.of("uid",article.uid().toString(),"path",article.path()));
            assertTrue(read.path("ok").asBoolean(),read.toString());
            assertInstanceOf(KnowledgeStore.Read.Ok.class,store.read("article:"+article.uid(),article.path(),article.hash(),null));
            String wrong="article:"+UUID.randomUUID();
            assertInstanceOf(KnowledgeStore.Read.Rejected.class,store.read(wrong,article.path(),article.hash(),null));
            var create=call(new KnowledgeAgentTool("create",store),Map.of("path","knowledge/b.md","title","Title","kind","article","bodyMarkdown","body"));
            assertTrue(create.path("ok").asBoolean());assertFalse(create.path("data").path("indexReady").asBoolean());
            assertTrue(store.todos().create(new com.mordor.kelly.kelsy.todo.TodoRepository.Input("Task","",null,0,List.of())).version()>0);
        }
    }
    @Test void reopeningChangedEvidenceReportsConflict()throws Exception{
        try(var store=new KnowledgeStore(root)){
            var documents=new MarkdownDocumentStore(root);
            var article=documents.create("knowledge/a.md","Title","article",List.of(),List.of(),null,"alpha");store.index().upsert(article.path());
            documents.update(article.path(),article.hash(),article.markdown().replace("alpha","beta"));
            assertInstanceOf(KnowledgeStore.Read.Rejected.class,store.read("article:"+article.uid(),article.hash(),null));
            var task=store.todos().create(new com.mordor.kelly.kelsy.todo.TodoRepository.Input("Task","",null,0,List.of()));
            store.todos().complete(task.uid(),task.version());
            assertInstanceOf(KnowledgeStore.Read.Rejected.class,store.read("todo:"+task.uid(),null,task.version()));
        }
    }
    private static com.fasterxml.jackson.databind.JsonNode call(AgentTool tool,Map<String,Object> input)throws Exception{
        var result=tool.callAsync(ToolCallParam.builder().input(input).build()).block();
        return new ObjectMapper().readTree(((TextBlock)result.getOutput().getFirst()).getText());
    }
}
