# Kelsy(智能秘书)知识库 —— 架构探索报告

## 执行摘要

Kelsy 是构建在 [AgentScope](https://github.com/agentscope-ai/agentscope) 2.0.1(`io.agentscope:agentscope-harness`)之上的个人 AI 助理。**没有 embedding/向量存储,没有 ingestion 流水线,没有 chunking,也没有独立索引。**所谓"知识库"就是磁盘上一个人工编辑的 Markdown 文件目录。检索完全靠 **JVM 内部的纯关键字扫描**,不调用任何模型。LLM 是 AgentScope 的 `HarnessAgent`,通过 `OpenAIChatModel` 接入 OpenAI 兼容的 provider(MiniMax、Kimi、GLM、DeepSeek)。引用(citation)是靠正则去匹配 LLM 在工具参数/结果里"碰巧"回显的知识路径,从而逆向重建出来的。

---

## 1. 架构 / 组件

### 1.1 UI 入口 → LLM 调用的调用链

```
ChatPane (BorderPane)
  └─ ChatController.send(content)                       ChatController.java:387
       └─ KelsySendRouter.route(...)                   KelsySendRouter.java:99
            └─ SlashCommands.parse(body)               SlashCommands.java:72
       ├─ case ASK  → startAsk(outgoing)               ChatController.java:529
       └─ case FIND → runFind(query)                   ChatController.java:804
                       └─ KnowledgeStore.search        KnowledgeStore.java:231

startAsk(outgoing):
  runtime.ensureAssistant()                            KelsyRuntime.java:179
    └─ LocalAssistantService.create(cfg, userId)       LocalAssistantService.java:113
  assistant.chat(text, ReplyHandler)                   LocalAssistantService.java:151
    └─ agent.streamEvents(text, context)               LocalAssistantService.java:152
         └─ HarnessAgent (AgentScope 2.0.1)            pom.xml:117-120
              └─ OpenAIChatModel                       ModelFactory.java:116
```

### 1.2 类地图

| 层次 | 类 | 职责 |
|---|---|---|
| UI 顶层 | `ChatPane` | `BorderPane`;开启时右侧为 `KnowledgePane`,装在 `SplitPane`(70/30)里(`ChatPane.java:200-206`) |
| Controller | `ChatController` | 持有响应式状态(`kelsyBusy`、`liveAssistant`、`citations`、`memoryWarn`);把每条 send 路由到 `KelsySendRouter`;把 `AgentEvent` 流式片段转换成 JavaFX property 更新 |
| Runtime | `KelsyRuntime` | 单例(`KelsyRuntime.java:85`);通过注入的 factory `Function<KelsyConfig, AssistantService>` 懒构造 assistant |
| 服务接口 | `AssistantService` | 纯 Java 契约,不暴露 AgentScope(`AssistantService.java:51`);回调 `ReplyHandler` 暴露 `onTextDelta`、`onThinkingDelta`、`onToolCall/Args/Result`、`onComplete`、`onError` |
| 服务实现 | `LocalAssistantService` | 包 `HarnessAgent`。`streamEvents` 跑在 `Schedulers.boundedElastic()`;`dispatch()` switch 把 `AgentEvent` 映射到 `ReplyHandler`(`LocalAssistantService.java:186-200`) |
| 配置 | `KelsyConfig` / `ConfigLoader` | 从 `~/.kelly/kelsy/config.json` 加载的 JSON record |
| Providers | `ProviderCatalog` + `ProviderSpec` | 从 classpath 加载 `providers.json`(DCL 缓存) |
| Model | `ModelFactory` | 用 provider 专属的 `OpenAIBaseFormatter` 构造 `OpenAIChatModel` |
| 路由 | `KelsySendRouter` | `KelsyMention` 检查 → `SlashCommands.parse` → ASK/FIND/PEER/... |
| Mention | `KelsyMention` | 大小写不敏感的 `@nickname ` 前缀匹配;默认昵称 `RoomMember.SECRETARY_NAME` |
| 知识检索 | `KnowledgeStore`(文件操作)、`FindQuery`(日期+关键字解析)、`KnowledgePathExtractor`(正则路径抽取) |
| 引用追踪 | `CitationTurn` | 缓冲 `memory_get`/`memory_search`/`read_file`/`list_files` 的工具参数/结果;用正则抽取路径 |
| 斜杠 UI | `SlashCommands` | `/note`、`/today`、`/tidy`、`/find` |
| 种子 | `WorkspaceSeeder` | 首次运行时从 classpath 拷贝模板 |
| KnowledgePane | `KnowledgePane` | 顶部:来源链接列表;中部:被选文件的 `MarkdownView` |
| 气泡 | `AssistantBubble` | 渲染 TEXT / THINKING(可折叠)/ TOOL(卡片)块;若内容匹配提醒格式,渲染 `reminderBox` |

### 1.3 `AssistantService` 与 `LocalAssistantService` 的关系

`AssistantService` 是 UI 层唯一接触的接口,定义在 `AssistantService.java:51`,内嵌 `ReplyHandler` 接口。`LocalAssistantService` 是唯一的进程内实现,`KelsyRuntime` 通过注入的 `Function<KelsyConfig, AssistantService> factory`(`KelsyRuntime.java:52, 94-95`)调用它,这就是唯一的扩展点 —— 目前没有远程 / HTTP 实现(Javadoc 在 `AssistantService.java:46-48` 显式预留了这个口子)。

### 1.4 Ingestion 流水线:**不存在**

没有 loader、chunker、embedder 或索引构建器。`WorkspaceSeeder` 只种:
- `AGENTS.md`、`MEMORY.md`、`knowledge/KNOWLEDGE.md`(write-if-absent,`WorkspaceSeeder.java:60-65`)
- 7 个 `knowledge/<sub>` 子目录(`WorkspaceSeeder.java:66-69`)
- skill 包 `skills/kelsy-knowledge/SKILL.md` 和 `references/examples.md`(write-always,`WorkspaceSeeder.java:71-76`)

所有其他内容(会议卡、决策卡、待办卡、每日日志 `memory/YYYY-MM-DD.md` 等)**由 LLM 在运行时自己创建**,依靠 AgentScope 的 filesystem/memory 工具,遵循 `SKILL.md` 里的规则。`WorkspaceSeeder` 在 `KelsyRuntime.open()`(`KelsyRuntime.java:131-132`)和 `LocalAssistantService.create()`(`LocalAssistantService.java:115-116`)都会跑 —— 所以首次启动跑两次。

### 1.5 知识在哪里被**检索**

三条检索路径,全部基于关键字,全部 JVM 内部:

1. **`/find <query>`** —— `ChatController.runFind`(`ChatController.java:804-818`) → `KnowledgeStore.search(FindQuery.parse(query, today))` → 返回行级 `Hit` 记录,在聊天中显示。
2. **对话回合中 AgentScope 的工具调用** —— LLM 自主调用 `memory_get`、`memory_search`、`read_file`、`list_files`。被它触碰的文件路径名,由 `CitationTurn` + `KnowledgePathExtractor` 从流式工具参数/结果文本里抠出来。
3. **本地证据补全** —— 每回合结束后,`ChatController.addLocalEvidence`(`ChatController.java:724-742`)调用 `LocalEvidence.mentionsTodos/Meetings/Decisions`(在 `待办/会议/纪要/决定` 上做 substring 匹配)和 `LocalEvidence.terms(outgoing)`(去掉停用词和标点后用 `FindQuery.parse` 抽关键字)把匹配到的卡片路径追加到引用列表,所以即便 LLM 忘了暴露,右栏也会显示相关卡片。

整个 kelsy 包里没有 vector store、没有 ANN 索引、没有 reranker、没有任何 embedding API 调用。用 `grep -rn "embedding\|vector\|VectorStore\|Embedder\|EmbedModel" /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/kelsy/` 验证,零命中。

---

## 2. 存储

### 2.1 路径(`KelsyPaths.java:24`)

```
~/.kelly/kelsy/config.json                        (KelsyPaths.java:58)
~/.kelly/kelsy/workspace/                         (KelsyPaths.java:59)
~/.kelsy/config.json          ← legacy,会自动迁移  (KelsyPaths.java:60, ConfigLoader.java:96-103)
```

`KelsyConfig.java:65-70` 的 `workspacePath()` 识别 `workspaceDir` 中的 `~` 前缀,`KelsyRuntime.resolve()`(`KelsyRuntime.java:67-69`)读取它,允许用户把 workspace 指到其他盘。

### 2.2 Workspace 布局(参考 `WorkspaceSeeder.seed` + `KnowledgeStore.list`)

```
~/.kelly/kelsy/workspace/
  AGENTS.md                 (系统提示锚点;只在根,不在每个用户下)
  MEMORY.md                 (最大约 8KB;超过 MEMORY_WARN_BYTES UI 会提醒)
  KNOWLEDGE.md
  knowledge/
    KNOWLEDGE.md            (目录文件;cardContaining 结果会排除它)
    people/  projects/  playbooks/  inbox/
    meetings/   decisions/   todos/
  memory/                   (每日日志,命名 YYYY-MM-DD.md)
  sessions/                 (原始聊天 dump;SKILL.md 说"不要当事实")
  skills/kelsy-knowledge/
    SKILL.md                (每次启动重写)
    references/examples.md  (每次启动重写)
```

**用户隔离**是通过 workspace 路径实现的:`KnowledgeStore.knowledgeRoot(workspace, username)` 在 username 非空时返回 `workspace/<username>/`,且用严格校验保证不逃逸出 workspace(`KnowledgeStore.java:133-144`)。`KelsyRuntime.store(username)` 调用它(`KelsyRuntime.java:161-163`)。

### 2.3 存储格式

纯 UTF-8 Markdown。**没有 JSON,没有 sqlite,没有向量索引。**`KnowledgeStore.read()` 就只是 `Files.readString`(`KnowledgeStore.java:179`),带 256 KB 上限(`KnowledgeStore.java:56`,`MAX_FILE_BYTES = 256L * 1024`)。

Markdown 之外唯一"结构化"的文件是 `~/.kelly/kelsy/config.json`(Jackson 序列化),存的是 `model.{provider,apiKey,baseUrl,modelName}`、`workspaceDir`、`lastUsername`、`selfAvatarPath`、`kelsyAvatarPath`(`KelsyConfig.java:27-32`)。每个房间的设置(`enabled`、`avatarPath`、`nickname`)存在 `java.util.prefs.Preferences` 里,以 IM code 的 SHA-256 作 key(`KelsyRoomSettings.java:108-120`)。

### 2.4 Embeddings:**N/A**(没有任何代码路径计算或缓存它们)。

---

## 3. 检索流

### 3.1 @-mention → ASK

1. `KelsyMention.isMention(text, nickname)` —— 大小写不敏感的 `@nickname` 前缀,后跟空格或 EOF(`KelsyMention.java:57-67`)。
2. `KelsySendRouter.route(enabled, busy, configured, text, nickname)`(`KelsySendRouter.java:99-128`)—— 拒绝 BUSY / UNCONFIGURED / EMPTY_BODY / SLASH_ERROR;否则分派 ASK 或 FIND。
3. `SlashCommands.parse(body)`(`SlashCommands.java:72-99`)—— `/note`/`/today`/`/tidy` 改写成中文指令送给 LLM;`/find` 返回 `Result.find(query)`,router 映射成 `Kind.FIND`。
4. `ChatController.startAsk(outgoing)`(`ChatController.java:529-640`):
   - `citations.beginAsk()`
   - `kelsyBusy.set(true)`
   - `runtime.ensureAssistant()` → `LocalAssistantService.chat(outgoing, handler)`,跑在 `Schedulers.boundedElastic()`(`LocalAssistantService.java:152-153`)
   - 在这里构造的 `ReplyHandler`(`ChatController.java:539-639`)是干重活的:把文本 delta 追加到 `AssistantMessage`,为每次工具调用驱动 `citations`,`onComplete` 时跑 `addLocalEvidence`、`commitIfRetrieved`,然后自动开 `knowledgeVisible`,调 `onCitationSources`/`openKnowledge`,让右栏填满。

### 3.2 Assistant 怎么决定走 KB 还是靠模型自己答

它不决定 —— 由 LLM(AgentScope `HarnessAgent`)通过 tool-use 自己决定。AgentScope 给它的工具是:
- `memory_get` / `memory_search`(覆盖 workspace 的 `memory/` + `MEMORY.md`)
- `read_file` / `list_files`(覆盖整个 workspace 树)
- 加上每个会话的 `MEMORY.md` 由 harness 自动注入到系统提示

运行时侧的启发式只在 `LocalEvidence` 里(见上),**仅在回合结束后**用来补充引用列表,不参与 LLM 的路由。

### 3.3 引用的产出和渲染

引用路径来自三个地方,全部用 `KnowledgePathExtractor.PATH`(`KnowledgePathExtractor.java:38-39`)里同一个正则解析:

```
(?:MEMORY\.md|AGENTS\.md|memory/[\p{L}\p{N}._/-]+\.md|knowledge/[\p{L}\p{N}._/-]+\.md)
```

1. **流式工具参数/结果** —— `CitationTurn` 缓冲 `memory_get`/`memory_search`/`read_file`/`list_files`(即 `RETRIEVAL` 集合,`CitationTurn.java:43-44`)上每个 delta,然后在拼接文本上跑 `KnowledgePathExtractor.all`。
2. **回复正文** —— `onComplete` 时,`citations.addRetrievalText(reply.content())`(`ChatController.java:614`)对完整 assistant 文本跑同一个正则。这是为了捕捉模型行内写出来的路径(SKILL.md 里显式要求它这么写)。
3. **本地证据补全** —— `addLocalEvidence`(`ChatController.java:724-742`)。

然后 `commitIfRetrieved()` 把 `pending → shown`(`CitationTurn.java:187-195`)。`evidencePath()`(`CitationTurn.java:222-230`)优先选 `knowledge/.../*.md` 卡片,其次 `MEMORY.md`/`AGENTS.md`/日记。右栏 UI 通过 `onCitationSources` 收到列表,通过 `openKnowledge(evidencePath())`(`ChatController.java:619-622`)收到选中的文件,后者设 `knowledgeVisible=true` 并转发到 `KnowledgePane.setSources` + `KnowledgePane.open`。

`AssistantBubble` 把工具调用渲染成 `ToolCallCard`,带一个"open"链接,用的也是同一个 `onWorkspaceLink` 回调;Markdown 文本用 `MarkdownView` 渲染(`AssistantBubble.java:168-177, 278-286`)。

### 3.4 检索:仅关键字,行内 AND

`KnowledgeStore.search`(`KnowledgeStore.java:231-261`)按顺序扫三个 scope,**跨所有文件返回前 50 条行级匹配**:

```java
// KnowledgeStore.java:368-399 — scanFile
List<String> lines = Files.readAllLines(path);
for (int i = 0; i < lines.size(); i++) {
    String line = lines.get(i);
    if (line.isBlank()) continue;
    String lower = line.toLowerCase(Locale.ROOT);
    boolean all = query.keywords().isEmpty()
            || query.keywords().stream().allMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)));
    if (all) {
        String snippet = line.strip();
        if (snippet.length() > 120) snippet = snippet.substring(0, 120) + "…";
        hits.add(new Hit(relative, i + 1, snippet));
        if (hits.size() >= MAX_HITS) return;
    }
}
```

- `MAX_HITS = 50`(`KnowledgeStore.java:62`)
- `MAX_FILE_BYTES = 256 KB`(单文件上限,`KnowledgeStore.java:56`)
- `MEMORY_WARN_BYTES = 8 KB`(UI 提示阈值,`KnowledgeStore.java:59`)
- Snippet 截断:120 字符 + `…`(`KnowledgeStore.java:388-390`)
- 日记文件名过滤走 `FindQuery.matchesDailyFile`(`FindQuery.java:109-118`)—— 只匹配解析后日期范围内的 `YYYY-MM-DD.md`
- 关键字之间是 AND 逻辑;大小写不敏感的 `contains`(无 tokenization、无 stemming、无模糊匹配)

`KnowledgeStore.cardsContaining`(`addLocalEvidence` 用,`KnowledgeStore.java:279-303`)做一样的逐文件 `String.contains`,返回匹配的文件路径(上限 50,`KnowledgeStore.java:308-330`)。它还**跳过 `KNOWLEDGE.md` 自身**,避免目录文件影子挡住自己的卡片。

### 3.5 Embedding 模型:**kelly 应用本身不用任何 embedding**。AgentScope 的 `memory_*` 工具跑的是文本和文件路径,不进行任何 embedding。

---

## 4. Provider / 配置

### 4.1 Provider 选择

`ModelFactory.create` → `ModelFactory.resolve`(`ModelFactory.java:82-100`)拿用户的 `KelsyConfig.ModelSettings` 做:
1. `provider` 为空则默认 `"minimax"`。
2. 通过 `ProviderCatalog.findById(provider)` 查 `ProviderSpec`(对 `providers.json` 的列表做了 DCL 缓存,`ProviderCatalog.java:54-66`)。
3. 解析 `baseUrl` / `modelName`,`firstNonBlank(userValue, specDefault)`。
4. 选 formatter:
   ```java
   // ModelFactory.java:134-142
   private static OpenAIBaseFormatter formatterFor(String provider) {
       return switch (provider) {
           case "minimax"  -> new MiniMaxFormatter();
           case "kimi"     -> new KimiFormatter();
           case "glm"      -> new GLMFormatter();
           case "deepseek" -> new DeepSeekFormatter();
           default         -> null;
       };
   }
   ```
5. 构造 `OpenAIChatModel.builder().apiKey().baseUrl().modelName().formatter().stream(true).build()`(`ModelFactory.java:116-125`)。

### 4.2 支持的 provider(`providers.json`)

| id | displayName | baseUrl | defaultModelName |
|---|---|---|---|
| `minimax` | MiniMax | `https://api.minimaxi.com/v1` | `MiniMax-M3` |
| `kimi` | 月之暗面 Kimi | `https://api.moonshot.cn/v1` | `kimi-k2.5` |
| `glm` | 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-5.3` |
| `deepseek` | DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |

四家都是 OpenAI Chat-Completions 兼容。新增一个 provider 需要:(a) 在 `providers.json` 加一行;(b) 在 `ModelFactory.formatterFor` 加一个 case(必须继承 `agentscope-extensions-model-openai` 的 `OpenAIBaseFormatter`)。

### 4.3 API key / endpoint

存在 `~/.kelly/kelsy/config.json`,**明文 JSON**(`model.apiKey` 字段)。首次创建时,`ConfigLoader.ensureAndHasApiKey` 写入模板(`ConfigLoader.java:55-68`),然后 `chmod 600` 通过 `Files.setPosixFilePermissions(... "rw-------")`(`ConfigLoader.java:170-175`);在 Windows 上静默 no-op。Legacy 的 `~/.kelsy/config.json` 会被自动拷过来(`ConfigLoader.java:96-105`)。

### 4.4 什么会卡住一个能跑的 assistant

`ChatController.send`(`ChatController.java:391-394`)计算 `configured = enabled && runtime != null && runtime.hasApiKey()`,缺失时 UI 显示一条 system 消息,指向 config 文件路径(`ChatController.java:403-407`)。

---

## 5. UI

### 5.1 `KnowledgePane`(`KnowledgePane.java:41-166`)

布局:`BorderPane` 上面 `VBox sources`(顶部)+ `ScrollPane host`(中部)。`setSources(paths)` 被调时,会为每个被引用的文件生成一个 `Hyperlink`,标签是 `shortName(path)`,绑定 `link.setOnAction(e -> open(path))`。中部的 host pane 调 `KnowledgeStore.read(relativePath)`,根据 sealed 结果分支(`KnowledgePane.java:132-147`):

- `Ok` → `new MarkdownView(MarkdownRenderer.parse(markdown), this::open)`(commonmark,`open` 作为链接点击回调,使导航可递归)
- `Missing` → "文件不存在" label
- `TooLarge` → "文件过大,未渲染" label
- `Rejected` → 原因 label

它还会在 sources 变化或 refresh 被调时更新 `memoryWarn`(一个和 `ChatController` 共享的 `BooleanProperty`)。

它**是一个 Markdown 浏览器,不是 chunk 列表** —— 没有源树、没有 chunk 导航器、除了路径外没有预览元数据,只有一个"本轮没有引用原文"的空状态。

### 5.2 斜杠命令面

- **解析**:`SlashCommands.parse`(`SlashCommands.java:72-99`)识别 `/note`、`/today`、`/tidy`、`/find`。任何其他以 `/` 开头的会落到 `Result.send(text)`(原样送给 LLM)。
- **触发**:`KelsySendRouter.route`,在剥离 `@nickname` 前缀之后(`KelsySendRouter.java:115-120`)。
- **UI 露出**:没有 popup/自动补全 —— `InputBar`/`MentionPopover` 只建议成员 @(`MentionPopover.java`、`MentionQuery.java`)。用户得手敲 `/`。
- **改写**:`/note`、`/today`、`/tidy` 被转成长长的中文指令送给 LLM。`/find` 在 router 里被截走,根本到不了 LLM。

### 5.3 布局(`ChatPane.applyCenter` 里已验证)

- Kelsy 关闭 → 中部就是 `chat`
- Kelsy 开启 + 知识库隐藏 → 还是只有 `chat`
- Kelsy 开启 + 知识库显示 → `SplitPane(chat 70%, KnowledgePane 30%)`,分隔条可拖
- 切换会重建 `Center` 节点(规避 OpenJFX SplitPane 的 bug,`ChatPane.java:172-176`)

---

## 6. 限制 / 已知薄弱点

### 6.1 硬编码限制

| 限制 | 数值 | 出处 |
|---|---|---|
| 单文件读上限 | 256 KB | `KnowledgeStore.MAX_FILE_BYTES`(`KnowledgeStore.java:56`) |
| 搜索命中上限 | 50 条,行级 | `KnowledgeStore.MAX_HITS`(`KnowledgeStore.java:62`) |
| MEMORY.md 提醒阈值 | 8 KB | `KnowledgeStore.MEMORY_WARN_BYTES`(`KnowledgeStore.java:59`) |
| Snippet 上限 | 120 字符 | `KnowledgeStore.java:388-390` |
| AgentScope 最大迭代 | 20 | `LocalAssistantService.create`(`.maxIters(20)`,`LocalAssistantService.java:129`) |
| 单次列卡片 | 不过滤,返回全部卡片路径 | `KnowledgeStore.cardPaths`、`cardsContaining` |
| Agent 启用标志 | `disableShellTool`、`disableDynamicSkills`、`disableSubagents`、`disableDynamicSubagents` | `LocalAssistantService.java:125-128` |
| 单回合限制 | `kelsyBusy` AtomicBoolean —— 忙时新 send 被丢为 `BUSY` | `ChatController.java:120, 393-402` |
| 流式调度 | `boundedElastic`,默认 Reactor 池,每回合一个 Flux | `LocalAssistantService.java:152-153` |

### 6.2 同步 vs 异步

- **Embedding/索引**:N/A —— 没有。
- **种子**:首次 `KelsyRuntime.open()` 时同步,`LocalAssistantService.create()` 又跑一次(所以首次启动两次)。文件数小所以无所谓,但确实是在 FX 线程上,通过 `ChatController` 的构造器跑(`ChatController.java:221`)。
- **Workspace 读**(`KnowledgeStore.read` / `search` / `cardsContaining`):**FX 线程同步**。`KnowledgePane.open`(`KnowledgePane.java:123-149`)每次点击链接都同步读 + 解析 Markdown;`runFind`(`ChatController.java:804-818`)会在 FX 线程上阻塞整个文件遍历。没有任何 `Task`/worker 包装。
- **LLM 调用**:`Schedulers.boundedElastic()` 异步;`ReplyHandler` 回调通过 `onFx(...)`(`ChatController.java:780-790`)跳回 FX 线程。
- **配置读/写**:`Files.readString` / Jackson `readValue` 同步(`ConfigLoader.peek`)。

### 6.3 流式 vs 批量

**端到端流式。**`ModelFactory.create` 设 `.stream(true)`(`ModelFactory.java:123`)。AgentScope 把 `TextBlockDeltaEvent` / `ThinkingBlockDeltaEvent` / `ToolCallDeltaEvent` / `ToolResultTextDeltaEvent` 推给 Flux,`ReplyHandler` 把它们接到 `AssistantMessage.append` / `appendThinking` / `addTool` / `appendArgs` —— 全是 JavaFX property setter,所以气泡会增量重渲染。最终的 `MarkdownView` 只在 `onComplete` 把 `streaming` 关掉后才构造(`AssistantBubble.java:178-191`)。

### 6.4 TODO/FIXME/"not implemented" 标记

在 kelsy 包里 grep `TODO|FIXME|XXX|not implemented` 命中 **0 处**。当前功能里没有显式的桩。

### 6.5 明显的瓶颈和单点故障

1. **仅关键字检索**。一个 5000 张卡片、且中英文同义词较丰富的知识库,绝大多数语义查询会 miss。`LocalEvidence.terms` 维护了一张手写的停用词表(`LocalEvidence.java:32-34`),全程 substring `contains`;SKILL.md 显式要求别名词典惯例(比如 `licence`/`license`/`许可证` 永远一起写,`SKILL.md:60`),恰恰是因为没有语义匹配。

2. **没有 re-rank / 没有 chunking**。`read_file` 把整个文件丢进 LLM 上下文;由 LLM 自己决定 chunk 边界和哪一段相关。`MAX_FILE_BYTES = 256 KB`(`KnowledgeStore.java:56`),单次 `read_file` 调用就能撑爆任何 provider 的上下文窗口。

3. **引用准确性完全依赖 LLM 逐字回显路径**(工具参数或回复里)。`KnowledgePathExtractor.PATH` 要求 `.md` 后缀字面存在,且前缀严格是 `MEMORY.md|AGENTS.md|memory/...|knowledge/...`。写成 `knowledge/meetings/foo`(没 `.md`)会被静默丢弃。

4. **FX 线程上的同步文件 I/O**,在 `KnowledgePane.open` 和 `ChatController.runFind`。几百个 Markdown 文件的 workspace,每次 `/find` 和每次点引用链接都会卡 UI。

5. **Workspace 种子不是事务的**(`WorkspaceSeeder.seed`,`WorkspaceSeeder.java:56-80`)。进程被杀在种子中途,用户会拿到半个 workspace(`writeIfAbsent` 路径和 `writeAlways` skill 路径都不是原子的)。`MEMORY.md` 8 KB 提醒(`KnowledgeStore.MEMORY_WARN_BYTES`)就是这件事可能成真的信号。

6. **`KelsyRuntime` 是单一全局单例**(`KelsyRuntime.java:46, 85-98`)。`shared()` 在 workspace 路径变化时关掉重建,但用户切换走 `lastUsername` 配置字段;同一 JVM 里两个用户先后登录,只要 workspace 路径一样,第二个用户就会拿到第一个用户的 runtime。没有按 (user, imCode) 的 `AssistantService` 缓存。

7. **不能取消**。一旦 `assistant.chat(...)` 被调,唯一停下来的办法是 `assistant.close()`(`LocalAssistantService.java:164-166`),这会把整个 agent 销毁掉。用户没有"停止生成"按钮 —— `kelsyBusy` 只在 `onComplete`/`onError`/`onFx` 异常路径上被置 false。

8. **`maxIters(20)`**(`LocalAssistantService.java:129`)是硬上限,不可配置。跨多张卡片的多步检索会撞到它。

9. **HarnessAgent 的 `workspace` 是 `config.workspacePath()`**(`LocalAssistantService.java:124`),**不是** per-user 的 knowledge 根目录。所以 AgentScope 的文件系统工具覆盖整个 workspace,包括 `AGENTS.md`、`MEMORY.md`、`skills/` 等 —— 没有用户子目录范围限定。系统提示里硬编码了 "Tars" 作为角色名(`LocalAssistantService.SYS_PROMPT`,`LocalAssistantService.java:74-75`),但 per-room 昵称只活在 `KelsyRoomSettings`(LLM 自己看不见)。

10. **知识库没有加密**。只有 `config.json` 被 chmod 600(`ConfigLoader.java:170-175`);workspace 是纯 Markdown,无完整性校验,没有"模型用了哪张卡"的审计日志。

11. **斜杠命令没有 UI 自动补全和文档**。用户必须自己知道 `/note`、`/today`、`/tidy`、`/find` 存在。

12. **没有 model fallback / 限流处理**。provider 返回 429 直接冒到 `onError`,在气泡里显示为 `[出错] <message>`(`ChatController.java:630-637`),不重试。

---

## 7. 升级时要保住的不变量

这些是当前设计里"承重"的零件,任何升级要么遵守、要么有意识地替换:

- **`AssistantService` 接口** 是 UI 和 AgentScope 之间唯一的接缝。要换实现就在这里换,不要动 `ChatController`(`AssistantService.java:51`)。
- **`ReplyHandler` 回调契约** 是 `LocalAssistantService` 对外暴露的唯一东西;`ChatController.startAsk` 是唯一消费者(`LocalAssistantService.java:151-158`)。
- **`KnowledgeStore` 是 `Path` 上的 record** —— 所有方法都是无状态的、因为是纯路径 I/O 所以天然线程安全。新的检索策略(比如 embedding 搜索作为另一个 `search` 重载)就挂在这里。
- **`CitationTurn` 的"shown" vs "pending" 两阶段**(`commitIfRetrieved`)让右栏在 `onComplete` 时"啪"地一下落到最终集合,而不是随工具调用流不停闪(`CitationTurn.java:187-195`)。
- **`KnowledgePathExtractor.PATH`** 正则是"什么算知识引用"的唯一真理来源 —— 你新增的引用类型(PDF、网页等)要么匹配这个正则,要么扩这个 extractor(`KnowledgePathExtractor.java:38-39`)。
- **`KelsySendRouter` 的 result 枚举是穷尽的**;`ChatController.send` 在它们上面 switch(`ChatController.java:395-423`)。新的路由结果需要新 `Kind` 加新分支。
- **Per-user knowledge 根** 在 `KnowledgeStore.knowledgeRoot(workspace, username)`,有严格的 `startsWith(base)` 校验(`KnowledgeStore.java:133-144`)。别绕过它。
- **Workspace 种子的 `writeIfAbsent` vs `writeAlways` 是有意的** —— 用户编辑过的 `AGENTS.md` / `MEMORY.md` / `KNOWLEDGE.md` 会被保护,但 `skills/kelsy-knowledge/SKILL.md` 和 `examples.md` 每次启动强制刷新(`WorkspaceSeeder.java:60-76`)。

---

### 关键实现文件
- /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/kelsy/service/KnowledgeStore.java
- /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/kelsy/service/LocalAssistantService.java
- /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/ui/chat/ChatController.java
- /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/kelsy/service/CitationTurn.java
- /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/kelsy/KelsyRuntime.java
