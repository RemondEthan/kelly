package com.mordor.kelly.kelsy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mordor.kelly.kelsy.todo.*;
import com.mordor.kelly.kelsy.storage.StorageException;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.*;
import java.util.*;

/** Structured task operations. All JDBC work runs off the UI/event thread. */
public final class TodoAgentTool implements AgentTool {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final String operation;
    private final TodoRepository repository;
    public TodoAgentTool(String operation,TodoRepository repository){this.operation=operation;this.repository=repository;}
    public static void install(Toolkit toolkit,TodoRepository repository){
        for(String op:List.of("create","get","list","update","complete","reopen","delete"))toolkit.registerAgentTool(new TodoAgentTool(op,repository));
    }
    @Override public String getName(){return "todo_"+operation;}
    @Override public String getDescription(){return "Manage SQLite tasks: "+operation+". Tasks are never stored in files. Mutations require the latest expectedVersion.";}
    @Override public boolean isReadOnly(){return operation.equals("get")||operation.equals("list");}
    @Override public Map<String,Object> getParameters(){
        Map<String,Object> props=new LinkedHashMap<>(); List<String> required=new ArrayList<>();
        if(!operation.equals("create")&&!operation.equals("list")){props.put("uid",Map.of("type","string"));required.add("uid");}
        if(List.of("update","complete","reopen","delete").contains(operation)){props.put("expectedVersion",Map.of("type","integer","minimum",1));required.add("expectedVersion");}
        if(operation.equals("create")||operation.equals("update")){
            props.put("title",Map.of("type","string"));props.put("descriptionMd",Map.of("type","string"));props.put("dueDate",Map.of("type",List.of("string","null"),"description","ISO date YYYY-MM-DD or null"));props.put("priority",Map.of("type","integer","minimum",0,"maximum",3));required.add("title");
            props.put("sources",Map.of("type","array","items",Map.of("type","object","properties",Map.of("documentUid",Map.of("type","string"),"lastKnownPath",Map.of("type","string"),"sourceHash",Map.of("type","string"),"heading",Map.of("type","string")),"required",List.of("documentUid","lastKnownPath","sourceHash"))));
        }
        if(operation.equals("update")) required.addAll(List.of("descriptionMd","dueDate","priority","sources"));
        if(operation.equals("list"))for(String key:List.of("status","from","before","dueMode","keywords","cursor"))props.put(key,Map.of("type","string"));
        if(operation.equals("list"))props.put("limit",Map.of("type","integer","minimum",1,"maximum",50));
        return Map.of("type","object","properties",props,"required",required,"additionalProperties",false);
    }
    @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param){
        return Mono.fromCallable(()->execute(param==null||param.getInput()==null?Map.of():param.getInput())).subscribeOn(Schedulers.boundedElastic());
    }
    private ToolResultBlock execute(Map<String,Object> input){
        try{
            Object data;
            if(operation.equals("create"))data=view(repository.create(input(input)));
            else if(operation.equals("list")){
                var page=repository.list(new TodoRepository.Query(status(input),date(input,"from"),date(input,"before"),TodoRepository.DueMode.valueOf(text(input,"dueMode","any").toUpperCase(Locale.ROOT)),text(input,"keywords",""),number(input,"limit",20).intValue(),text(input,"cursor",null)));
                Map<String,Object> result=new LinkedHashMap<>();result.put("items",page.items().stream().map(TodoAgentTool::view).toList());result.put("nextCursor",page.nextCursor());data=result;
            }else{
                UUID uid=UUID.fromString(text(input,"uid",null));
                data=view(switch(operation){
                    case "get"->repository.get(uid);
                    case "update"->repository.edit(uid,number(input,"expectedVersion",null).longValue(),input(input));
                    case "complete"->repository.complete(uid,number(input,"expectedVersion",null).longValue());
                    case "reopen"->repository.reopen(uid,number(input,"expectedVersion",null).longValue());
                    case "delete"->repository.delete(uid,number(input,"expectedVersion",null).longValue());
                    default->throw new IllegalArgumentException("Unknown operation");
                });
            }
            return ToolResultBlock.text(JSON.writeValueAsString(Map.of("ok",true,"data",data)));
        }catch(Exception e){
            String code=e instanceof StorageException se?se.code().name():"INVALID_ARGUMENT";
            try{return ToolResultBlock.text(JSON.writeValueAsString(Map.of("ok",false,"error",Map.of("code",code,"message",Objects.toString(e.getMessage(),code),"retryable",false))));}catch(Exception impossible){throw new IllegalStateException(impossible);}
        }
    }
    private TodoRepository.Input input(Map<String,Object> in){
        List<Todo.Source> sources=new ArrayList<>();
        Object raw=in.get("sources");
        if(raw!=null){if(!(raw instanceof List<?> values))throw new IllegalArgumentException("Invalid sources");for(Object item:values){if(!(item instanceof Map<?,?> value))throw new IllegalArgumentException("Invalid source");sources.add(new Todo.Source(UUID.fromString(Objects.toString(value.get("documentUid"),"")),Objects.toString(value.get("lastKnownPath"),""),Objects.toString(value.get("sourceHash"),""),Objects.toString(value.get("heading"),null)));}}
        return new TodoRepository.Input(text(in,"title",null),text(in,"descriptionMd",""),date(in,"dueDate"),number(in,"priority",0).intValue(),sources);
    }
    private static TodoStatus status(Map<String,Object> in){String value=text(in,"status","open");return value.equals("all")?null:TodoStatus.valueOf(value.toUpperCase(Locale.ROOT));}
    private static String text(Map<String,Object> in,String key,String fallback){Object v=in.get(key);return v==null?fallback:v.toString();}
    private static Number number(Map<String,Object> in,String key,Number fallback){Object v=in.get(key);if(v==null){if(fallback==null)throw new IllegalArgumentException("Missing "+key);return fallback;}if(!(v instanceof Number n)||n.doubleValue()!=n.longValue())throw new IllegalArgumentException("Invalid integer "+key);return n;}
    private static LocalDate date(Map<String,Object> in,String key){String v=text(in,key,null);return v==null||v.isBlank()?null:LocalDate.parse(v);}
    public static Map<String,Object> view(Todo t){
        Map<String,Object> v=new LinkedHashMap<>();v.put("uid",t.uid().toString());v.put("citation","todo:"+t.uid());v.put("title",t.title());v.put("descriptionMd",t.descriptionMd());v.put("status",t.status().name().toLowerCase(Locale.ROOT));v.put("dueDate",t.dueDate()==null?null:t.dueDate().toString());v.put("priority",t.priority());v.put("version",t.version());v.put("sources",t.sources().stream().map(source->Map.of("documentUid",source.documentUid().toString(),"lastKnownPath",source.lastKnownPath(),"sourceHash",source.sourceHash(),"heading",Objects.toString(source.heading(),""))).toList());return v;
    }
}
