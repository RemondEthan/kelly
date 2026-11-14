package com.mordor.kelly.kelsy.service;

import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.message.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalRegressionTest {
    @TempDir Path root;
    @Test void mixedScriptsPunctuationAndCrossChunkConstraints() {
        var files=new MarkdownDocumentStore(root);
        files.create("knowledge/a.md","Record","article",List.of(),List.of(),null,
                "许可证交付 licence 张三\n"+"neutral ".repeat(150)+"\n\n# Second\nbeta");
        files.create("knowledge/b.md","Record","article",List.of(),List.of(),null,"其他人交付 licence");
        try(var index=KnowledgeIndex.open(root)) {
            index.reconcile();
            for(String query:List.of("许可证licence","licence许可证","张三，交付","张三 beta")) {
                var hits=index.search(new FindQuery(null,null,List.of(query)),50);
                assertEquals(List.of("knowledge/a.md"),hits.stream().map(KnowledgeStore.Hit::relativePath).toList(),query);
            }
            assertTrue(index.search(new FindQuery(null,null,List.of("张三 missing")),50).isEmpty());
        }
    }
    @Test void meetingIntentAndStructuredToolFilters() throws Exception {
        new MarkdownDocumentStore(root).create("knowledge/review.md","交付评审","meeting",List.of(),List.of("客户"),LocalDate.of(2026,11,9),"张三确认许可证周五交付");
        try(var store=new KnowledgeStore(root)) {
            store.index().reconcile();
            assertEquals(1,AskGrounding.prepare(store,"我最近有什么会议？",LocalDate.of(2026,11,9)).attached().size());
            var tool=new KnowledgeSearchTool(store);
            var output=tool.callAsync(ToolCallParam.builder().input(Map.of("kind","meeting","tags",List.of("客户"),"from","2026-11-09","before","2026-11-10")).build()).block();
            var json=new ObjectMapper().readTree(((TextBlock)output.getOutput().getFirst()).getText());
            assertTrue(json.path("ok").asBoolean(),json.toString());assertEquals(1,json.path("data").path("hits").size());
        }
    }
}
