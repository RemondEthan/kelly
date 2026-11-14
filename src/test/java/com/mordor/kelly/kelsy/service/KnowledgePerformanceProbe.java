package com.mordor.kelly.kelsy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mordor.kelly.kelsy.todo.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in local/CI probe; prints measurements, never treats timing noise as a correctness test. */
public final class KnowledgePerformanceProbe {
    private KnowledgePerformanceProbe() {}
    public static void main(String[] args)throws Exception {
        int documents=Integer.getInteger("kelly.benchmark.documents",3000);
        int todos=Integer.getInteger("kelly.benchmark.todos",3000);
        if(documents<50||todos<50)throw new IllegalArgumentException("Use at least 50 documents/tasks");
        Path root=Files.createTempDirectory("kelly-performance-");
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("os",System.getProperty("os.name"));report.put("java",System.getProperty("java.version"));
        report.put("documents",documents);report.put("todos",todos);
        try {
            Files.createDirectories(root.resolve("knowledge"));
            for(int i=0;i<documents;i++){
                UUID uid=UUID.nameUUIDFromBytes(("document-"+i).getBytes(StandardCharsets.UTF_8));
                Files.writeString(root.resolve("knowledge/"+i+".md"),"---\nid: "+uid+"\ntype: article\ntitle: Record\n---\nalpha "+"neutral ".repeat(250));
            }
            try(var index=KnowledgeIndex.open(root)) {
                long start=System.nanoTime();index.reconcile();report.put("initialIndexMs",elapsed(start));
                var query=new FindQuery(null,null,List.of("alpha"));
                for(int i=0;i<10;i++)index.search(query,50);
                double[] hot=new double[50];
                for(int i=0;i<hot.length;i++){start=System.nanoTime();if(index.search(query,50).size()!=50)throw new AssertionError("Recall changed");hot[i]=elapsed(start);}
                Arrays.sort(hot);report.put("searchP95Ms",hot[47]);report.put("searchP99Ms",hot[49]);
                try(var worker=Executors.newSingleThreadExecutor()) {
                    var started=new CountDownLatch(1);var scan=worker.submit(()->{started.countDown();index.reconcile();});started.await();
                    double[] concurrent=new double[20];
                    for(int i=0;i<concurrent.length;i++){start=System.nanoTime();index.search(query,50);concurrent[i]=elapsed(start);}
                    scan.get();Arrays.sort(concurrent);report.put("reconcileConcurrentSearchMaxMs",concurrent[19]);
                }
            }
            try(var tasks=new TodoRepository(root)) {
                for(int i=0;i<todos;i++)tasks.create(new TodoRepository.Input("Task "+i,"",java.time.LocalDate.of(2026,11,9),0,List.of()));
                double[] times=new double[50];
                for(int i=0;i<times.length;i++){long start=System.nanoTime();if(tasks.list(TodoRepository.Query.open()).items().size()!=20)throw new AssertionError("Task page changed");times[i]=elapsed(start);}
                Arrays.sort(times);report.put("taskListP95Ms",times[47]);
            }
            String json=new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report);
            System.out.println(json);
            String output=System.getProperty("kelly.benchmark.report");
            if(output!=null){Path path=Path.of(output).toAbsolutePath();Files.createDirectories(path.getParent());Files.writeString(path,json+"\n");}
        } finally {
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    private static double elapsed(long start){return (System.nanoTime()-start)/1_000_000.0;}
}
