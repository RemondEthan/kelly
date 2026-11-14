package com.mordor.kelly.kelsy.service;

import com.mordor.kelly.kelsy.storage.StorageException;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.io.IOException;
import static com.mordor.kelly.kelsy.storage.StorageException.Code.*;

/** Markdown is authoritative. File hashes are SHA-256 of the original UTF-8 bytes. */
public final class MarkdownDocumentStore {
    private final Path root;
    // Bounded, process-wide locks: tool calls construct separate store instances.
    private static final Object[] UPDATE_LOCKS = new Object[256];
    static { Arrays.setAll(UPDATE_LOCKS, ignored -> new Object()); }

    private Object updateLock(String path) {
        try {
            Path canonical = safe(path).toRealPath();
            return UPDATE_LOCKS[Math.floorMod(canonical.hashCode(), UPDATE_LOCKS.length)];
        } catch (NoSuchFileException e) {
            throw new StorageException(NOT_FOUND, "Document not found", e);
        } catch (IOException e) {
            throw new StorageException(IO_ERROR, "Cannot resolve document lock", e);
        }
    }
    private static final Set<String> KINDS=Set.of("article","meeting","decision","person","project","playbook","inbox");
    public record Article(UUID uid,String path,String title,String kind,List<String> aliases,List<String> tags,
                          LocalDate date,String markdown,String hash,int bodyLine) {}
    public record Saved(Article article,boolean indexReady) {}
    public MarkdownDocumentStore(Path root){this.root=root.toAbsolutePath().normalize();}
    public Path safe(String path) {
        if(path==null||path.isBlank()||path.contains("\\")||path.contains(":")||path.startsWith("/")||Arrays.asList(path.split("/")).contains(".."))throw new StorageException(PATH_REJECTED,"Unsafe relative path");
        Path p=root.resolve(path).normalize();
        try {
            Files.createDirectories(root);
            Path existing=p;
            while(existing!=null&&!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.getParent();
            if(!p.startsWith(root)||existing==null||!existing.toRealPath().startsWith(root.toRealPath()))throw new StorageException(PATH_REJECTED,"Path escapes knowledge root");
            // Even internal symlinks introduce aliases and ambiguous identities; disallow them.
            for(Path part=root;part!=null;){if(Files.isSymbolicLink(part))throw new StorageException(PATH_REJECTED,"Symlinks are not supported");if(part.equals(p))break;int depth=part.getNameCount()-root.getNameCount();if(depth<0||depth>=root.relativize(p).getNameCount())break;part=part.resolve(root.relativize(p).getName(depth));}
            return p;
        }catch(IOException e){throw new StorageException(IO_ERROR,"Cannot validate path",e);}
    }
    public String readText(String path){
        Path p=safe(path);
        if(!Files.isRegularFile(p))throw new StorageException(NOT_FOUND,"Document not found");
        try{
            if(Files.size(p)>KnowledgeStore.MAX_FILE_BYTES)throw new StorageException(TOO_LARGE,"Document exceeds 256KiB");
            byte[] bytes;
            try(var in=Files.newInputStream(p)){bytes=in.readNBytes((int)KnowledgeStore.MAX_FILE_BYTES+1);}
            if(bytes.length>KnowledgeStore.MAX_FILE_BYTES)throw new StorageException(TOO_LARGE,"Document exceeds 256KiB");
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }catch(IOException e){throw new StorageException(IO_ERROR,"Cannot read document",e);}
    }
    public Article read(String path,String expectedHash){
        Article a=parse(path,readText(path));
        if(expectedHash!=null&&!expectedHash.equals(a.hash()))throw new StorageException(CONFLICT,"Document changed; retrieve it again");
        return a;
    }
    public static Article parse(String path,String raw){
        try{
            String normalized=raw.replace("\r\n","\n");
            if(!normalized.startsWith("---\n"))throw new IllegalArgumentException("Article requires YAML front matter");
            int end=normalized.indexOf("\n---\n",4);
            if(end<0)throw new IllegalArgumentException("Unterminated front matter");
            LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setCodePointLimit(262144);
            Object loaded=new Yaml(new SafeConstructor(options)).load(normalized.substring(4,end));
            if(!(loaded instanceof Map<?,?> meta))throw new IllegalArgumentException("Invalid front matter");
            UUID uid=UUID.fromString(Objects.toString(meta.get("id"),""));
            String title=Objects.toString(meta.get("title"),"").strip();String kind=Objects.toString(meta.get("type"),"");
            if(title.isEmpty()||title.codePointCount(0,title.length())>200||!KINDS.contains(kind))throw new IllegalArgumentException("Invalid article title or type");
            Object date=meta.get("date");
            LocalDate day=date==null?null:date instanceof java.util.Date d?d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate():LocalDate.parse(date.toString());
            int bodyLine=(int)normalized.substring(0,end+5).chars().filter(c->c=='\n').count()+1;
            return new Article(uid,path,title,kind,strings(meta.get("aliases")),strings(meta.get("tags")),day,raw,hash(raw),bodyLine);
        }catch(StorageException e){throw e;}catch(Exception e){throw new StorageException(INVALID_ARGUMENT,"Invalid article metadata: "+e.getMessage(),e);}
    }
    private static List<String> strings(Object value){
        if(value==null)return List.of();if(!(value instanceof List<?> list)||list.size()>32)throw new IllegalArgumentException("Expected metadata list of at most 32 values");
        List<String> out=new ArrayList<>();for(Object v:list){if(!(v instanceof String s)||s.isBlank()||s.codePointCount(0,s.length())>100)throw new IllegalArgumentException("Invalid metadata value");out.add(s);}return List.copyOf(out);
    }
    public static String hash(String raw){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private void articlePath(String path){if(path==null||!path.startsWith("knowledge/")||path.startsWith("knowledge/todos/")||path.equals("knowledge/KNOWLEDGE.md")||!path.endsWith(".md"))throw new StorageException(PATH_REJECTED,"Expected an article path under knowledge/");}
    public synchronized Article create(String path,String title,String kind,List<String> aliases,List<String> tags,LocalDate date,String body){
        articlePath(path);
        Map<String,Object> meta=new LinkedHashMap<>();meta.put("id",UUID.randomUUID().toString());meta.put("type",kind);meta.put("title",title);meta.put("aliases",new ArrayList<>(aliases));meta.put("tags",new ArrayList<>(tags));if(date!=null)meta.put("date",date.toString());
        String raw="---\n"+new Yaml().dump(meta)+"---\n"+Objects.toString(body,"");
        Article a=parse(path,raw);size(raw);Path p=safe(path);
        Path tmp=null;
        try {
            Files.createDirectories(p.getParent());tmp=Files.createTempFile(p.getParent(),".kelly-", ".tmp");Files.writeString(tmp,raw,StandardCharsets.UTF_8);
            // CREATE_NEW at the destination is guaranteed by a hard link where available.
            // A move without REPLACE_EXISTING is the portable fallback.
            try { Files.createLink(p,tmp); }
            catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
                if(Files.exists(p))throw new FileAlreadyExistsException(p.toString());
                Files.move(tmp,p);
            }
            return a;
        } catch(FileAlreadyExistsException e){throw new StorageException(CONFLICT,"Article already exists");}
        catch(IOException e){throw new StorageException(IO_ERROR,"Cannot create article",e);}
        finally {if(tmp!=null)try{Files.deleteIfExists(tmp);}catch(IOException ignored){}}
    }

    public Article update(String path,String expectedHash,String raw){
        articlePath(path);if(expectedHash==null)throw new StorageException(INVALID_ARGUMENT,"expectedHash is required");
        synchronized (updateLock(path)) {
            String previous=readText(path);
            if(!hash(previous).equals(expectedHash))throw new StorageException(CONFLICT,"Document changed; retrieve it again");
            // Explicit update may migrate a legacy file without front matter.
            // Existing structured articles must retain their identity.
            Article old=previous.replace("\r\n","\n").startsWith("---\n")?parse(path,previous):null;
            Article next=parse(path,raw);size(raw);
            if(old!=null&&!old.uid().equals(next.uid()))throw new StorageException(IDENTITY_CONFLICT,"Article ID cannot change");
            Path target=safe(path),tmp=null;
            try{
                tmp=Files.createTempFile(target.getParent(),".kelly-", ".tmp");Files.writeString(tmp,raw,StandardCharsets.UTF_8);
                if(!hash(readText(path)).equals(expectedHash))throw new StorageException(CONFLICT,"Article changed during save");
                try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}
                return next;
            }catch(IOException e){throw new StorageException(IO_ERROR,"Cannot save article",e);}finally{if(tmp!=null)try{Files.deleteIfExists(tmp);}catch(IOException ignored){}}
        }
    }
    private static void size(String raw){if(raw.getBytes(StandardCharsets.UTF_8).length>KnowledgeStore.MAX_FILE_BYTES)throw new StorageException(TOO_LARGE,"Article exceeds 256KiB");}
}
