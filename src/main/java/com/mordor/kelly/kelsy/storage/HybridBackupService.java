package com.mordor.kelly.kelsy.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mordor.kelly.kelsy.service.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;

/** Portable snapshot: authoritative Markdown plus an online SQLite backup, no derived index. */
public final class HybridBackupService {
    private static final ObjectMapper JSON=new ObjectMapper();
    // The same bounded contract applies when producing and consuming archives.
    static final long MAX_ENTRY_BYTES=4L*1024*1024*1024;
    static final long MAX_TOTAL_BYTES=8L*1024*1024*1024;
    private static final int MAX_MANIFEST_BYTES=64*1024*1024;
    private static final class Budget {
        long total;
        void add(long entry,long bytes)throws IOException{
            total+=bytes;
            if(entry>MAX_ENTRY_BYTES||total>MAX_TOTAL_BYTES)throw new IOException("Backup exceeds archive budget");
        }
    }
    private static String copy(InputStream in,OutputStream out,Budget budget)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[64*1024];long entry=0;
        for(int n;(n=in.read(buffer))!=-1;){entry+=n;budget.add(entry,n);digest.update(buffer,0,n);out.write(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private HybridBackupService() {}
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static boolean included(String path){return path.equals("MEMORY.md")||path.equals("AGENTS.md")||path.endsWith(".md")&&(path.startsWith("knowledge/")||path.startsWith("memory/"));}
    /** Caller pauses application writes until this returns. External edits are detected and fail the snapshot. */
    public static void backup(KnowledgeStore store,Path destination){
        Path snapshot=null,archive=null;
        try{
            if(Files.exists(destination))throw new IOException("Backup target already exists");
            Path parent=destination.toAbsolutePath().getParent();Files.createDirectories(parent);
            snapshot=Files.createTempFile(parent,"kelly-data-",".db");Files.delete(snapshot);store.todos().backup(snapshot);
            archive=Files.createTempFile(parent,"kelly-backup-",".zip");
            Map<String,String> manifest=new TreeMap<>();
            MarkdownDocumentStore files=new MarkdownDocumentStore(store.workspace());
            List<String> paths;
            try(var stream=Files.walk(store.workspace())){paths=stream.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).map(p->store.workspace().relativize(p).toString().replace('\\','/')).filter(HybridBackupService::included).sorted().toList();}
            try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){
                Budget budget=new Budget();
                zip.putNextEntry(new ZipEntry(".kelly-data.db"));
                try(var in=Files.newInputStream(snapshot)){manifest.put(".kelly-data.db",copy(in,zip,budget));}
                zip.closeEntry();
                for(String path:paths){byte[] bytes=files.readText(path).getBytes(StandardCharsets.UTF_8);budget.add(bytes.length,bytes.length);manifest.put(path,hash(bytes));put(zip,path,bytes);}
                for(String path:paths)if(!manifest.get(path).equals(hash(files.readText(path).getBytes(StandardCharsets.UTF_8))))throw new IOException("File changed during backup: "+path);
                List<String> after;try(var stream=Files.walk(store.workspace())){after=stream.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).map(p->store.workspace().relativize(p).toString().replace('\\','/')).filter(HybridBackupService::included).sorted().toList();}
                if(!after.equals(paths))throw new IOException("File set changed during backup");
                byte[] metadata=JSON.writeValueAsBytes(manifest);
                if(metadata.length>MAX_MANIFEST_BYTES)throw new IOException("Backup manifest too large");
                budget.add(metadata.length,metadata.length);put(zip,"manifest.json",metadata);
            }
            Files.move(archive,destination);archive=null;
        }catch(Exception e){throw new StorageException(StorageException.Code.IO_ERROR,"Backup failed: "+e.getMessage(),e);}
        finally{for(Path p:new Path[]{snapshot,archive})if(p!=null)try{Files.deleteIfExists(p);}catch(IOException ignored){}}
    }
    private static void put(ZipOutputStream zip,String name,byte[] bytes)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
    /** Restore only into a new directory; failure removes only the staging directory created by this call. */
    public static void restore(Path archive,Path destination){
        Path staging=null;
        try{
            if(Files.exists(destination))throw new IOException("Restore target must not exist");
            Path parent=destination.toAbsolutePath().getParent();Files.createDirectories(parent);staging=Files.createTempDirectory(parent,"kelly-restore-");
            Map<String,String> hashes=new TreeMap<>();Map<String,String> manifest=null;Budget budget=new Budget();
            try(var zip=new ZipInputStream(Files.newInputStream(archive))){for(ZipEntry e;(e=zip.getNextEntry())!=null;){
                String name=e.getName();if(e.isDirectory()||hashes.containsKey(name)||name.contains("\\")||name.contains(":")||name.startsWith("/")||Arrays.asList(name.split("/")).contains("..")||!(included(name)||name.equals(".kelly-data.db")||name.equals("manifest.json")))throw new IOException("Invalid backup entry");
                if(!Path.of(name).normalize().toString().replace('\\','/').equals(name))throw new IOException("Non-canonical backup path");
                if(name.equals("manifest.json")){
                    if(manifest!=null)throw new IOException("Duplicate manifest");
                    byte[] bytes=zip.readNBytes(MAX_MANIFEST_BYTES+1);
                    if(bytes.length>MAX_MANIFEST_BYTES)throw new IOException("Backup manifest too large");
                    budget.add(bytes.length,bytes.length);
                    manifest=JSON.readValue(bytes,new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});continue;
                }
                Path path=staging.resolve(name).normalize();if(!path.startsWith(staging))throw new IOException("Invalid path");
                Files.createDirectories(path.getParent());
                try(var out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){
                    hashes.put(name,copy(zip,out,budget));
                }
            }}
            if(manifest==null||!manifest.equals(hashes)||!hashes.containsKey(".kelly-data.db"))throw new IOException("Backup integrity check failed");
            try(var c=DatabaseBootstrap.open(staging,false);var s=c.createStatement();var r=s.executeQuery("PRAGMA integrity_check")){if(!r.next()||!r.getString(1).equals("ok"))throw new IOException("Invalid database snapshot");}
            Files.move(staging,destination);staging=null;
        }catch(Exception e){throw new StorageException(StorageException.Code.IO_ERROR,"Restore failed: "+e.getMessage(),e);}
        finally{if(staging!=null)try(var paths=Files.walk(staging)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(IOException ignored){}}
    }
}
