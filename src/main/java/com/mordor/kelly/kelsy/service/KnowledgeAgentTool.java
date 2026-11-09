package com.mordor.kelly.kelsy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mordor.kelly.kelsy.storage.StorageException;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.*;
import java.util.*;

/** File-backed article operations with optimistic hash checking and stable IDs. */
public final class KnowledgeAgentTool implements AgentTool {
    private final String op;private final KnowledgeStore store;private static final ObjectMapper JSON=new ObjectMapper();
    public KnowledgeAgentTool(String op,KnowledgeStore store){this.op=op;this.store=store;}
    public static void install(Toolkit tools,KnowledgeStore store){for(String op:List.of("get","create","update"))tools.registerAgentTool(new KnowledgeAgentTool(op,store));}
    @Override public String getName(){return "knowledge_"+op;}
    @Override public String getDescription(){return "Read/create/update a Markdown article. Use get before update and supply expectedHash. Never use these tools for tasks.";}
    @Override public boolean isReadOnly(){return op.equals("get");}
    @Override public Map<String,Object> getParameters(){
        Map<String,Object> properties=new LinkedHashMap<>();List<String> required=new ArrayList<>();
        properties.put("path",Map.of("type","string","description","Relative article path; optional fallback for get by uid"));
        if(op.equals("get")){properties.put("uid",Map.of("type","string"));properties.put("expectedHash",Map.of("type","string"));}
        else required.add("path");
        if(op.equals("create")){
            for(String key:List.of("title","kind","bodyMarkdown","date"))properties.put(key,Map.of("type","string"));
            for(String key:List.of("aliases","tags"))properties.put(key,Map.of("type","array","items",Map.of("type","string")));
            required.addAll(List.of("title","kind","bodyMarkdown"));
        }
        if(op.equals("update")){properties.put("expectedHash",Map.of("type","string"));properties.put("markdown",Map.of("type","string"));required.addAll(List.of("expectedHash","markdown"));}
        return Map.of("type","object","properties",properties,"required",required,"additionalProperties",false);
    }
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param){return Mono.fromCallable(()->execute(param==null||param.getInput()==null?Map.of():param.getInput())).subscribeOn(Schedulers.boundedElastic());}
    private ToolResultBlock execute(Map<String,Object> in){try{
        MarkdownDocumentStore files=new MarkdownDocumentStore(store.workspace());
        String path=text(in,"path",null);String uid=text(in,"uid",null);
        if(uid!=null){String fallback=path;
            if(store.index()!=null)try{path=store.index().pathFor(UUID.fromString(uid));}catch(StorageException e){if(fallback==null)throw e;}
            else if(fallback==null)throw new StorageException(StorageException.Code.INDEX_UNAVAILABLE,"Provide path while the index is unavailable");
        }
        MarkdownDocumentStore.Article article;
        switch(op){
            case "get" -> {
                if(path==null)throw new IllegalArgumentException("uid or path is required");
                if(path.equals("MEMORY.md")||path.startsWith("memory/")){
                    String raw=files.readText(path);String hash=MarkdownDocumentStore.hash(raw);String expected=text(in,"expectedHash",null);
                    if(expected!=null&&!expected.equals(hash))throw new StorageException(StorageException.Code.CONFLICT,"File changed");
                    return result(Map.of("path",path,"markdown",raw,"hash",hash));
                }
                String raw=files.readText(path);
                if(!raw.replace("\r\n","\n").startsWith("---\n")){
                    String hash=MarkdownDocumentStore.hash(raw),expected=text(in,"expectedHash",null);
                    if(expected!=null&&!expected.equals(hash))throw new StorageException(StorageException.Code.CONFLICT,"File changed");
                    if(uid!=null)throw new StorageException(StorageException.Code.IDENTITY_CONFLICT,"Legacy file has no article ID");
                    return result(Map.of("path",path,"markdown",raw,"hash",hash,"metadataRequired",true));
                }
                article=files.read(path,text(in,"expectedHash",null));
                if(uid!=null&&!article.uid().toString().equals(uid))throw new StorageException(StorageException.Code.IDENTITY_CONFLICT,"File ID differs");
            }
            case "create" -> article=files.create(path,text(in,"title",null),text(in,"kind",null),strings(in,"aliases"),strings(in,"tags"),in.get("date")==null?null:LocalDate.parse(in.get("date").toString()),text(in,"bodyMarkdown",""));
            case "update" -> article=files.update(path,text(in,"expectedHash",null),text(in,"markdown",null));
            default -> throw new IllegalArgumentException("Unknown operation");
        }
        boolean indexed=store.index()!=null;if(!op.equals("get"))try{store.upsert(path);}catch(RuntimeException e){indexed=false;}
        Map<String,Object> data=new LinkedHashMap<>();data.put("uid",article.uid().toString());data.put("path",article.path());data.put("citation","article:"+article.uid());data.put("markdown",article.markdown());data.put("hash",article.hash());data.put("indexReady",indexed);return result(data);
    }catch(Exception e){String code=e instanceof StorageException se?se.code().name():"INVALID_ARGUMENT";try{return ToolResultBlock.text(JSON.writeValueAsString(Map.of("ok",false,"error",Map.of("code",code,"message",Objects.toString(e.getMessage(),code),"retryable",false))));}catch(Exception impossible){throw new IllegalStateException(impossible);}}}
    private static ToolResultBlock result(Object data)throws Exception{return ToolResultBlock.text(JSON.writeValueAsString(Map.of("ok",true,"data",data)));}
    private static String text(Map<String,Object> in,String key,String fallback){return in.get(key)==null?fallback:in.get(key).toString();}
    private static List<String> strings(Map<String,Object> in,String key){Object v=in.get(key);if(v==null)return List.of();if(!(v instanceof List<?> list))throw new IllegalArgumentException("Invalid "+key);return list.stream().map(Object::toString).toList();}
}
