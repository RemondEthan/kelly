# 长期记忆：分层工作集 + 进程内 FTS — Design

**Date:** 2026-09-15
**Status:** Approved
**Owner:** user

## 导读：用秘书书桌理解这套设计

核心就一句话：**Markdown 卡片永远是真相；`MEMORY.md` 只是桌上那张小便签；SQLite 是目录柜上的索引。** 对话时先查索引、再打开几张卡片，而不是把整柜文件或整本小便签摊开给模型。

### 现在为什么会越用越差

今天秘书的「记忆」是三堆文件，外加模型自己想办法翻：

```
桌上：MEMORY.md            每轮都塞进模型（会越写越长）
抽屉：memory/2026-03-15.md  日记，默认不在眼前
柜子：knowledge/meetings/…  会议 / 决定 / 待办正文，默认不在眼前
```

你问「半年前 licence 怎么定的」，模型要自己抽词、自己 `memory_search`、自己打开文件。文件一多就会：`MEMORY.md` 变长、全盘逐行扫描变慢、模型搜错词或用聊天印象编结论。`/find` 用得少，所以问题主要出在对话上。

### 新方案：三样东西，三种职责

升级后磁盘上还是这些 Markdown，多一个可删的索引文件。

| 名字 | 是什么 | 干什么 | 不干什么 |
|---|---|---|---|
| L2 卡片 | `knowledge/**/*.md`、日记 | 唯一真相。结论、别名、待办原文都在这里 | 不会整柜倒进对话 |
| L0 小便签 | `MEMORY.md`，硬顶 4KB | 「现在仍为真」：偏好、身份、当前项目的一行指针 | 不当搜索引擎，不保证所有事实都在上面 |
| 索引 | `.kelly-index.db`（进程内 SQLite） | 谁、主题、别名、正文都能搜 | 不是第二份知识库；删了下次启动会重建 |

可以把它想成一个只带一张书桌的秘书：桌上永远只有一张不超过 4KB 的便签；柜子里卡片越积越多但不上桌；你一开口带出「张三 / licence / 周报」这种词，秘书先查目录（一般 <50ms），最多抽出 5 张相关卡片放到手边，再让模型读这几张。

**不是**「每轮 = 整份 SQLite + 整份 MEMORY.md」。  
**也不是**「只有问过去才查柜子」。问「张三邮箱」同样要查柜子，因为邮箱在人物卡里，便签上往往没有。

### 你发一句话之后发生什么

```
你打字发给秘书
        │
        ├─ 1. 抽词（去掉「请问」「一下」）
        │     抽不到 → 当闲聊，不查索引
        │     抽到了 → 查一次 SQLite
        │
        ├─ 2. 分数够 → 在给模型的消息前面附上最多 5 条「已检索候选」
        │     （路径 + 标题 + 一句摘要）
        │     分数不够 → 先不附
        │
        ├─ 3. 模型看到：4KB 便签 +（可能有的）5 条候选 + 你的原话
        │     需要事实 → 只 read_file 这几张卡（最多 5 张）
        │     候选没有、便签也没有、但你在问人/项目/流程/结论
        │         → 再调一次 knowledge_search（还是同一个 SQLite）
        │     两次都没有 → 只能说「没归档」，不许编
        │
        └─ 4. 右栏只显示这几张被摸过的卡，不再把整个 meetings/ 目录甩上去
```

一轮对话的上限和「柜子里有一万张卡」无关：便签 ≤ 4KB；查目录 1 次（模型最多再查 1 次）；打开卡片 ≤ 5 张。

### 四种真实情况

假设柜子里已经有人物卡、licence 会议卡、申请流程卡；便签上只有「喜欢深色主题」「当前项目：XX」「张三 → people/张三.md」。

- **「你好」**：抽不到有用词 → 不查 SQLite。模型只看到 4KB 便签。
- **「张三邮箱是什么？」**：没说「当时」，但抽到了「张三」「邮箱」。查索引 → 命中人物卡 → 模型打开人物卡。便签上没有邮箱也没关系。
- **「客户交付 licence 当时怎么定的？」**：带回忆线索，阈值更松。别名对上 licence / 许可证 → 打开会议卡，结论以卡片字段为准。
- **「帮我按那个结论写封催申请的邮件」**：预检索若没带上，模型发现便签里没有结论，必须再搜一次，不能瞎写。

归档（`/note`）仍按现在的规矩：槽位齐了才写卡片，再往 `KNOWLEDGE.md` 加一行，再用 `memory_save` 更新便签和当天日记。写完后索引按 mtime 对账。便签超过 4KB 时由 Java 自动收瘦，不再指望 `/tidy`。

### 现有知识库怎么长上去

不用搬家。现有卡片、日记、`KNOWLEDGE.md` 原样不动。升级应用后第一次打开该用户目录时：扫描现有 `.md` 建成 `.kelly-index.db`；若 `MEMORY.md` 已超过 4KB 则收瘦（先备份 `MEMORY.md.bak`）；`SKILL.md` 本来每次启动都会覆盖。删掉索引文件只是丢掉目录，卡片还在。

### 刻意不做

不上 Elasticsearch、向量库、独立中间件；第一期不算 embedding；不改会议 / 决定 / 待办卡片格式。AgentScope 自带的 `memory_search` 不再当主路，能关就关。

---

## 1. Background

Kelsy 当前把知识库做成「磁盘 Markdown 为唯一真相」：

- `MEMORY.md` 每轮由 AgentScope Harness 注入，目标 2–4KB，超过 8KB 只警告
- 检索是 `KnowledgeStore.search` / `cardsContaining` 的全盘 `contains` 扫描，最多 50 条
- 回忆协议写在 `skills/kelsy-knowledge/SKILL.md`：模型自己抽词，再 `memory_search` → `memory_get` → `read_file`，最多 20 轮
- `/tidy` 完全靠模型收瘦索引
- 用户说到「会议 / 决定」时，`addLocalEvidence` 会挂上整个目录的卡片路径

语料变长后，工作集会膨胀、扫描会变慢、模型更容易漏搜或用会话印象补事实。仓库里另有一份 `design_oc.md`（向量 + 全量 cosine），不作为本方案：它既引入 embedding API，又把检索复杂度重新变成随 chunk 数线性增长。

## 2. Goals

- 单机可部署；不引入 Redis / Elasticsearch / Qdrant 等独立中间件。
- 知识条目增长时，每轮注入的工作集不增长；检索延迟不随文件数线性增长。
- 问到过去的事（含半年前）时，结论以卡片原文为准，不靠会话印象。
- 现有用户知识库**自动升级**，无需导出导入、无需重新归档。
- Markdown 仍是唯一真相；索引损坏可重建。

## 3. Non-Goals

- 第一期不上 embedding / 向量库 / 混合向量排序。
- 不改会议 / 决定 / 待办卡片的 Markdown 字段约定。
- 不把 `KNOWLEDGE.md` 拆成按年月目录（可在后续版本做；升级期保留原文件以免破坏已有阅读路径）。
- 不替换 AgentScope；不开放 Shell / 子代理。
- 不做跨机器同步、加密知识库、多用户并发写同一知识根。

## 4. Current invariants to keep

- `AssistantService` / `ReplyHandler` 仍是 UI 与 AgentScope 的唯一接缝。
- `KnowledgeStore` 仍是路径上的只读门面；新检索挂在它上面，不让 `ChatController` 直接碰 SQLite。
- `CitationTurn` 的 pending → shown 两阶段保留；检索工具集合增加 `knowledge_search`。
- `WorkspaceSeeder`：用户编辑过的 `AGENTS.md` / `MEMORY.md` / `knowledge/KNOWLEDGE.md` 仍 `writeIfAbsent`；`SKILL.md` 与 `examples.md` 仍每次启动覆盖。
- 用户隔离仍是 `KnowledgeStore.knowledgeRoot(workspace, username)` → `workspace/<username>/`。
- `memory_save` 仍只写 `MEMORY.md` 和当天日记；禁止 `write_file` / `edit_file` 改这两处。
- `/find` 仍不发给模型，由本地检索出结果。

## 5. Architecture

```
用户提问 / /find
        │
        ▼
┌───────────────────────────────────────────┐
│ KnowledgeStore                            │
│  search() / cardsContaining() 改走索引     │
│  read() / cardPaths() 仍读 Markdown        │
└─────────────────┬─────────────────────────┘
                  │
                  ▼
┌───────────────────────────────────────────┐
│ KnowledgeIndex  （进程内 sqlite-jdbc）      │
│  <userRoot>/.kelly-index.db               │
│  FTS5 + cards_meta；CJK 二元切分在 Java    │
└─────────────────┬─────────────────────────┘
                  │ 派生，可删可重建
                  ▼
┌───────────────────────────────────────────┐
│ L0 MEMORY.md     硬上限 4KB，Java 压缩     │
│ L2 knowledge/**  与 memory/YYYY-MM-DD.md   │
│    （唯一真相，格式不变）                    │
└───────────────────────────────────────────┘
```

只新增一个 Maven 依赖：`org.xerial:sqlite-jdbc`。它打进应用包，不是独立进程。

### 5.1 三层记忆

| 层 | 路径 | 是否注入模型 | 规则 |
|---|---|---|---|
| L0 工作集 | `MEMORY.md` | 是，硬上限 4KB | 只留「现在仍为真」的短指针 + 近 14 天日记指针 |
| L1 目录 | `knowledge/KNOWLEDGE.md` | 否（不再当全文目录注入） | 维持现有「路径 + 一句话」；升级期不改写 |
| L2 正文 | `knowledge/**/*.md`、`memory/YYYY-MM-DD.md` | 否，按需 `read_file` | 格式与现网完全相同 |

L0 超限时由 Java 压缩，不叫模型改文件。`/tidy` 变成可选润色，不是正确性前提。

### 5.2 L0 确定性压缩

触发：写入后或启动对账时 `MEMORY.md` 字节数 > 4096。

顺序：

1. 保留无日期 / 常青条目（偏好、身份、长期关系）。
2. 保留近 14 天（按行内 ISO 日期或日记指针）的条目。
3. 其余行若已含 `knowledge/` 卡片路径：删除该指针（事实在卡片里，FTS 能找回）。
4. 其余行若不含卡片路径：先写成 `knowledge/inbox/YYYY-MM-DD-<slug>.md`（原文一行不改），再删指针。
5. 同一「谁 + 主题」已有 `people/` 或 `projects/` 专题页时，L0 只留一行指向专题页。

首次压缩前把当时的 `MEMORY.md` 复制为 `MEMORY.md.bak`（已存在则不覆盖，只留第一次）。压缩失败则保留原文，只记日志，不阻断启动。

### 5.3 索引

每个用户知识根一份库：

```
<knowledgeRoot>/.kelly-index.db
```

Schema：

```sql
CREATE TABLE cards_meta (
  path   TEXT PRIMARY KEY,
  mtime  INTEGER NOT NULL,
  type   TEXT,
  date   TEXT,
  who    TEXT,
  status TEXT
);

CREATE VIRTUAL TABLE cards_fts USING fts5(
  path, title, aliases, body, type, who, date,
  tokenize = 'unicode61'
);
```

索引范围：`MEMORY.md`、`memory/*.md`、`knowledge/**/*.md`（含 `KNOWLEDGE.md`，检索展示时仍可按现逻辑排除目录页）。不索引 `sessions/`、`skills/`、`AGENTS.md`。

中文写入前在 Java 做 **CJK 二元切分 + 原词**（`许可证` → `许可 可证 许可证`；ASCII 词原样）。卡片 `- 别名：` 整行进入 `aliases`。无标题文件用文件名当 `title`。字段解析失败时 `type/date/who/status` 可空，`body` 仍索引全文。

增量：比较磁盘 `mtime` 与 `cards_meta.mtime`，只处理新增 / 修改 / 删除。启动时对账一次即可；不强制 `WatchService`。

`memory_save` 或文件系统工具写卡之后，对变更路径调用 `KnowledgeIndex.upsert(path)`。

### 5.4 检索

真实主路径是**对话 ASK**，不是 `/find`。`MEMORY.md` 与 SQLite **不是每轮合并成一份语料**。两者职责分开：

- `MEMORY.md`：L0 工作集。Harness 可能每轮自动注入，硬上限 4KB，只承载「现在仍为真」（偏好、身份、当前项目指针）。不当搜索引擎，也不把 FTS 命中写回去再搜一遍。
- SQLite FTS：卡片 / 日记 / 常青页的检索面。`MEMORY.md` 里没有的事实（人名、流程、项目约定、旧结论）以这里为准。

「不问过去」也不能只看 L0。用户问「张三邮箱」「licence 怎么申请」「按上次结论写邮件」时，事实通常在 `people/` `playbooks/` `meetings/`，压缩后 L0 可能只剩一行指针，或指针已被收掉。因此预检索的触发条件是**有可检索词**，不是「像在问历史」：

1. `startAsk` 用 `LocalEvidence.terms`（去停用词后长度 ≥ 2 的词）判断。抽不到词（纯寒暄、单字）：跳过 FTS，本轮只有 L0。
2. 抽到词：构造 `FindQuery`，FTS 一次（< 50ms）。不要求出现「当时 / 半年前」。
3. top1 过默认阈值 → 附 top 5「已检索候选」。原文带回忆线索（当时 / 会议 / 决定 / 待办 / 相对时间 / ISO 日期）时用更低阈值，宁可多附一条。
4. 分数不够：不附候选。模型不得把「L0 里没有」写成「没归档」；若回答需要一条已记事实，必须再调 `knowledge_search`。仍零命中才能说没归档。
5. 模型只 `read_file` 候选或补查路径，最多 5 张。结论以卡片字段为准，不用 L0 缩写顶替。
6. 禁止为找一条事实去读整份 `MEMORY.md` 或 `list_files` 整个目录。

`/find` 是同一引擎的手工入口，只做检索并显示，不经过模型。

`KnowledgeStore.search(FindQuery)`：

1. 用 `FindQuery` 的关键词与日期窗口。
2. FTS5 BM25 取 top 24。
3. 用 `date` / 日记文件名 / `FindQuery.matchesDailyFile` 过滤窗口。
4. 返回最多 50 条 `Hit`（`relativePath` + 行号尽力而为 + snippet），接口不变。

`cardsContaining` 改为同一套 FTS，按 path 去重，上限 50。`addLocalEvidence` 与本次 ASK 预检索共用结果，禁止再 `cardPaths` 整目录倾倒。

工具 `knowledge_search` 仍注册，供模型在候选不够时再查一次。内部委托 `KnowledgeStore.search`。它不是对话的第一跳。

`CitationTurn.RETRIEVAL` 增加 `knowledge_search`。预检索附上的 path 也走 `addPaths`。

对话一轮成本上限（与库规模无关）：注入 L0 ≤ 4KB；FTS 1 次（模型补查最多再 1 次）；`read_file` ≤ 5 张卡。

### 5.4.1 哪些入口走 SQLite，哪些不走

**Kelly 自己的「搜索」只有一个引擎：`KnowledgeIndex`（SQLite FTS5）。** 下列入口必须委托它，禁止再 `Files.walk` + `contains`：

| 入口 | 调用方 | 是否 SQLite |
|---|---|---|
| `KnowledgeStore.search` | 对话 ASK 预检索（主路径）、`/find` | 是 |
| `KnowledgeStore.cardsContaining` | `addLocalEvidence`（与本次 ASK 的 FTS 结果合并） | 是 |
| 工具 `knowledge_search` | 模型补查，不是第一跳 | 是（委托 `KnowledgeStore.search`） |

下列**不是搜索**，继续读磁盘或列目录，不经 FTS：

| 入口 | 作用 |
|---|---|
| `KnowledgeStore.read` / 工具 `read_file` | 读一张卡的全文 |
| `KnowledgeStore.cardPaths` / 工具 `list_files` | 列目录（提醒扫描、UI 树） |
| `TodoScanner.list` | 待办提醒时钟，按文件列 `open` 卡 |
| 工具 `memory_get` | 按日期读日记原文 |
| `memory_save` | 写 L0 / 当日日记，写完后再 `upsert` 索引 |

AgentScope 自带的 `memory_search` **不走我们的 SQLite**，它仍是 Harness 对 `MEMORY.md` + `memory/` 的内置扫描。主协议不再教模型用它。实现时若 Harness 允许关掉或替换该工具，应关掉，避免和 `knowledge_search` 并存两套搜索；关不掉则只靠 SKILL 约束。验收以「ASK 预检索已经跑过 FTS」为准，不依赖模型是否调用 `memory_search`。

索引不可用（库损坏、驱动失败）时：启动时删除并重建；若重建也失败，**仅上述三张搜索表**回退到现有线性扫描，并打警告。不允许静默空结果冒充「没有归档」。`read_file` / `list_files` 不受影响。

### 5.5 模型协议

`SKILL.md`（`writeAlways`，升级后下次启动即生效）改为：

1. 本轮若已有「已检索候选」：先读这些路径，不要先翻 `MEMORY.md`，不要先 `memory_search`。
2. L0 没有、候选也没有、但用户在问一个可归档事实（人、项目、流程、结论、待办）：必须再调 `knowledge_search`，禁止用会话印象补。
3. 候选不够或用户改了时间范围：再调一次 `knowledge_search`。
4. 只对返回路径 `read_file`；结论以卡片字段为准。
5. 两次检索都零命中才说没有归档。禁止把「MEMORY.md 里没有」当成没归档。
6. 问待办：用候选或 `knowledge_search` 滤 `status=open`，再读相关卡；回复写 `来源：knowledge/todos/…`。
7. 归档落盘顺序不变：先写卡片，再 `KNOWLEDGE.md` 一行，再 `memory_save`。
8. `MEMORY.md` 只用于「现在还成立的偏好 / 身份」，不当事实检索入口。
9. AgentScope 自带 `memory_search` 不写进主协议；能关则关。

`AGENTS.md` 仍 `writeIfAbsent`，不覆盖用户改过的人设。旧文件里「先 load kelsy-knowledge」这一句已经足够把模型带进新 skill。

### 5.6 会话窗口

`sessionId = "main"` 保留。送给模型的对话只保留最近 20 轮。更早内容不当已归档事实。聊天 UI 的 `ChatHistory.MEMORY_CAP = 100` 不变。

## 6. Automatic upgrade

现有知识库**可以自动升级**。用户不用导出、不用 `/tidy`、不用重新 `/note`。

### 6.1 什么是「无缝」

| 项目 | 升级行为 |
|---|---|
| 会议 / 决定 / 待办 / 人物 / 项目卡片 | 原路径、原正文不动 |
| `memory/YYYY-MM-DD.md` | 不动 |
| `knowledge/KNOWLEDGE.md` | 不动，继续可追加 |
| 卡片字段不完整的旧文件 | 仍按全文 + 文件名索引，可被搜到 |
| 自定义 `workspaceDir` | 仍走 `KelsyConfig.workspacePath()` |
| 多用户子目录 | 每个 `workspace/<user>/` 各自建 `.kelly-index.db` |
| `config.json` / API Key | 不改 |
| `/note` `/today` `/find` `/tidy` | 命令保留；`/find` 与对话检索共用新索引 |

用户侧唯一可感知变化：升级后**第一次**打开该用户知识根时，后台扫一遍现有 Markdown 建索引。卡片量在数千级时通常数秒内完成；期间检索走「建完再用」，UI 不阻塞在 FX 线程上做全盘扫描。

### 6.2 启动迁移步骤

挂在现有 `KelsyRuntime.open` → `WorkspaceSeeder.seed(userRoot)` 之后：

1. 打开或创建 `<userRoot>/.kelly-index.db`。
2. 若 `meta.schema_version` 不是当前版本：删表重建（派生数据，安全）。
3. 扫描 L2 + `MEMORY.md`，按 `mtime` 增量 upsert。
4. 若 `MEMORY.md` > 4KB：按 §5.2 压缩（先写 `MEMORY.md.bak`）。
5. 覆盖写入新 `SKILL.md` / `examples.md`（已有逻辑）。

没有单独的「迁移向导」或手动脚本。

### 6.3 会改写的文件（仅派生或超限压缩）

- **新增** `.kelly-index.db`（及 SQLite 附属文件）。可随时删除，下次启动重建。
- **仅当超 4KB**：改写 `MEMORY.md`；可能新增 `knowledge/inbox/*.md` 承接无卡片指针的旧流水。事实不丢。
- **不改写**：已有会议 / 决定 / 待办 / 专题页、日记、`KNOWLEDGE.md`、`AGENTS.md`。

这不是「另起一套库再导入」，而是「旧文件继续当真相，旁边长出可重建的索引」。

### 6.4 不能假装无感的点

- 第一次建索引有一次性耗时，不是零成本，但是自动的。
- 若旧 `MEMORY.md` 已经很长且很多行没有卡片路径，压缩会在 `inbox/` 落下若干卡。右栏能打开这些卡；内容是原指针原文。
- 旧 `AGENTS.md` 不会被覆盖。新检索协议靠每次启动刷新的 `SKILL.md`。
- 降级回旧版应用：旧代码忽略 `.kelly-index.db`；卡片与日记仍按旧扫描工作。若曾压缩过 `MEMORY.md`，旧版看到的是更短的索引（指向卡片），功能仍正确；可用 `MEMORY.md.bak` 还原压缩前原文。

### 6.5 验收（升级）

用一份从现网拷来的用户根（含 `MEMORY.md`、若干 `memory/*.md`、`knowledge/meetings|decisions|todos|people`）：

1. 启动后存在 `.kelly-index.db`，卡片 path 集合等于磁盘 `.md` 集合（扣除 `skills/`）。
2. `KnowledgeStoreSearchTest` 现有三条（日期窗口、licence/许可证别名、people 页）在索引实现下仍通过。
3. 不把现网卡片改写（mtime 除被压缩的 `MEMORY.md` 外不变）。
4. 删除 `.kelly-index.db` 再启动，索引重建后检索结果稳定（同 query 的 path 集合一致）。

## 7. Error handling

- 索引重建失败：记录错误，本轮回退线性扫描，UI 可用 `memoryWarn` 同类通道提示「知识索引未就绪，检索较慢」。
- 单文件 > 256KB：与现在一样不读入正文；索引只记 path + title。
- 压缩中途崩溃：`MEMORY.md` 用临时文件 + replace，保证要么旧内容要么新内容；`.bak` 已存在则不动。
- `knowledge_search` 空结果：模型必须说未归档，测试覆盖「库空 / 词不匹配」两条。

## 8. Testing

- 单元：CJK 二元切分、FTS 别名命中、日期窗口、inbox 升格、`MEMORY.md` 超限压缩、schema 升级删库重建。
- 升级夹具：把现有 `KnowledgeStoreSearchTest` 的目录当作「旧库」，先按旧文件布局写入，再跑 `KnowledgeIndex.reconcile`。
- 回归：`CitationTurn` 能从 `knowledge_search` 结果抽出 path；`addLocalEvidence` 不再因「会议」二字返回整个 meetings 目录。
- 不在单测里打真实 embedding API。

## 9. Success criteria

以单机、单用户、约 1 万张卡 / 5 年日记为设计点：

- 对话 ASK 预检索与 `/find` 在索引已热时 P95 < 50ms（不含首次重建）。
- 每轮注入 L0 ≤ 4KB，不随总卡数增长。
- 模型每轮最多读 5 张卡全文。
- 同主题三次归档已升格专题页后，问「当时那一次」能列出时间线并链回旧卡。

## 10. Implementation sketch (files)

新增：

- `kelsy/service/CjkNgrams.java` — 索引/查询用切分
- `kelsy/service/CardFields.java` — 从卡片正文抽 type/who/date/aliases/status
- `kelsy/service/KnowledgeIndex.java` — SQLite 打开、reconcile、search、upsert
- `kelsy/service/MemoryCompactor.java` — L0 压缩
- `kelsy/service/AskGrounding.java` — 对话预检索：抽词、阈值、附「已检索候选」
- `kelsy/service/KnowledgeSearchTool.java` — AgentScope `AgentTool`，名称 `knowledge_search`

修改：

- `KnowledgeStore.java` — `search` / `cardsContaining` 走索引；构造时打开 `.kelly-index.db`
- `KelsyRuntime.java` — seed 之后 `index.reconcile()`，超限则压缩
- `LocalAssistantService.java` — `build()` 之后 `agent.getToolkit().registerAgentTool(...)`；`ToolsConfig.setDeny(List.of("memory_search"))`。不打开 dynamic skills。
- `CitationTurn.java` — `RETRIEVAL` 加入 `knowledge_search`
- `ChatController.startAsk` / `addLocalEvidence` — ASK 走 `AskGrounding`；证据用本次 FTS top-K
- `SlashCommands.java` — `/today` 改为使用已检索候选 / `knowledge_search`
- `pom.xml` / `module-info.java` — `sqlite-jdbc`（`org.xerial.sqlitejdbc`）
- `workspace/skills/kelsy-knowledge/SKILL.md` 与 `examples.md` — 新回忆协议

产品验收以「有可检索词的 ASK 至少经过一次 FTS；模型再 `read_file` 候选卡」为准。
