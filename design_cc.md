# Kelsy (智能秘书) Knowledge Base — Architecture Exploration Report

## Executive Summary

Kelsy is a personal AI assistant built on top of [AgentScope](https://github.com/agentscope-ai/agentscope) 2.0.1 (`io.agentscope:agentscope-harness`). There is **no embedding/vector store, no ingestion pipeline, no chunking, and no separate index**. The "knowledge base" is a directory of human-edited Markdown files on disk. Retrieval is **pure keyword scanning inside the JVM**, with no model calls. The LLM is the AgentScope `HarnessAgent` speaking to OpenAI-compatible providers (MiniMax, Kimi, GLM, DeepSeek) via `OpenAIChatModel`. Citations are reconstructed by regex-matching knowledge paths that the LLM happened to echo in its tool arguments/results.

---

## 1. Architecture / Components

### 1.1 UI entry → LLM call trace

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

### 1.2 Class map

| Layer | Class | Role |
|---|---|---|
| UI top | `ChatPane` | `BorderPane`; right pane = `KnowledgePane` in a `SplitPane` (70/30) when enabled (`ChatPane.java:200-206`) |
| Controller | `ChatController` | Owns the reactive state (`kelsyBusy`, `liveAssistant`, `citations`, `memoryWarn`); routes every send through `KelsySendRouter`; turns `AgentEvent` deltas into JavaFX property updates |
| Runtime | `KelsyRuntime` | Singleton (`KelsyRuntime.java:85`); lazy-creates the assistant via injected factory `Function<KelsyConfig, AssistantService>` |
| Service iface | `AssistantService` | Pure-Java contract; no AgentScope leakage (`AssistantService.java:51`); callback `ReplyHandler` exposes `onTextDelta`, `onThinkingDelta`, `onToolCall/Args/Result`, `onComplete`, `onError` |
| Service impl | `LocalAssistantService` | Wraps `HarnessAgent`. `streamEvents` on `Schedulers.boundedElastic()`; `dispatch()` switch maps `AgentEvent` → `ReplyHandler` (`LocalAssistantService.java:186-200`) |
| Config | `KelsyConfig` / `ConfigLoader` | JSON record loaded from `~/.kelly/kelsy/config.json` |
| Providers | `ProviderCatalog` + `ProviderSpec` | Loads `providers.json` from classpath (DCL cached) |
| Model | `ModelFactory` | Builds `OpenAIChatModel` with provider-specific `OpenAIBaseFormatter` |
| Routing | `KelsySendRouter` | `KelsyMention` check → `SlashCommands.parse` → ASK/FIND/PEER/... |
| Mention | `KelsyMention` | Case-insensitive `@nickname ` prefix match; default nickname `RoomMember.SECRETARY_NAME` |
| Knowledge retrieval | `KnowledgeStore` (file ops), `FindQuery` (date+keyword parser), `KnowledgePathExtractor` (regex path extractor) |
| Citation tracking | `CitationTurn` | Buffers tool args/results from `memory_get`/`memory_search`/`read_file`/`list_files`; extracts paths via regex |
| Slash UI | `SlashCommands` | `/note`, `/today`, `/tidy`, `/find` |
| Seeding | `WorkspaceSeeder` | Copies templates from classpath on first run |
| KnowledgePane | `KnowledgePane` | Top: source-link list; Center: `MarkdownView` of selected file |
| Bubble | `AssistantBubble` | Renders TEXT / THINKING (collapsible) / TOOL (cards) blocks; if content matches reminder format, renders `reminderBox` |

### 1.3 `AssistantService` vs `LocalAssistantService` relationship

`AssistantService` is the only interface the UI layer touches; defined in `AssistantService.java:51` with the `ReplyHandler` inner interface. `LocalAssistantService` is the sole in-process implementation, and `KelsyRuntime` calls it through an injected `Function<KelsyConfig, AssistantService> factory` (`KelsyRuntime.java:52, 94-95`), which is the only extension seam — there is no remote / HTTP variant today (the Javadoc at `AssistantService.java:46-48` explicitly anticipates one).

### 1.4 Ingestion pipeline: **does not exist**

There is no loader, chunker, embedder, or index builder. `WorkspaceSeeder` only seeds:
- `AGENTS.md`, `MEMORY.md`, `knowledge/KNOWLEDGE.md` (write-if-absent, `WorkspaceSeeder.java:60-65`)
- The 7 `knowledge/<sub>` directories (`WorkspaceSeeder.java:66-69`)
- The skill bundle `skills/kelsy-knowledge/SKILL.md` and `references/examples.md` (write-always, `WorkspaceSeeder.java:71-76`)

All other content (meeting cards, decision cards, todo cards, memory diaries `memory/YYYY-MM-DD.md`, etc.) is created **at runtime by the LLM itself** using AgentScope's filesystem/memory tools, following the rules in `SKILL.md`. `WorkspaceSeeder` runs at `KelsyRuntime.open()` (`KelsyRuntime.java:131-132`) and again from `LocalAssistantService.create()` (`LocalAssistantService.java:115-116`).

### 1.5 Where knowledge is **retrieved**

Three retrieval paths, all keyword-based, all in-JVM:

1. **`/find <query>`** — `ChatController.runFind` (`ChatController.java:804-818`) → `KnowledgeStore.search(FindQuery.parse(query, today))` → returns line-level `Hit` records displayed in chat.
2. **AgentScope tool calls during a chat turn** — the LLM autonomously calls `memory_get`, `memory_search`, `read_file`, `list_files`. The path names of the files it touches are scraped out of the streamed tool args/result text by `CitationTurn` + `KnowledgePathExtractor`.
3. **Local evidence augmentation** — after every turn, `ChatController.addLocalEvidence` (`ChatController.java:724-742`) calls `LocalEvidence.mentionsTodos/Meetings/Decisions` (substring match on `待办/会议/纪要/决定`) and `LocalEvidence.terms(outgoing)` (stopword + punctuation stripped, then `FindQuery.parse` for keywords) to add the matching card paths to the citation list, so the right-side pane shows the relevant cards even if the LLM forgot to surface them.

There is no vector store, no ANN index, no reranker, no embedding API call anywhere in the kelsy package. Confirmed by `grep -rn "embedding\|vector\|VectorStore\|Embedder\|EmbedModel" /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/kelsy/` returning zero hits.

---

## 2. Storage

### 2.1 Paths (`KelsyPaths.java:24`)

```
~/.kelly/kelsy/config.json                        (KelsyPaths.java:58)
~/.kelly/kelsy/workspace/                         (KelsyPaths.java:59)
~/.kelsy/config.json          ← legacy, auto-migrated  (KelsyPaths.java:60, ConfigLoader.java:96-103)
```

`workspacePath()` in `KelsyConfig.java:65-70` honors a `~` prefix in `workspaceDir` and is read by `KelsyRuntime.resolve()` (`KelsyRuntime.java:67-69`) so users can point workspace to another drive.

### 2.2 Workspace layout (per `WorkspaceSeeder.seed` + `KnowledgeStore.list`)

```
~/.kelly/kelsy/workspace/
  AGENTS.md                 (system prompt anchor; only in root, not per-user)
  MEMORY.md                 (max ~8KB; UI warns above MEMORY_WARN_BYTES)
  KNOWLEDGE.md
  knowledge/
    KNOWLEDGE.md            (catalog; excluded from cardContaining results)
    people/  projects/  playbooks/  inbox/
    meetings/   decisions/   todos/
  memory/                   (daily logs, named YYYY-MM-DD.md)
  sessions/                 (raw chat dumps; SKILL.md says "do not treat as facts")
  skills/kelsy-knowledge/
    SKILL.md                (re-written on every start)
    references/examples.md  (re-written on every start)
```

**Per-user isolation** is at the workspace path itself: `KnowledgeStore.knowledgeRoot(workspace, username)` returns `workspace/<username>/` when username is non-blank, validated to not escape the workspace (`KnowledgeStore.java:133-144`). `KelsyRuntime.store(username)` calls this (`KelsyRuntime.java:161-163`).

### 2.3 Storage format

Plain UTF-8 Markdown. **No JSON, no sqlite, no vector index.** `KnowledgeStore.read()` simply does `Files.readString` (`KnowledgeStore.java:179`) with a 256 KB cap (`KnowledgeStore.java:56`, `MAX_FILE_BYTES = 256L * 1024`).

The only "structured" file outside Markdown is the `~/.kelly/kelsy/config.json` record (Jackson-serialized) holding `model.{provider,apiKey,baseUrl,modelName}`, `workspaceDir`, `lastUsername`, `selfAvatarPath`, `kelsyAvatarPath` (`KelsyConfig.java:27-32`). Per-room settings (`enabled`, `avatarPath`, `nickname`) live in `java.util.prefs.Preferences` keyed by SHA-256 of the IM code (`KelsyRoomSettings.java:108-120`).

### 2.4 Embeddings: **N/A** (no code path computes or caches them).

---

## 3. Retrieval flow

### 3.1 @-mention → ASK

1. `KelsyMention.isMention(text, nickname)` — case-insensitive `@nickname` prefix followed by space/EOF (`KelsyMention.java:57-67`).
2. `KelsySendRouter.route(enabled, busy, configured, text, nickname)` (`KelsySendRouter.java:99-128`) — rejects BUSY / UNCONFIGURED / EMPTY_BODY / SLASH_ERROR; otherwise dispatches ASK or FIND.
3. `SlashCommands.parse(body)` (`SlashCommands.java:72-99`) — `/note`/`/today`/`/tidy` rewrite into Chinese instructions for the LLM; `/find` returns `Result.find(query)` which the router maps to `Kind.FIND`.
4. `ChatController.startAsk(outgoing)` (`ChatController.java:529-640`):
   - `citations.beginAsk()`
   - `kelsyBusy.set(true)`
   - `runtime.ensureAssistant()` → `LocalAssistantService.chat(outgoing, handler)` on `Schedulers.boundedElastic()` (`LocalAssistantService.java:152-153`).
   - The `ReplyHandler` constructed here (`ChatController.java:539-639`) does the heavy lifting: it appends text deltas to an `AssistantMessage`, drives `citations` for every tool call, and on `onComplete` runs `addLocalEvidence`, `commitIfRetrieved`, then auto-opens `knowledgeVisible` and calls `onCitationSources`/`openKnowledge` so the right pane fills in.

### 3.2 How the assistant decides KB vs. model-alone

It doesn't — the LLM (AgentScope `HarnessAgent`) decides via tool-use. AgentScope gives it:
- `memory_get` / `memory_search` (over the workspace `memory/` + `MEMORY.md`)
- `read_file` / `list_files` (over the workspace tree)
- Plus the per-session `MEMORY.md` is auto-injected into the system prompt by the harness

The runtime-side heuristics are in `LocalEvidence` (mentioned above) and operate only **after** the turn completes, to enrich the citation list — not to gate the LLM.

### 3.3 Citation production and rendering

Citation paths come from three places, all parsed by the same regex in `KnowledgePathExtractor.PATH` (`KnowledgePathExtractor.java:38-39`):

```
(?:MEMORY\.md|AGENTS\.md|memory/[\p{L}\p{N}._/-]+\.md|knowledge/[\p{L}\p{N}._/-]+\.md)
```

1. **Streamed tool args/results** — `CitationTurn` buffers every delta on `memory_get`/`memory_search`/`read_file`/`list_files` (the `RETRIEVAL` set, `CitationTurn.java:43-44`), then runs `KnowledgePathExtractor.all` on the concatenated text.
2. **Reply text** — on `onComplete`, `citations.addRetrievalText(reply.content())` (`ChatController.java:614`) runs the same regex over the full assistant text. This catches paths the model writes inline (the SKILL.md explicitly tells it to).
3. **Local-evidence augmentation** — `addLocalEvidence` (`ChatController.java:724-742`).

`commitIfRetrieved()` then promotes `pending → shown` (`CitationTurn.java:187-195`). `evidencePath()` (`CitationTurn.java:222-230`) prefers a `knowledge/.../*.md` card over `MEMORY.md`/`AGENTS.md`/diaries. The right-pane UI receives the list via `onCitationSources` and the selected file via `openKnowledge(evidencePath())` (`ChatController.java:619-622`), which sets `knowledgeVisible=true` and forwards to `KnowledgePane.setSources` + `KnowledgePane.open`.

`AssistantBubble` shows tool calls as `ToolCallCard`s with an "open" link that uses the same `onWorkspaceLink` callback, plus the rendered Markdown text in a `MarkdownView` (`AssistantBubble.java:168-177, 278-286`).

### 3.4 Retrieval: keyword-only, line-AND

`KnowledgeStore.search` (`KnowledgeStore.java:231-261`) walks three scopes in order and **returns the first 50 line-level matches across all files**:

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

- `MAX_HITS = 50` (`KnowledgeStore.java:62`)
- `MAX_FILE_BYTES = 256 KB` (per-file cap, `KnowledgeStore.java:56`)
- `MEMORY_WARN_BYTES = 8 KB` (UI nag threshold, `KnowledgeStore.java:59`)
- Snippet truncation: 120 chars + `…` (`KnowledgeStore.java:388-390`)
- Diary filename filter via `FindQuery.matchesDailyFile` (`FindQuery.java:109-118`) — only `YYYY-MM-DD.md` inside the resolved date range
- AND-logic across keywords; case-insensitive `contains` (no tokenization, no stemming, no fuzzy matching)

`KnowledgeStore.cardsContaining` (used by `addLocalEvidence`, `KnowledgeStore.java:279-303`) does the same per-file `String.contains` match, returning matching file paths (cap 50, `KnowledgeStore.java:308-330`). It also **skips `KNOWLEDGE.md` itself** so the index file doesn't shadow its own cards.

### 3.5 Embedding model: **none used by the kelly app**. AgentScope's `memory_*` tools operate over text and file paths; no embedding is performed in-process.

---

## 4. Provider / config

### 4.1 Provider selection

`ModelFactory.create` → `ModelFactory.resolve` (`ModelFactory.java:82-100`) takes the user's `KelsyConfig.ModelSettings` and:
1. Defaults `provider` to `"minimax"` if blank.
2. Looks up `ProviderSpec` via `ProviderCatalog.findById(provider)` (DCL-cached list of `providers.json`, `ProviderCatalog.java:54-66`).
3. Resolves `baseUrl` / `modelName` as `firstNonBlank(userValue, specDefault)`.
4. Selects a formatter:
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
5. Builds `OpenAIChatModel.builder().apiKey().baseUrl().modelName().formatter().stream(true).build()` (`ModelFactory.java:116-125`).

### 4.2 Supported providers (`providers.json`)

| id | displayName | baseUrl | defaultModelName |
|---|---|---|---|
| `minimax` | MiniMax | `https://api.minimaxi.com/v1` | `MiniMax-M3` |
| `kimi` | 月之暗面 Kimi | `https://api.moonshot.cn/v1` | `kimi-k2.5` |
| `glm` | 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-5.3` |
| `deepseek` | DeepSeek | `https://api.deepseek.com` | `deepseek-chat` |

All four are OpenAI Chat-Completions compatible. Adding a new provider means: (a) a new line in `providers.json`, (b) a new case in `ModelFactory.formatterFor` (must extend `OpenAIBaseFormatter` from `agentscope-extensions-model-openai`).

### 4.3 API keys / endpoints

Stored in `~/.kelly/kelsy/config.json`, **JSON plaintext** (the `apiKey` field of `model`). On first creation, `ConfigLoader.ensureAndHasApiKey` writes the template (`ConfigLoader.java:55-68`) and `chmod 600` via `Files.setPosixFilePermissions(... "rw-------")` (`ConfigLoader.java:170-175`); silently no-op on Windows. Legacy `~/.kelsy/config.json` is auto-copied over (`ConfigLoader.java:96-105`).

### 4.4 What blocks a working assistant

`ChatController.send` (`ChatController.java:391-394`) computes `configured = enabled && runtime != null && runtime.hasApiKey()`, and the UI shows a system message pointing at the config file path when missing (`ChatController.java:403-407`).

---

## 5. UI

### 5.1 `KnowledgePane` (`KnowledgePane.java:41-166`)

Layout: `BorderPane` with `VBox sources` (top) + `ScrollPane host` (center). When `setSources(paths)` is called, it generates one `Hyperlink` per cited file with `shortName(path)` as the label and wires `link.setOnAction(e -> open(path))`. The host pane calls `KnowledgeStore.read(relativePath)` and switches on the sealed result (`KnowledgePane.java:132-147`):

- `Ok` → `new MarkdownView(MarkdownRenderer.parse(markdown), this::open)` (commonmark, with `open` as the link-click handler so navigation is recursive)
- `Missing` → "文件不存在" label
- `TooLarge` → "文件过大，未渲染" label
- `Rejected` → reason label

It also updates `memoryWarn` (a `BooleanProperty` shared with `ChatController`) whenever sources change or refresh is called.

It is **a Markdown browser, not a chunk list** — there is no source tree, no chunk navigator, no preview metadata beyond the path and a "本轮没有引用原文" empty state.

### 5.2 Slash command surface

- **Parsed**: `SlashCommands.parse` (`SlashCommands.java:72-99`) recognizes `/note`, `/today`, `/tidy`, `/find`. Anything else starting with `/` falls through to `Result.send(text)` (sent to the LLM verbatim).
- **Triggered by**: `KelsySendRouter.route` after stripping the `@nickname` prefix (`KelsySendRouter.java:115-120`).
- **Surfaced in UI**: there is no popup/autocomplete for slash commands — the `InputBar`/`MentionPopover` only suggest member mentions (see `MentionPopover.java`, `MentionQuery.java`). Users type `/` manually.
- **Rewriting**: `/note`, `/today`, `/tidy` are turned into long Chinese instructions for the LLM. `/find` is intercepted by the router and never reaches the LLM.

### 5.3 Layout (verified in `ChatPane.applyCenter`)

- Kelsy disabled → just `chat` in center
- Kelsy enabled + knowledge hidden → just `chat`
- Kelsy enabled + knowledge shown → `SplitPane(chat 70%, KnowledgePane 30%)`, divider draggable
- Toggling rebuilds the `Center` node (OpenJFX SplitPane bug workaround, `ChatPane.java:172-176`)

---

## 6. Limits / known weaknesses

### 6.1 Hardcoded limits

| Limit | Value | Source |
|---|---|---|
| Per-file read | 256 KB | `KnowledgeStore.MAX_FILE_BYTES` (`KnowledgeStore.java:56`) |
| Search hit cap | 50 hits, line-level | `KnowledgeStore.MAX_HITS` (`KnowledgeStore.java:62`) |
| MEMORY.md warn | 8 KB | `KnowledgeStore.MEMORY_WARN_BYTES` (`KnowledgeStore.java:59`) |
| Snippet cap | 120 chars | `KnowledgeStore.java:388-390` |
| AgentScope max iterations | 20 | `LocalAssistantService.create` (`.maxIters(20)`, `LocalAssistantService.java:129`) |
| Per-call card listing | unfiltered, returns all card paths | `KnowledgeStore.cardPaths`, `cardsContaining` |
| Agent enable flags | `disableShellTool`, `disableDynamicSkills`, `disableSubagents`, `disableDynamicSubagents` | `LocalAssistantService.java:125-128` |
| Single chat per turn | `kelsyBusy` AtomicBoolean — new sends while busy are dropped as `BUSY` | `ChatController.java:120, 393-402` |
| Stream `boundedElastic` | default Reactor pool; one Flux per turn | `LocalAssistantService.java:152-153` |

### 6.2 Sync vs async

- **Embedding/indexing**: N/A — none.
- **Seeding**: synchronous on first `KelsyRuntime.open()` and again on `LocalAssistantService.create()` (so twice on first launch). Small enough not to matter, but it does run on the FX thread via `ChatController`'s constructor (`ChatController.java:221`).
- **Workspace reads** (`KnowledgeStore.read` / `search` / `cardsContaining`): **synchronous** on the FX thread. `KnowledgePane.open` (`KnowledgePane.java:123-149`) reads and parses Markdown synchronously every time a link is clicked; `runFind` (`ChatController.java:804-818`) blocks the FX thread on the full file walk. There is no `Task`/worker wrapping.
- **LLM call**: async via `Schedulers.boundedElastic()`; `ReplyHandler` callbacks are hopped back to the FX thread via `onFx(...)` (`ChatController.java:780-790`).
- **Config load/save**: `Files.readString` / Jackson `readValue` synchronous (`ConfigLoader.peek`).

### 6.3 Streaming vs batch

**Streaming end-to-end.** `ModelFactory.create` sets `.stream(true)` (`ModelFactory.java:123`). AgentScope pushes `TextBlockDeltaEvent` / `ThinkingBlockDeltaEvent` / `ToolCallDeltaEvent` / `ToolResultTextDeltaEvent` to the Flux, and the `ReplyHandler` wires them to `AssistantMessage.append` / `appendThinking` / `addTool` / `appendArgs` — all JavaFX property setters, so the bubble re-renders incrementally. The final `MarkdownView` is only constructed after `onComplete` switches `streaming` off (`AssistantBubble.java:178-191`).

### 6.4 TODO/FIXME / "not implemented" markers

`grep` of the kelsy package for `TODO|FIXME|XXX|not implemented` returns **zero hits**. There are no explicit stubs in the current feature.

### 6.5 Obvious bottlenecks & single points of failure

1. **Keyword-only retrieval**. A 5,000-card knowledge base with non-trivial Chinese/English synonyms will miss most semantic queries. `LocalEvidence.terms` does a hand-curated stopword list (`LocalEvidence.java:32-34`) and substring-`contains` everywhere; the SKILL.md documents an alias convention (e.g. `licence`/`license`/`许可证` always all written together, `SKILL.md:60`) precisely because there's no semantic matching.

2. **No re-rank / no chunking**. Whole files are read into the LLM context via `read_file`; the LLM has to decide chunk boundaries and which slice is relevant. With `MAX_FILE_BYTES = 256 KB` (`KnowledgeStore.java:56`) a single `read_file` call can blow the context window of any provider.

3. **Citation accuracy depends on the LLM echoing the path verbatim** in tool args or its reply. `KnowledgePathExtractor.PATH` requires a literal `.md` suffix and a very specific prefix (`MEMORY.md|AGENTS.md|memory/...|knowledge/...`). A path written as `knowledge/meetings/foo` (no `.md` suffix) is silently dropped.

4. **Synchronous file I/O on the FX thread** in `KnowledgePane.open` and `ChatController.runFind`. A workspace with hundreds of Markdown files will stall the UI on every `/find` and on every citation link click.

5. **Workspace seeding is not transactional** (`WorkspaceSeeder.seed`, `WorkspaceSeeder.java:56-80`). If the process is killed mid-seed, the user can end up with half a workspace (the `writeIfAbsent` paths and the `writeAlways` skill paths are not atomic). The `MEMORY.md` 8 KB warn (`KnowledgeStore.MEMORY_WARN_BYTES`) is a hint that this can be a real problem.

6. **Single global `KelsyRuntime` singleton** (`KelsyRuntime.java:46, 85-98`). `shared()` shuts down and rebuilds on workspace-path change, but per-user switching is via the `lastUsername` config field; if two users log in back-to-back in the same JVM, the second user gets the first user's runtime unless the workspace path differs. There is no per-(user,imCode) `AssistantService` cache.

7. **No cancellation**. Once `assistant.chat(...)` is called, the only way to stop a runaway turn is `assistant.close()` (`LocalAssistantService.java:164-166`), which destroys the entire agent. The user has no "stop generating" button — `kelsyBusy` is only set false on `onComplete`/`onError`/`onFx` exception paths.

8. **`maxIters(20)` on HarnessAgent** (`LocalAssistantService.java:129`) is a hard cap, not configurable. Multi-step retrieval across many cards can hit this.

9. **HarnessAgent `workspace` is `config.workspacePath()`** (`LocalAssistantService.java:124`), **not** the per-user knowledge root. So AgentScope's filesystem tools operate over the entire workspace including `AGENTS.md`, `MEMORY.md`, `skills/`, etc. — there's no scoping to the user subdirectory. The system prompt hard-codes "Tars" as the persona name (`LocalAssistantService.SYS_PROMPT`, `LocalAssistantService.java:74-75`), but per-room nicknames live only in `KelsyRoomSettings` (the LLM itself doesn't see them).

10. **No encryption on the knowledge base**. Only `config.json` is chmod 600 (`ConfigLoader.java:170-175`); the workspace is plain Markdown, no integrity checks, no audit log of which card the model used.

11. **Slash commands are not autocompleted or documented in UI**. Users have to know `/note`, `/today`, `/tidy`, `/find` exist.

12. **No model fallback / no rate-limit handling**. A 429 from the provider bubbles up to `onError` and surfaces as `[出错] <message>` in the bubble (`ChatController.java:630-637`) with no retry.

---

## 7. Key invariants to preserve during an upgrade

These are load-bearing pieces of the current design that any upgrade should respect or consciously replace:

- **`AssistantService` interface** is the only seam between UI and AgentScope. Swap implementations here, not in `ChatController` (`AssistantService.java:51`).
- **`ReplyHandler` callback contract** is the only thing `LocalAssistantService` exposes; `ChatController.startAsk` is the only consumer (`LocalAssistantService.java:151-158`).
- **`KnowledgeStore` is a record over a `Path`** — all its methods are stateless and thread-safe by virtue of being pure path I/O. It's the right place to attach new retrieval strategies (e.g. embedding search as another `search` overload).
- **`CitationTurn`** tracks "shown" vs "pending" — the two-phase commit (`commitIfRetrieved`) is what makes the right pane "snap" to the final set on `onComplete` rather than flickering as tool calls stream in (`CitationTurn.java:187-195`).
- **`KnowledgePathExtractor.PATH`** regex is the single source of truth for "what counts as a knowledge citation" — anything you add (e.g. PDFs, web pages) needs to either fit this regex or extend the extractor (`KnowledgePathExtractor.java:38-39`).
- **`KelsySendRouter` result kinds** are exhaustive; `ChatController.send` switches on them all (`ChatController.java:395-423`). New routing outcomes need a new `Kind` and a new switch arm.
- **Per-user knowledge root** is `KnowledgeStore.knowledgeRoot(workspace, username)` with a strict `startsWith(base)` check (`KnowledgeStore.java:133-144`). Don't bypass this.
- **Workspace seed `writeIfAbsent` vs `writeAlways`** is intentional — user-edited `AGENTS.md` / `MEMORY.md` / `KNOWLEDGE.md` are protected, but `skills/kelsy-knowledge/SKILL.md` and `examples.md` are forcibly refreshed each launch (`WorkspaceSeeder.java:60-76`).

---

### Critical Files for Implementation
- /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/kelsy/service/KnowledgeStore.java
- /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/kelsy/service/LocalAssistantService.java
- /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/ui/chat/ChatController.java
- /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/kelsy/service/CitationTurn.java
- /Users/ksw/workspace/repository/kelly/src/main/java/com/mordor/kelly/kelsy/KelsyRuntime.java
