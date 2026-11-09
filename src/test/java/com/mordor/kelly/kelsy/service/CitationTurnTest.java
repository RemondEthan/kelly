package com.mordor.kelly.kelsy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CitationTurnTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static String task(UUID uid,long version)throws Exception{
        return JSON.writeValueAsString(Map.of("ok",true,"data",Map.of("uid",uid.toString(),"version",version)));
    }
    @Test void argumentsFailureListingsAndGeneratedProseAreNotEvidence(){
        CitationTurn t=new CitationTurn();t.beginAsk();
        t.beginTool("knowledge_get");t.appendToolArgs("{\"path\":\"knowledge/missing.md\"}");
        t.appendToolResult("{\"ok\":false,\"error\":{\"code\":\"NOT_FOUND\"}}");
        t.beginTool("list_files");t.appendToolResult("knowledge/a.md");
        t.addRetrievalText("来源：knowledge/fabricated.md");t.finishTools();
        assertFalse(t.commitIfRetrieved());assertTrue(t.shown().isEmpty());
    }
    @Test void successfulTaskResultRetainsVersionAndNewTurnClearsIt()throws Exception{
        CitationTurn t=new CitationTurn();UUID uid=UUID.randomUUID();t.beginTool("todo_get");
        String output=task(uid,3);t.appendToolResult(output.substring(0,output.length()/2));
        assertTrue(t.pendingPaths().isEmpty());t.appendToolResult(output.substring(output.length()/2));t.finishTools();
        assertTrue(t.commitIfRetrieved());assertEquals(List.of("todo:"+uid),t.shown());
        assertEquals(3L,t.evidence().getFirst().version());t.beginAsk();
        assertTrue(t.shown().isEmpty());assertTrue(t.evidence().isEmpty());assertFalse(t.commitIfRetrieved());
    }
    @Test void interleavedSameNameCallsRemainSeparateAndFailedEndIsExcluded()throws Exception{
        CitationTurn t=new CitationTurn();UUID good=UUID.randomUUID(),bad=UUID.randomUUID();
        t.beginTool("a","todo_get");t.beginTool("b","todo_get");
        t.appendToolResult("a",task(good,1));t.appendToolResult("b",task(bad,2));
        t.finishTool("a",true);t.finishTool("b",false);t.finishTools();
        assertEquals(List.of("todo:"+good),t.pendingPaths());
    }
    @Test void verifiedArticleHashMustMatchReturnedMarkdown()throws Exception{
        CitationTurn t=new CitationTurn();String uid=UUID.randomUUID().toString(),raw="# Title\n正文";
        t.beginTool("knowledge_get");t.appendToolResult(JSON.writeValueAsString(Map.of("ok",true,"data",Map.of("uid",uid,"path","knowledge/a.md","hash",MarkdownDocumentStore.hash(raw),"markdown",raw))));
        t.finishTools();assertTrue(t.commitIfRetrieved());assertEquals(List.of("article:"+uid),t.shown());
        assertEquals(MarkdownDocumentStore.hash(raw),t.evidence().getFirst().hash());
        t.beginAsk();t.beginTool("knowledge_get");t.appendToolResult(JSON.writeValueAsString(Map.of("ok",true,"data",Map.of("uid",uid,"path","knowledge/a.md","hash",MarkdownDocumentStore.hash(raw),"markdown","different"))));
        t.finishTools();assertFalse(t.commitIfRetrieved());
    }
    @Test void trailingMalformedOutputAndSearchCandidatesAreNotVerified()throws Exception{
        CitationTurn t=new CitationTurn();t.beginTool("todo_get");t.appendToolResult(task(UUID.randomUUID(),1)+" garbage");
        t.beginTool("knowledge_search");t.appendToolResult("{\"ok\":true,\"data\":{\"hits\":[{\"path\":\"knowledge/a.md\"}]}}");
        t.finishTools();assertTrue(t.pendingPaths().isEmpty());
    }
    @Test void findAndReminderPathsAreExplicitNavigationSources(){
        CitationTurn t=new CitationTurn();t.addPaths(List.of("knowledge/KNOWLEDGE.md","knowledge/a.md"));
        assertTrue(t.commitIfRetrieved());assertEquals(List.of("knowledge/a.md"),t.shown());
        t.replaceShown(List.of("todo:"+UUID.randomUUID()));assertTrue(t.firstShown().startsWith("todo:"));
    }
}
