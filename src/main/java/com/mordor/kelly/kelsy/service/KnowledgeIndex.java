package com.mordor.kelly.kelsy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mordor.kelly.kelsy.storage.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.mordor.kelly.kelsy.storage.StorageException.Code.*;

/** Disposable article index. Search never scans the filesystem. All JDBC access is serialized. */
public final class KnowledgeIndex implements AutoCloseable {
    static final long RECONCILE_TTL_NANOS=java.util.concurrent.TimeUnit.MINUTES.toNanos(5);
    private final Path workspace;
    private final Connection db;
    private final MarkdownDocumentStore files;
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,String> errors=new LinkedHashMap<>();
    private final Map<String,String> invalidHashes=new LinkedHashMap<>();
    private KnowledgeSyncService sync;
    private volatile boolean building=true,closed,truncated;
    public boolean truncated(){return truncated;}
    private volatile Instant reconciled;
    public record Status(String state,Instant lastReconciledAt,Map<String,String> errors) {}
    private KnowledgeIndex(Path root,Connection db){this.workspace=root;this.db=db;this.files=new MarkdownDocumentStore(root);}
    public static KnowledgeIndex open(Path workspace){
        Path root=workspace.toAbsolutePath().normalize();
        try { return new KnowledgeIndex(root,DatabaseBootstrap.open(root,true)); }
        catch (StorageException first) {
            Path db=root.resolve(".kelly-index.db");
            if(!Files.isRegularFile(db,LinkOption.NOFOLLOW_LINKS))throw first;
            try {
                String suffix=".damaged-"+UUID.randomUUID();
                for(String name:List.of(".kelly-index.db",".kelly-index.db-wal",".kelly-index.db-shm")) {
                    Path source=root.resolve(name);
                    if(Files.exists(source,LinkOption.NOFOLLOW_LINKS))Files.move(source,root.resolve(name+suffix));
                }
                return new KnowledgeIndex(root,DatabaseBootstrap.open(root,true));
            } catch(java.io.IOException e) { throw new StorageException(INDEX_UNAVAILABLE,"Cannot rebuild derived index",e); }
        }
    }
    public synchronized void start(){if(sync==null){sync=new KnowledgeSyncService(workspace,this);sync.start();}}
    public synchronized Status status(){return new Status(building?"BUILDING":errors.isEmpty()?"READY":"STALE",reconciled,Map.copyOf(errors));}
    /** Explicit background/maintenance hook; retained for callers, never called by search. */
    public void reconcileIfStale(){ if(reconciled==null||Duration.between(reconciled,Instant.now()).toMinutes()>=5)reconcile(); }
    public synchronized void reconcile(){
        check();Set<String> disk=new LinkedHashSet<>();
        try{
            for(String dir:List.of("knowledge","memory")){
                Path base=workspace.resolve(dir);if(!Files.isDirectory(base))continue;
                try(var stream=Files.walk(base)){
                    stream.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).map(p->workspace.relativize(p).toString().replace('\\','/')).filter(KnowledgeIndex::indexable).forEach(disk::add);
                }
            }
            if(Files.isRegularFile(workspace.resolve("MEMORY.md")))disk.add("MEMORY.md");
            for(String path:disk){try{upsert(path);}catch(RuntimeException e){errors.put(path,e.getMessage());}}
            List<String> gone=new ArrayList<>();try(var s=db.createStatement();var r=s.executeQuery("SELECT path FROM documents")){while(r.next())if(!disk.contains(r.getString(1)))gone.add(r.getString(1));}
            for(String path:gone)delete(path);
            errors.keySet().removeIf(p->!disk.contains(p));
            invalidHashes.keySet().removeIf(p->!disk.contains(p));
            reconciled=Instant.now();building=false;
        }catch(Exception e){building=false;errors.put("<scan>",Objects.toString(e.getMessage(),"Scan failed"));throw failure(e);}
    }
    static boolean indexable(String p){return p.equals("MEMORY.md") || (p.endsWith(".md")&&(p.startsWith("memory/")||(p.startsWith("knowledge/")&&!p.startsWith("knowledge/todos/")&&!p.equals("knowledge/KNOWLEDGE.md"))));}
    private void check(){if(closed)throw new StorageException(CLOSED,"Article index closed");}
    private StorageException failure(Exception e){return e instanceof StorageException se?se:new StorageException(INDEX_UNAVAILABLE,"Article index unavailable",e);}
    public synchronized void upsert(String path){
        check();if(!indexable(path))return;
        try{
            Path p=files.safe(path);
            if(!Files.isRegularFile(p)){delete(path);return;}
            String raw=files.readText(path),hash=MarkdownDocumentStore.hash(raw);
            if(hash.equals(invalidHashes.get(path)))return;
            try(var q=db.prepareStatement("SELECT content_hash,state FROM documents WHERE path=?")){q.setString(1,path);try(var r=q.executeQuery()){if(r.next()&&r.getString(1).equals(hash)&&r.getString(2).equals("ready")&&!errors.containsKey(path)){return;}}}
            MarkdownDocumentStore.Article article;
            try { article=path.startsWith("knowledge/")?MarkdownDocumentStore.parse(path,raw):null; }
            catch(StorageException e) {
                if(e.code()!=INVALID_ARGUMENT)throw e;
                // Never index malformed articles, including old cached chunks.
                delete(path);
                invalidHashes.put(path,hash);
                errors.put(path,"Invalid article metadata; explicit migration required");
                return;
            }
            invalidHashes.remove(path);
            UUID uid=article==null?UUID.nameUUIDFromBytes(("kelly:system:"+path).getBytes(java.nio.charset.StandardCharsets.UTF_8)):article.uid();
            String title=article==null?path:article.title(),kind=article==null?(path.equals("MEMORY.md")?"memory":"daily"):article.kind();
            String date=article==null?(path.startsWith("memory/")?path.substring(7,Math.min(path.length(),17)):null):article.date()==null?null:article.date().toString();
            try(var q=db.prepareStatement("SELECT path FROM documents WHERE document_uid=? AND path<>?")){
                q.setString(1,uid.toString());q.setString(2,path);try(var r=q.executeQuery()){if(r.next()){
                    String other=r.getString(1);
                    if(Files.exists(files.safe(other))) {errors.put(other,"Duplicate document ID");throw new StorageException(IDENTITY_CONFLICT,"Duplicate document ID: "+other);}
                    delete(other);
                }}
            }
            try(var s=db.createStatement()){s.execute("BEGIN IMMEDIATE");}
            try{
                try(var q=db.prepareStatement("INSERT INTO documents(document_uid,path,kind,title,aliases_json,event_date,mtime_ms,size_bytes,content_hash,parser_version,state) VALUES(?,?,?,?,?,?,?,?,?,1,'ready') ON CONFLICT(path) DO UPDATE SET document_uid=excluded.document_uid,kind=excluded.kind,title=excluded.title,aliases_json=excluded.aliases_json,event_date=excluded.event_date,mtime_ms=excluded.mtime_ms,size_bytes=excluded.size_bytes,content_hash=excluded.content_hash,parser_version=1,state='ready'")){
                    q.setString(1,uid.toString());q.setString(2,path);q.setString(3,kind);q.setString(4,title);q.setString(5,json.writeValueAsString(article==null?List.of():article.aliases()));q.setString(6,date);q.setLong(7,Files.getLastModifiedTime(p).toMillis());q.setLong(8,Files.size(p));q.setString(9,hash);q.executeUpdate();
                }
                long id;try(var q=db.prepareStatement("SELECT id FROM documents WHERE path=?")){q.setString(1,path);try(var r=q.executeQuery()){r.next();id=r.getLong(1);}}
                try(var q=db.prepareStatement("DELETE FROM chunks WHERE document_id=?")){q.setLong(1,id);q.executeUpdate();}
                try(var q=db.prepareStatement("DELETE FROM document_tags WHERE document_id=?")){q.setLong(1,id);q.executeUpdate();}
                if(article!=null)for(String tag:new LinkedHashSet<>(article.tags()))try(var q=db.prepareStatement("INSERT INTO document_tags VALUES(?,?)")){q.setLong(1,id);q.setString(2,tag);q.executeUpdate();}
                String aliases=article==null?"":String.join(" ",article.aliases());
                int ordinal=0;for(Chunk chunk:chunks(raw,article==null?1:article.bodyLine())){
                    long chunkId;
                    try(var q=db.prepareStatement("INSERT INTO chunks(document_id,ordinal,heading,start_line,end_line,raw_text) VALUES(?,?,?,?,?,?) RETURNING id")){
                        q.setLong(1,id);q.setInt(2,ordinal++);q.setString(3,chunk.heading);q.setInt(4,chunk.start);q.setInt(5,chunk.end);q.setString(6,chunk.raw);try(var r=q.executeQuery()){r.next();chunkId=r.getLong(1);}
                    }
                    try(var q=db.prepareStatement("INSERT INTO search_content VALUES(?,?,?,?)")){q.setLong(1,chunkId);q.setString(2,SearchTerms.index(title+" "+chunk.heading));q.setString(3,SearchTerms.index(aliases));q.setString(4,SearchTerms.index(chunk.raw));q.executeUpdate();}
                }
                try(var s=db.createStatement()){s.execute("COMMIT");}errors.remove(path);
            }catch(Exception e){try(var s=db.createStatement()){s.execute("ROLLBACK");}throw e;}
        }catch(Exception e){errors.put(path,Objects.toString(e.getMessage(),"Index failed"));throw failure(e);}
    }
    record Chunk(String heading,int start,int end,String raw) {}
    static List<Chunk> chunks(String raw,int bodyLine){
        String[] lines=raw.split("\\R",-1);List<Chunk> out=new ArrayList<>();StringBuilder body=new StringBuilder();
        String heading="";int start=bodyLine,count=0;boolean fence=false;
        for(int i=bodyLine-1;i<lines.length;i++){
            String line=lines[i];boolean header=!fence&&line.matches("#{1,6} .*");
            if(body.length()>0 && ((header&&count>=200)||(!fence&&count>=800&&line.isBlank())||count>=4000)){
                out.add(new Chunk(heading,start,i,body.toString()));body.setLength(0);start=i+1;count=0;
            }
            if(header)heading=line.replaceFirst("^#+\\s+","");
            if(line.stripLeading().startsWith("```")||line.stripLeading().startsWith("~~~"))fence=!fence;
            body.append(line).append('\n');count+=line.codePointCount(0,line.length())+1;
        }
        if(!body.isEmpty()||out.isEmpty())out.add(new Chunk(heading,start,lines.length,body.toString()));
        List<Chunk> bounded=new ArrayList<>();
        for(Chunk chunk:out){
            int total=chunk.raw.codePointCount(0,chunk.raw.length());
            if(total<=4000){bounded.add(chunk);continue;}
            for(int offset=0;offset<total;offset+=900){
                int from=chunk.raw.offsetByCodePoints(0,offset),to=chunk.raw.offsetByCodePoints(0,Math.min(total,offset+1000));
                int first=chunk.start+(int)chunk.raw.substring(0,from).chars().filter(cp->cp=='\n').count();
                int last=first+(int)chunk.raw.substring(from,to).chars().filter(cp->cp=='\n').count();
                bounded.add(new Chunk(chunk.heading,first,last,chunk.raw.substring(from,to)));
                if(offset+1000>=total)break;
            }
        }
        return bounded;
    }
    public synchronized void rebuild(){
        check();try(var s=db.createStatement()){s.executeUpdate("DELETE FROM documents");errors.clear();invalidHashes.clear();building=true;}
        catch(SQLException e){throw failure(e);}reconcile();
    }
    public synchronized void delete(String path){check();try(var q=db.prepareStatement("DELETE FROM documents WHERE path=?")){q.setString(1,path);q.executeUpdate();errors.remove(path);invalidHashes.remove(path);}catch(SQLException e){throw failure(e);}}
    public synchronized String pathFor(UUID uid){check();try(var q=db.prepareStatement("SELECT path FROM documents WHERE document_uid=?")){q.setString(1,uid.toString());try(var r=q.executeQuery()){if(!r.next())throw new StorageException(NOT_FOUND,"Article ID not indexed");return r.getString(1);}}catch(SQLException e){throw failure(e);}}
    public synchronized List<KnowledgeStore.Hit> search(FindQuery query,int limit){return search(query,null,Set.of(),limit);}
    public synchronized List<KnowledgeStore.Hit> search(FindQuery query,String kind,Set<String> tags,int limit){
        check();truncated=false;int cap=Math.min(50,Math.max(0,limit));if(cap==0)return List.of();
        String match=SearchTerms.match(String.join(" ",query.keywords()));boolean full=!match.isBlank();
        if(!full&&query.fromInclusive()==null&&kind==null&&tags.isEmpty())return List.of();
        StringBuilder sql=new StringBuilder("SELECT d.path,d.title,d.content_hash,d.document_uid,c.heading,c.start_line,c.end_line,c.raw_text,"+(full?"bm25(article_fts,8.0,5.0,1.0)":"0.0")+" AS rank FROM chunks c JOIN documents d ON d.id=c.document_id "+(full?"JOIN article_fts ON article_fts.rowid=c.id ":"")+"WHERE d.state='ready'");
        List<Object> args=new ArrayList<>();if(full){sql.append(" AND article_fts MATCH ?");args.add(match);}
        if(query.fromInclusive()!=null){sql.append(" AND d.event_date>=? AND d.event_date<=?");args.add(query.fromInclusive().toString());args.add(query.toInclusive().toString());}
        if(kind!=null){sql.append(" AND d.kind=?");args.add(kind);}
        for(String tag:tags){sql.append(" AND EXISTS(SELECT 1 FROM document_tags x WHERE x.document_id=d.id AND x.tag=?)");args.add(tag);}
        sql.append(full?" ORDER BY rank,d.path,c.ordinal":" ORDER BY d.event_date DESC,d.path,c.ordinal");sql.append(" LIMIT 5001");
        List<KnowledgeStore.Hit> hits=new ArrayList<>();Set<String> seen=new HashSet<>();
        try(var p=db.prepareStatement(sql.toString())){
            for(int i=0;i<args.size();i++)p.setObject(i+1,args.get(i));
            try(var r=p.executeQuery()){int scanned=0;while(r.next()&&hits.size()<cap){
                if(++scanned>5000){truncated=true;break;}
                String path=r.getString("path");if(errors.containsKey(path)||!seen.add(path))continue;
                String current;
                try{current=files.readText(path);}catch(StorageException e){errors.put(path,e.getMessage());continue;}
                if(!MarkdownDocumentStore.hash(current).equals(r.getString("content_hash"))){errors.put(path,"File changed; reindex pending");if(sync!=null)sync.changed(path);continue;}
                String snippet=r.getString("raw_text").strip();int end=snippet.offsetByCodePoints(0,Math.min(240,snippet.codePointCount(0,snippet.length())));snippet=snippet.substring(0,end);
                hits.add(new KnowledgeStore.Hit(path,r.getInt("start_line"),snippet,1.0,r.getString("content_hash"),r.getString("document_uid"),r.getDouble("rank")));
            }}return List.copyOf(hits);
        }catch(SQLException e){throw failure(e);}
    }
    @Override public void close(){
        KnowledgeSyncService service; synchronized(this){if(closed)return;service=sync;sync=null;}
        if(service!=null)service.close();
        synchronized(this){closed=true;try{db.close();}catch(SQLException e){throw failure(e);}}
    }
}
