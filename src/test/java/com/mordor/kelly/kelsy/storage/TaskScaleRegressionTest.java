package com.mordor.kelly.kelsy.storage;

import com.mordor.kelly.kelsy.todo.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskScaleRegressionTest {
    @TempDir Path root;
    @Test void remindersDrainAllTasksInRecoverableBoundedBatches() {
        LocalDateTime now=LocalDateTime.of(2026,11,9,10,0);Set<String> expected=new HashSet<>(),seen=new HashSet<>();
        try(var tasks=new TodoRepository(root)){
            for(int i=0;i<121;i++)expected.add("todo:"+tasks.create(new TodoRepository.Input("Task "+i,"description",now.toLocalDate(),0,List.of())).uid());
            var first=tasks.claim(now).orElseThrow();assertEquals(50,first.todos().size());assertEquals(first.uid(),tasks.claim(now).orElseThrow().uid());
        }
        try(var tasks=new TodoRepository(root)){
            int batches=0;
            while(true){var next=tasks.claim(now);if(next.isEmpty())break;var batch=next.get();
                assertTrue(batch.todos().size()<=50);for(var task:batch.todos())assertTrue(seen.add(task.relativePath()));
                tasks.acknowledge(batch.uid());assertTrue(++batches<=3);
            }
            assertEquals(expected,seen);assertEquals(3,batches);
        }
    }
    @Test void taskSortUsesAnIndexWithoutTemporarySorting()throws Exception{
        try(var tasks=new TodoRepository(root);var db=DatabaseBootstrap.open(root,false);var statement=db.createStatement()){
            for(String filter:List.of(""," AND status='open'")){
                try(var result=statement.executeQuery("EXPLAIN QUERY PLAN SELECT * FROM todos WHERE deleted_at IS NULL"+filter+" ORDER BY COALESCE(due_date,'9999-99-99'),id LIMIT 21")){
                    StringBuilder plan=new StringBuilder();while(result.next())plan.append(result.getString(4));
                    assertFalse(plan.toString().contains("TEMP B-TREE"),plan.toString());
                }
            }
        }
    }
    @Test void paginatedTasksKeepTheirSourcesAndDoNotRepeat()throws Exception{
        var article=new com.mordor.kelly.kelsy.service.MarkdownDocumentStore(root).create("knowledge/a.md","Title","article",List.of(),List.of(),null,"alpha");
        var source=new Todo.Source(article.uid(),article.path(),article.hash(),"heading");
        try(var tasks=new TodoRepository(root)){
            for(int i=0;i<61;i++)tasks.create(new TodoRepository.Input("Task "+i,"details",null,0,List.of(source)));
            Set<UUID> seen=new HashSet<>();String cursor=null;
            do{
                var page=tasks.list(new TodoRepository.Query(TodoStatus.OPEN,null,null,TodoRepository.DueMode.ANY,"",20,cursor));
                for(var task:page.items()){assertTrue(seen.add(task.uid()));assertEquals(List.of(source),task.sources());}
                cursor=page.nextCursor();
            }while(cursor!=null);
            assertEquals(61,seen.size());
        }
    }
    @Test void legacyOversizedPendingPayloadIsSplitWithoutDroppingTasks()throws Exception{
        LocalDateTime now=LocalDateTime.of(2026,11,9,10,0);Set<String> expected=new HashSet<>(),seen=new HashSet<>();
        try(var tasks=new TodoRepository(root)){
            for(int i=0;i<111;i++)expected.add("todo:"+tasks.create(new TodoRepository.Input("Task "+i,"",now.toLocalDate(),0,List.of())).uid());
            UUID first=tasks.claim(now).orElseThrow().uid();
            var json=new com.fasterxml.jackson.databind.ObjectMapper();var all=json.createArrayNode();
            try(var db=DatabaseBootstrap.open(root,false);var statement=db.createStatement()){
                try(var rows=statement.executeQuery("SELECT payload_json FROM reminder_batches WHERE state='pending' ORDER BY rowid")){
                    while(rows.next())for(var task:json.readTree(rows.getString(1)).path("tasks"))all.add(task);
                }
                try(var update=db.prepareStatement("UPDATE reminder_batches SET state='cancelled' WHERE uid<>?")){update.setString(1,first.toString());update.executeUpdate();}
                try(var update=db.prepareStatement("UPDATE reminder_batches SET payload_json=? WHERE uid=?")){
                    update.setString(1,json.writeValueAsString(Map.of("formatVersion",1,"tasks",all)));update.setString(2,first.toString());update.executeUpdate();
                }
            }
            for(int i=0;i<4;i++){
                var next=tasks.claim(now);if(next.isEmpty())break;
                assertTrue(next.get().todos().size()<=50);
                for(var task:next.get().todos())assertTrue(seen.add(task.relativePath()));
                tasks.acknowledge(next.get().uid());
            }
            assertEquals(expected,seen);assertTrue(tasks.claim(now).isEmpty());
        }
    }

}
