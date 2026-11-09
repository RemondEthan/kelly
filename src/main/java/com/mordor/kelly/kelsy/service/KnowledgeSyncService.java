package com.mordor.kelly.kelsy.service;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;

/** Watch events accelerate updates; periodic full hash reconciliation repairs missed events. */
public final class KnowledgeSyncService implements AutoCloseable {
    private final Path root; private final KnowledgeIndex index;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"kelly-knowledge-sync");t.setDaemon(true);return t;});
    private final Map<WatchKey,Path> keys=new ConcurrentHashMap<>();
    private final Set<String> changed=ConcurrentHashMap.newKeySet();
    private WatchService watcher;private volatile boolean closed;
    public KnowledgeSyncService(Path root,KnowledgeIndex index){this.root=root;this.index=index;}
    public void start(){
        worker.execute(()->{safeReconcile();try{watcher=root.getFileSystem().newWatchService();register();}catch(IOException ignored){} });
        worker.scheduleWithFixedDelay(this::poll,500,500,TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(this::safeReconcile,5,5,TimeUnit.MINUTES);
    }
    private void register()throws IOException{
        try(var paths=Files.walk(root)){for(Path p:paths.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).toList()){
            if(keys.containsValue(p))continue;
            keys.put(p.register(watcher,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.ENTRY_DELETE),p);
        }}
    }
    public void changed(String path){if(!closed)changed.add(path);}
    private void poll(){
        if(closed)return;
        try{
            boolean directories=false;
            if(watcher!=null)for(WatchKey key;(key=watcher.poll())!=null;){
                Path dir=keys.get(key);for(var event:key.pollEvents()){
                    if(event.kind()==StandardWatchEventKinds.OVERFLOW){safeReconcile();directories=true;continue;}
                    if(dir==null)continue;Path p=dir.resolve((Path)event.context());
                    if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)){directories=true;continue;}
                    changed.add(root.relativize(p).toString().replace('\\','/'));
                }if(!key.reset())keys.remove(key);
            }
            if(directories){register();safeReconcile();}
            for(String path:List.copyOf(changed)){
                changed.remove(path);try{index.upsert(path);}catch(RuntimeException ignored){/* Periodic reconciliation retries failures. */}
            }
        }catch(Exception ignored){/* Watcher failure degrades to periodic reconciliation. */}
    }
    private void safeReconcile(){if(!closed)try{index.reconcile();}catch(RuntimeException ignored){/* State retains error detail. */}}
    @Override public void close(){closed=true;worker.shutdownNow();if(watcher!=null)try{watcher.close();}catch(IOException ignored){}keys.clear();changed.clear();}
}
