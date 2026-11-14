package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryRecoveryTest {
    @TempDir Path root;
    @Test void sameSlugArchivesAreDistinctAndPartialApplyCanBeRetried() throws Exception {
        String a="- 2020-01-01 abcdefghijklmnopqrstuvwxyz"+"x".repeat(2100);
        String raw="# Memory\n"+a+"\n"+a+"tail\n";
        Files.writeString(root.resolve("MEMORY.md"),raw);
        var result=MemoryCompactor.compact(raw,LocalDate.of(2026,11,9));
        assertEquals(2,result.inboxCards().stream().map(MemoryCompactor.InboxCard::relativePath).distinct().count());
        var first=result.inboxCards().getFirst();
        new MarkdownDocumentStore(root).create(first.relativePath(),"收件箱","inbox",List.of(),List.of(),null,first.markdown());
        MemoryCompactor.apply(root,result);MemoryCompactor.apply(root,result);
        assertEquals(result.memoryMarkdown(),Files.readString(root.resolve("MEMORY.md")));
        for(var card:result.inboxCards())assertTrue(Files.readString(root.resolve(card.relativePath())).contains(card.markdown()));
    }
    @Test void undatedFactsAreArchivedIntactAndWorkingSetFitsBudget() throws Exception {
        String fact="- 长期偏好"+"中文𠀀".repeat(1500);
        var result=MemoryCompactor.compact("# Memory\n"+fact+"\n",LocalDate.of(2026,11,9));
        assertTrue(result.memoryMarkdown().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=MemoryCompactor.LIMIT_BYTES);
        assertTrue(result.inboxCards().stream().anyMatch(card->card.markdown().contains(fact)));
        MemoryCompactor.apply(root,result);
    }
    @Test void collapsedArchiveKeepsOverflowPointerResolvable()throws Exception{
        StringBuilder memory=new StringBuilder("# Memory\n- 长期偏好"+"中文".repeat(1800)+"\n");
        for(int i=0;i<10;i++)memory.append("- 2020-01-01 旧记录 ").append(i).append('\n');
        var result=MemoryCompactor.compact(memory.toString(),LocalDate.of(2026,11,9));
        String pointer=KnowledgePathExtractor.first(result.memoryMarkdown()).orElseThrow();
        assertTrue(result.inboxCards().stream().anyMatch(card->card.relativePath().equals(pointer)));
        MemoryCompactor.apply(root,result);assertTrue(Files.isRegularFile(root.resolve(pointer)));
    }

}
