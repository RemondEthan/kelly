package com.mordor.kelly.kelsy.service;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.IOException;
import java.time.Duration;

/** Watch events accelerate updates; full reconciliation remains the portable source of truth. */
public final class KnowledgeSyncService implements AutoCloseable {
    @FunctionalInterface interface WatcherFactory { WatchService open() throws IOException; }
    private final Path root;
    private final KnowledgeIndex index;
    private final WatcherFactory factory;
    private final long pollMillis,reconcileMillis,retryNanos;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"kelly-knowledge-sync");t.setDaemon(true);return t;});
    private final Map<WatchKey,Path> keys=new ConcurrentHashMap<>();
    private final Set<Path> registered=ConcurrentHashMap.newKeySet();
    private final Set<String> changed=ConcurrentHashMap.newKeySet();
    private volatile WatchService watcher;
    private volatile boolean closed;
    private long nextRetry;
    private boolean watching,retryScheduled;
    public KnowledgeSyncService(Path root,KnowledgeIndex index){
        this(root,index,()->root.getFileSystem().newWatchService(),Duration.ofMillis(500),Duration.ofMinutes(5),Duration.ofSeconds(30));
    }
    KnowledgeSyncService(Path root,KnowledgeIndex index,WatcherFactory factory,Duration poll,Duration reconcile,Duration retry){
        this.root=root;this.index=index;this.factory=factory;
        pollMillis=poll.toMillis();reconcileMillis=reconcile.toMillis();retryNanos=retry.toNanos();
    }
    public void start(){
        worker.execute(()->{ensureWatcher();safeReconcile();registerSafely();});
        worker.scheduleWithFixedDelay(this::poll,pollMillis,pollMillis,TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(this::safeReconcile,reconcileMillis,reconcileMillis,TimeUnit.MILLISECONDS);
    }
    private void ensureWatcher(){
        if(closed||watching||(retryScheduled&&System.nanoTime()-nextRetry<0))return;
        try{
            if(watcher==null){WatchService opened=factory.open();if(closed){opened.close();return;}watcher=opened;}
            register();index.watcherHealthy();watching=true;retryScheduled=false;
        }catch(Exception e){failed(e);}
    }
    private void failed(Exception e){
        index.watcherFailed("File watcher degraded; periodic reconciliation remains active: "+Objects.toString(e.getMessage(),e.getClass().getSimpleName()));
        watching=false;retryScheduled=true;nextRetry=System.nanoTime()+retryNanos;
    }
    private void registerSafely(){if(watcher!=null&&!closed)try{register();}catch(Exception e){failed(e);}}
    private void register()throws IOException{
        Set<Path> directories=new LinkedHashSet<>();directories.add(root);
        for(String name:List.of("knowledge","memory")){
            Path base=root.resolve(name);if(!Files.isDirectory(base,LinkOption.NOFOLLOW_LINKS))continue;
            try(var paths=Files.walk(base)){paths.filter(p->Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).forEach(directories::add);}
        }
        for(Path p:directories){
            if(closed)return;
            if(registered.contains(p))continue;
            WatchKey key=p.register(watcher,StandardWatchEventKinds.ENTRY_CREATE,StandardWatchEventKinds.ENTRY_MODIFY,StandardWatchEventKinds.ENTRY_DELETE);
            keys.put(key,p);registered.add(p);
        }
    }
    public void changed(String path){if(!closed)changed.add(path);}
    private void poll(){
        if(closed)return;
        ensureWatcher();
        try{
            boolean directories=false;
            if(watcher!=null)for(WatchKey key;(key=watcher.poll())!=null;){
                Path dir=keys.get(key);
                for(var event:key.pollEvents()){
                    if(event.kind()==StandardWatchEventKinds.OVERFLOW){directories=true;continue;}
                    if(dir==null)continue;
                    Path path=dir.resolve((Path)event.context());
                    if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||registered.contains(path))directories=true;
                    else changed.add(root.relativize(path).toString().replace('\\','/'));
                }
                if(!key.reset()){Path removed=keys.remove(key);if(removed!=null)registered.remove(removed);directories=true;}
            }
            if(directories){registerSafely();safeReconcile();}
            for(String path:List.copyOf(changed)){
                changed.remove(path);try{index.upsert(path);}catch(RuntimeException ignored){/* Reconciliation retries failed snapshots. */}
            }
        }catch(Exception e){
            failed(e);
            if(watcher!=null)try{watcher.close();}catch(IOException ignored){}
            watcher=null;keys.clear();registered.clear();
        }
    }
    private void safeReconcile(){if(!closed)try{index.reconcile();}catch(RuntimeException ignored){/* Index records error details. */}}
    @Override public void close(){
        closed=true;worker.shutdownNow();
        if(watcher!=null)try{watcher.close();}catch(IOException ignored){}
        keys.clear();registered.clear();changed.clear();
    }
}
