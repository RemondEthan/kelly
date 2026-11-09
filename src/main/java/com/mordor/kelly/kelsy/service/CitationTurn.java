/**
 * 引用轮次管理器。
 *
 * <p>跟踪一轮对话中 AI 助手引用了哪些知识库文件。
 * 当 AI 调用检索工具（如 memory_get、memory_search、knowledge_search、read_file、list_files）时，
 * 此类负责提取被引用的文件路径，并在轮次结束时提交到"已展示"列表。
 *
 * <p>AI Agent 知识引用工作流程：
 * <pre>
 *   1. beginAsk()          → 开始新一轮对话，清空待处理路径
 *   2. beginTool("xxx")    → AI 开始调用工具
 *   3. appendToolArgs()    → 追加工具参数（可能包含路径）
 *   4. appendToolResult()  → 追加工具结果（可能包含路径）
 *   5. extractCurrent()    → 从参数和结果中提取知识库路径
 *   6. commitIfRetrieved() → 如果有引用，提交到"已展示"列表
 * </pre>
 *
 * <p>检索工具类型：
 * <ul>
 *   <li><b>memory_get</b> - 获取记忆内容</li>
 *   <li><b>memory_search</b> - 搜索记忆</li>
 *   <li><b>knowledge_search</b> - 按 FTS 检索知识卡片</li>
 *   <li><b>read_file</b> - 读取文件内容</li>
 *   <li><b>list_files</b> - 列出目录文件</li>
 * </ul>
 *
 * <p>"已展示"列表用于知识面板（KnowledgePane）显示本轮引用的来源文件，
 * 帮助用户理解 AI 回复的信息来源。
 *
 * @see KnowledgePathExtractor
 * @see KnowledgePane
 */
package com.mordor.kelly.kelsy.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CitationTurn {

    /** 检索类工具名称集合：这些工具的参数/结果中可能包含知识库路径 */
    private static final Set<String> RETRIEVAL = Set.of(
            "memory_get", "memory_search", "knowledge_search", "knowledge_get", "todo_get", "todo_list", "read_file", "list_files");

    /** 已提交的引用路径列表（本轮对话中确认引用的文件） */
    private final List<String> shown = new ArrayList<>();

    /** 待处理的引用路径集合（尚未提交，等待确认） */
    private final LinkedHashSet<String> pending = new LinkedHashSet<>();

    /** 当前正在处理的工具名称 */
    private final StringBuilder toolArgs = new StringBuilder();

    /** 当前工具的参数文本 */
    private final StringBuilder toolResult = new StringBuilder();

    /** 当前工具的结果文本 */
    private String currentTool = "";
    public record Evidence(String reference,String path,String hash,Long version) {}
    private final java.util.Map<String,Transcript> transcripts=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,Evidence> evidence=new java.util.LinkedHashMap<>();
    private String currentId="";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static final class Transcript {
        final String name;final StringBuilder result=new StringBuilder();boolean failed;
        Transcript(String name){this.name=name;}
    }
    public List<Evidence> evidence(){return List.copyOf(evidence.values());}


    /**
     * 判断工具是否为检索类工具。
     * 检索类工具的参数和结果中可能包含知识库文件路径。
     *
     * @param name 工具名称
     * @return true 如果是检索类工具
     */
    public static boolean isRetrievalTool(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return RETRIEVAL.contains(name.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * 开始新一轮对话：清空待处理路径和当前工具状态。
     */
    public void beginAsk() {
        pending.clear();shown.clear();evidence.clear();transcripts.clear();
        resetTool();
    }

    /**
     * 开始处理新的工具调用。
     *
     * @param name 工具名称
     */
    public void beginTool(String name) {beginTool(name,name);}
    public void beginTool(String id,String name) {
        currentId=id;
        transcripts.put(id,new Transcript(name));
        currentTool = name == null ? "" : name;
        toolArgs.setLength(0);
        toolResult.setLength(0);
    }

    /**
     * 追加工具调用参数增量。
     * 自动从参数中提取知识库路径。
     *
     * @param delta 参数增量片段
     */
    public void appendToolArgs(String delta) {
        if (delta != null && !delta.isEmpty()) {
            toolArgs.append(delta);
        }
    }
    public void appendToolArgs(String id,String delta){if(id.equals(currentId))appendToolArgs(delta);}

    /**
     * 追加工具返回结果增量。
     * 自动从结果中提取知识库路径。
     *
     * @param delta 结果增量片段
     */
    public void appendToolResult(String delta) {
        if (delta != null && !delta.isEmpty()) {
            toolResult.append(delta);
        }
        Transcript transcript=transcripts.get(currentId);
        if(transcript!=null&&delta!=null)transcript.result.append(delta);
    }
    public void appendToolResult(String id,String delta){
        if(id.equals(currentId))appendToolResult(delta);
        else {Transcript transcript=transcripts.get(id);if(transcript!=null&&delta!=null)transcript.result.append(delta);}
    }
    public void finishTool(String id,boolean success){
        Transcript transcript=transcripts.get(id);if(transcript!=null)transcript.failed=!success;
    }
    /** Parse complete tool envelopes once, never arguments, directory listings or generated prose. */
    public void finishTools(){
        for(Transcript transcript:transcripts.values()){
            if(transcript.failed||!Set.of("knowledge_get","todo_get","todo_list").contains(transcript.name))continue;
            try{
                com.fasterxml.jackson.databind.JsonNode envelope=JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(transcript.result.toString());
                if(!envelope.path("ok").asBoolean(false))continue;
                var data=envelope.path("data");
                if(transcript.name.equals("todo_list")){for(var task:data.path("items"))addTask(task);}
                else if(transcript.name.equals("todo_get"))addTask(data);
                else {
                    String path=data.path("path").asText();String hash=data.path("hash").asText();
                    if(!hash.matches("[0-9a-f]{64}")||!data.has("markdown")||!MarkdownDocumentStore.hash(data.path("markdown").asText()).equals(hash))continue;
                    String reference;
                    if(path.startsWith("knowledge/"))reference="article:"+java.util.UUID.fromString(data.path("uid").asText());
                    else if(path.equals("MEMORY.md")||path.startsWith("memory/"))reference=path;
                    else continue;
                    evidence.put(reference,new Evidence(reference,path,hash,null));pending.add(reference);
                }
            }catch(Exception ignored){/* Incomplete, failed or malformed tool output is not evidence. */}
        }
    }
    private void addTask(com.fasterxml.jackson.databind.JsonNode task){
        try{
            String ref="todo:"+java.util.UUID.fromString(task.path("uid").asText());long version=task.path("version").asLong();
            if(version<1)return;
            evidence.put(ref,new Evidence(ref,null,null,version));pending.add(ref);
        }catch(IllegalArgumentException ignored){}
    }

    /**
     * 获取待处理的引用路径列表。
     *
     * @return 待处理路径的副本
     */
    public List<String> pendingPaths() {
        return List.copyOf(pending);
    }

    /** 获取当前工具的完整参数文本 */
    public String toolArgs() {
        return toolArgs.toString();
    }

    /** 获取当前工具的完整文本（参数 + 结果） */
    public String toolText() {
        return toolArgs + "\n" + toolResult;
    }

    /**
     * 从当前工具的参数和结果中提取知识库路径。
     * 仅对检索类工具执行提取。
     */
    /** 重置当前工具状态 */
    private void resetTool() {
        currentTool = "";
        toolArgs.setLength(0);
        toolResult.setLength(0);
    }

    /**
     * 从文本中提取知识库路径并添加到待处理列表。
     *
     * @param text 包含路径的文本
     */
    public void addRetrievalText(String text) {
        for(String reference:KnowledgePathExtractor.all(text))if(evidence.containsKey(reference))pending.add(reference);
    }

    /**
     * 批量添加路径到待处理列表。
     *
     * @param paths 路径列表
     */
    public void addPaths(List<String> paths) {
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            if (p != null && !p.isBlank() && !KnowledgeStore.isKnowledgeCatalog(p)) {
                pending.add(p);
            }
        }
    }

    /**
     * 提交待处理路径到已展示列表（如果有待处理路径）。
     *
     * @return true 如果有路径被提交
     */
    public boolean commitIfRetrieved() {
        if (pending.isEmpty()) {
            return false;
        }
        shown.clear();
        shown.addAll(pending);
        pending.clear();
        return true;
    }

    /**
     * 获取已展示的引用路径列表。
     *
     * @return 已展示路径的副本
     */
    public List<String> shown() {
        return List.copyOf(shown);
    }

    /**
     * 获取最后一个已展示的路径。
     *
     * @return 最后一个路径，无则返回 null
     */
    public String lastShown() {
        return shown.isEmpty() ? null : shown.get(shown.size() - 1);
    }

    /**
     * 获取用于知识面板显示的路径。
     * 优先返回知识卡片路径（knowledge/ 开头且非 KNOWLEDGE.md），
     * 否则返回最后一个已展示路径。
     *
     * @return 证据路径
     */
    public String evidencePath() {
        for (int i = shown.size() - 1; i >= 0; i--) {
            String path = shown.get(i);
            if (isEvidenceCard(path)) {
                return path;
            }
        }
        return lastShown();
    }

    /**
     * 判断路径是否为知识卡片（knowledge/ 目录下的非索引文件）。
     */
    private static boolean isEvidenceCard(String path) {
        return KnowledgeStore.isKnowledgeCard(path);
    }

    /**
     * 获取第一个已展示的路径。
     *
     * @return 第一个路径，无则返回 null
     */
    public String firstShown() {
        return shown.isEmpty() ? null : shown.get(0);
    }

    /**
     * 替换已展示的路径列表。
     *
     * @param paths 新的路径列表
     */
    public void replaceShown(List<String> paths) {
        shown.clear();
        addAllShown(paths);
    }

    /** 批量添加路径到已展示列表 */
    private void addAllShown(List<String> paths) {
        if (paths == null) {
            return;
        }
        for (String p : paths) {
            if (p != null && !p.isBlank()) {
                shown.add(p);
            }
        }
    }
}
