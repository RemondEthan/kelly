# Kelsy 本地混合检索设计

日期：2026-09-18
状态：设计已确认，等待实现计划
范围：Kelsy 长期记忆的查询构造、BM25 排序、本地 ONNX embedding、向量检索与入口集成

## 1. 背景

Kelsy 当前以 Markdown 为唯一知识真相，以 SQLite FTS5 为派生搜索索引。现有检索链路已经能够索引中文二元词、卡片字段和正文，但相关性判断存在四个根本问题：

1. SQLite FTS5 的 `bm25()` 返回负值，越负越相关；当前代码用 `1 - bm25 / 20` 转换后，强弱命中都得到大于 1 的分数，`0.20`、`0.05` 阈值失效。
2. ASK 使用原始用户整句构造查询。无空格中文会展开成大量二元词并在组内使用 OR，常见问句片段会产生弱相关候选。
3. title、aliases、who、body 等字段没有体现不同的事实价值，短正文中的偶然命中可能超过人物页或专题页。
4. BM25 只能衡量字面相关性，不能召回无共同关键词但语义相近的表达。

本设计在不引入独立向量数据库、不调用远程 embedding API 的前提下，交付“加权 BM25 + 本地 ONNX 向量 + 确定性证据门槛”的混合检索。

## 2. 已确认的产品决策

1. ASK 采用精度优先策略：弱相关结果不自动附给模型。
2. ASK、`/find`、`knowledge_search` 共用查询规划、召回、融合与强弱判定。
3. 本次交付包含 BM25 修正和本地向量检索，不拆成远期可选功能。
4. embedding 只使用随安装包发布的本地 ONNX 模型。
5. 不实现、不配置、不预留远程 embedding API。
6. 本地语义索引默认开启，在后台低优先级构建；构建完成前使用纯 BM25。
7. 第一版保障最多约 20,000 个活动 chunk，使用进程内精确余弦扫描，不引入 HNSW。
8. Markdown 仍是唯一真相；SQLite、embedding 和内存向量矩阵都属于可删除、可重建的派生数据。
9. 保留现有归档时补充 `aliases` 的行为，并增加受控、本地、可测试的查询同义词扩展；不使用 LLM 动态生成同义词。

## 3. 目标

### 3.1 正确性

- 修复 BM25 方向和阈值错误。
- 精确 title、alias、who 命中优先于正文偶然命中。
- ASK 只附具有明确词法证据的强相关卡片。
- 纯语义相似但主体缺失的候选不能成为 ASK 强候选。
- 日期、状态和用户边界在融合前后都得到强制执行。

### 3.2 完整性

- 无共同关键词但语义相关的卡片可被本地向量召回。
- 新建、修改、删除卡片可增量更新词法与向量索引。
- 向量不可用时保留完整词法检索能力。
- FTS 不可用时明确标记结果不完整，不能把故障空结果解释为未归档。

### 3.3 性能

- 查询热路径不执行文件树遍历或 reconcile。
- JavaFX 线程不执行 SQLite、ONNX、tokenizer 或向量扫描。
- 20,000 个 384 维 chunk 下，完整混合检索 P95 目标小于 100ms。
- 后台向量构建不阻塞启动、聊天输入和窗口布局。

### 3.4 隐私

- 查询、卡片正文和向量不离开本机。
- embedding 子系统不包含网络访问代码。
- 不记录用户完整查询、正文或向量到诊断日志。

## 4. 非目标

- 不引入 Pinecone、Milvus、Weaviate、Qdrant 等独立向量数据库。
- 不引入 HNSW 或其他近似最近邻索引。
- 不使用 LLM 做在线 query rewrite 或候选重排。
- 不让向量结果替代日期、用户、状态等确定性过滤。
- 不把 BM25、cosine 或 RRF 解释为事实置信概率。
- 不修改现有 Markdown 卡片格式。
- 不在本次设计中修复 MemoryCompactor、会话窗口或其他长期记忆问题。

## 5. 总体架构

```text
用户查询
  │
  ▼
RetrievalQueryPlanner
  ├─ 日期窗口
  ├─ 实体锚点
  ├─ 词法组
  ├─ 受控同义词
  └─ 语义查询文本
  │
  ├──────────────┬─────────────────────┐
  ▼              ▼                     ▼
OriginalLexical  SynonymLexical        LocalEmbeddingRuntime
  │              │                     │
原词 BM25 top 50  同义词 BM25 top 50     384 维查询向量
  │              │                     ▼
  │              │                 ExactVectorIndex top 50
  └──────────────┴──────────┬──────────┘
             ▼
       HybridReranker
         ├─ 加权 RRF
         ├─ 词法覆盖
         ├─ 字段证据
         └─ 稳定 tie-break
             ▼
       RelevancePolicy
         ├─ STRONG
         ├─ WEAK
         └─ REJECTED
```

组件职责：

- `RetrievalQueryPlanner`：把原始用户文本转换为唯一查询计划。
- `SynonymLexicon`：提供版本化的受控同义词组，不执行在线生成。
- `LexicalRetriever`：分别执行原词和同义词 FTS 召回，并保留两份原始 BM25 排名。
- `LocalEmbeddingRuntime`：加载本地模型、分词、ONNX 推理和向量归一化。
- `EmbeddingIndexer`：按文件 hash 异步生成文档向量。
- `ExactVectorIndex`：以内存连续矩阵执行精确点积。
- `HybridReranker`：按 RRF 和确定性特征排序。
- `RelevancePolicy`：把候选划分为 STRONG、WEAK 或 REJECTED。
- `KnowledgeStore`：对 ASK、`/find` 和工具暴露统一异步接口。

## 6. 查询模型

### 6.1 RetrievalQuery

```java
record RetrievalQuery(
        String original,
        String semanticText,
        LocalDate fromInclusive,
        LocalDate toInclusive,
        List<QueryGroup> lexicalGroups,
        List<String> entityAnchors,
        boolean searchable) {
}
```

`original` 只用于最终恢复用户原话，不写诊断日志。`semanticText` 送入本地查询 encoder。`lexicalGroups` 送入 FTS 和证据计算。

### 6.2 规范化顺序

1. Unicode NFKC 规范化。
2. ASCII 转小写。
3. 删除 `@智能秘书`、斜杠命令和纯控制前缀。
4. 提取 ISO 日期和中文相对时间，形成结构化日期窗口。
5. 对词法文本删除问句噪声短语。
6. 对语义文本只删除控制内容，保留自然语言语义。
7. 提取实体锚点。
8. 对剩余 CJK run 生成检索单元。

问句噪声以短语处理，不逐字删除。初始集合包括：

```text
请问
帮我查一下
告诉我
是什么
怎么
怎么样
有没有
相关的
```

“当时、之前、最近、半年前、去年”等时间表达先转成日期或回忆线索，再从词法正文中移除。

示例：

```text
原话：@智能秘书，请问张三现在的邮箱是什么？
semanticText：张三现在的邮箱是什么
lexicalText：张三 邮箱
```

### 6.3 实体锚点

每个用户维护进程内实体词典。词典来源：

- 所有非空 `aliases`
- 所有非空 `who`
- people、projects 类型卡片的 title
- title 和 aliases 中的 ASCII 标识符

实体词条长度限定为 2～64 个 Unicode code point。纯停用词、日期和类型名称不进入词典。词典使用最长匹配，实体位置以分隔符替换，避免删除后生成跨边界 bigram。

```text
输入：张三邮箱是什么
词典命中：张三
entityAnchors：["张三"]
剩余词法文本：邮箱
```

实体词典在卡片 upsert/delete 后增量更新；启动时从 SQLite metadata 重建，不扫描 Markdown。

### 6.4 QueryGroup

```java
record QueryGroup(
        String source,
        List<String> originalUnits,
        List<String> synonymAlternatives,
        Kind kind) {

    enum Kind {
        ENTITY,
        ASCII_IDENTIFIER,
        CJK_TERM
    }
}
```

规则：

- `source` 永远保存用户实际输入中抽出的原词。
- `originalUnits`：原词自身及其必要的 CJK bigram，只进入原词通道。
- `synonymAlternatives`：实体规范名、卡片 aliases 或受控领域同义词，只进入同义词通道，不包含 source。
- ENTITY：用户实际输入的实体是 source；规范名及其他 aliases 是 synonym alternatives。
- ASCII_IDENTIFIER：完整 ASCII token 是 source；受控拼写和术语对照只进入 synonym alternatives。
- CJK_TERM：原 run 及其长度为 2 的 bigram 进入 originalUnits；受控中文/英文同义词进入 synonymAlternatives。
- 不自动生成开放式同义词；同义词只能来自卡片 aliases 或固定、测试覆盖的领域词表。
- 单次查询最多保留 8 个 group；超过时优先保留实体、ASCII 标识符和较长 CJK run。

CJK group 的匹配不是“任意一个 bigram 即强命中”。证据阶段计算该组 bigram 覆盖率；召回阶段仍允许 OR，以避免漏检。

### 6.5 SynonymLexicon

同义词分为两类，职责不同：

1. **卡片本地 aliases**：沿用现有 `- 别名：` 字段。它只增强所属卡片，在 FTS `aliases` 字段中获得高权重。现有 `SKILL.md` 在归档会议、决定和待办时补充中英文、常见拼写和明显同指词的行为保持不变。
2. **全局受控词典**：只存跨卡片稳定成立的等价术语，例如 `licence / license / 许可证`、`评审 / review`。它由本地资源加载，用于查询侧扩展。

全局词典使用版本化 JSON：

```json
{
  "version": 1,
  "groups": [
    {
      "id": "licence",
      "terms": ["licence", "license", "许可证"]
    },
    {
      "id": "review",
      "terms": ["评审", "review"]
    }
  ]
}
```

约束：

- 词典随安装包发布，完全离线。
- 不调用 LLM、网络或 embedding 自动生成同义词。
- 不从任意卡片 aliases 自动推导全局同义词图，防止某张卡的上下文词污染全部查询。
- 一个 term 最多属于一个全局等价组；资源加载时发现冲突则启动失败并回退到无全局扩展的原词检索。
- 原始词始终保留；扩展词不覆盖 source。
- 词典版本进入检索诊断和黄金集基线，但不触发文档向量重建。
- `SKILL.md` 中固定对照与 JSON 的固定组必须有一致性测试，避免归档协议和查询行为漂移。

示例：

```text
查询：licence 怎么申请

原词 groups：
- licence
- 申请

同义词 groups：
- license | 许可证
- 申请
```

卡片 aliases 中已经包含 `licence` 时，原词通道会直接命中该卡；只有卡片没有原词、仅包含 `许可证` 时，才依赖同义词通道。

### 6.6 无检索查询

去除控制内容、日期和停用短语后没有实体、ASCII token 或长度至少 2 的 CJK run 时，`searchable=false`。例如纯“你好”不调用 FTS 和 ONNX。

## 7. 词法召回

### 7.1 FTS 字段

保持 FTS5 主表：

```sql
CREATE VIRTUAL TABLE cards_fts USING fts5(
  path, title, aliases, body, type, who, date,
  tokenize = 'unicode61'
);
```

`cards_meta` 增加原始 title 和 aliases，供实体词典和精确字段证据使用：

```sql
ALTER TABLE cards_meta ADD COLUMN title TEXT;
ALTER TABLE cards_meta ADD COLUMN aliases TEXT;
```

实际迁移通过 schema 重建完成，不对旧 SQLite 直接依赖重复执行 `ALTER TABLE`。

### 7.2 原词与同义词双通道

词法检索必须产生两份独立排名：

1. `originalQuery`：只使用用户原词、实体锚点和原始 CJK 单元。
2. `synonymQuery`：只使用受控扩展词，扩展词命中单独记为 synonym evidence；实体原词证据由原词通道提供。

每个通道内部仍做宽召回：原词通道只使用 `originalUnits`，同义词通道只使用 `synonymAlternatives`，各自组内使用 OR；不同 group 不在召回阶段强制 AND。两个通道各取 top 50，不能把所有原词和扩展词平铺进一份无法区分来源的 BM25 结果。

双通道的原因：

- 原词命中比查询扩展更可靠，必须保留独立证据。
- 稀有同义词的 IDF 可能异常放大，不能让它覆盖原词排名。
- 同义词召回可以扩大候选，但不能凭单个扩展词直接成为 STRONG。
- 卡片 aliases 中包含用户原词时，仍属于原词通道的高价值字段命中。

两个宽召回通道都只产生候选，不直接产生 STRONG。

### 7.3 字段权重

初始 SQL：

```sql
SELECT rowid,
       path,
       snippet(cards_fts, 3, '', '', '…', 20) AS snippet,
       bm25(
           cards_fts,
           0.0,
           8.0,
           10.0,
           1.0,
           2.0,
           6.0,
           1.0
       ) AS bm25_rank
FROM cards_fts
WHERE cards_fts MATCH ?
ORDER BY bm25_rank
LIMIT 50;
```

权重顺序：

```text
path=0
title=8
aliases=10
body=1
type=2
who=6
date=1
```

这些是模型选型前的初始值。最终值只能通过开发黄金集调整，冻结验收集不得用于逐条调参。

### 7.4 BM25 契约

- `bm25_rank` 保留 SQLite 原始负值。
- 越小越相关。
- 不转换为 0～1。
- 不设置跨语料库固定 BM25 数值阈值。
- BM25 只决定词法候选内部排名和融合排名。
- API、UI 和模型提示不把 BM25 描述为可信度。

### 7.5 词法证据探测

两路宽召回合并后，先按初步加权 RRF 截取最多 100 个 rowid，再进行批量证据探测：

1. 每个 QueryGroup 生成独立 MATCH 表达式。
2. 原词和同义词分别探测，不能合并来源。
3. 使用一条批量 SQL 判断该 group 命中的候选 rowid。
4. 对 title、aliases、who 使用字段限定 MATCH，计算高价值字段命中。
5. 记录 `originalCoverage`、`synonymCoverage` 和 synonym group IDs。
6. 每个查询最多 8 个 group，因此最多执行有限、可预测的批量证据 SQL。
7. 不读取完整 Markdown，不把完整 body 传回 Java。

该设计避免按“候选 × group”执行 N+1 SQL，也不需要复制全量正文 token 表。

## 8. 本地 ONNX embedding

### 8.1 强制本地边界

- 只实现 `OnnxEmbeddingRuntime`。
- 不存在远程 provider、base URL、API key 或上传配置。
- 模型、tokenizer 和 manifest 随安装包发布。
- 完全断网时可以构建和查询向量。
- 模型初始化失败只降级纯 BM25。

保留 `EmbeddingRuntime` 接口是为了隔离 ONNX 细节和注入测试替身，不用于切换远程实现。

### 8.2 模型包

```text
models/embedding/
  model.onnx
  tokenizer.json
  manifest.json
  LICENSE
```

manifest 至少包含：

```json
{
  "modelId": "由模型选型门禁产出的固定标识",
  "modelVersion": "1",
  "dimensions": 384,
  "maxTokens": 512,
  "queryPrefix": "query: ",
  "documentPrefix": "passage: ",
  "pooling": "由模型声明的固定策略",
  "normalizedOutput": true,
  "textSchemaVersion": 1,
  "modelSha256": "发布模型的固定摘要",
  "tokenizerSha256": "发布 tokenizer 的固定摘要"
}
```

设计不预先写死模型名称。实现开始前必须执行模型选型门禁，并把唯一胜出模型及真实摘要写入发布资源；未完成该门禁不得合并语义检索。

候选模型必须：

- 支持简体中文、英文和混合文本。
- 输出约 384 维。
- 支持本地 CPU ONNX 推理。
- 许可证允许随应用分发。
- tokenizer 有可打包、可离线运行的 Java 方案。
- 不依赖远程代码。
- 通过第 17 节的质量和性能门禁。

### 8.3 运行接口

```java
interface EmbeddingRuntime extends AutoCloseable {
    ModelFingerprint fingerprint();
    int dimensions();
    int maxTokens();
    CompletableFuture<float[]> embedQuery(String text);
    CompletableFuture<List<float[]>> embedDocuments(List<String> texts);
    EmbeddingHealth health();
}
```

`OnnxEmbeddingRuntime` 为进程级共享、延迟初始化组件。查询推理优先于后台文档推理。输出统一校验维度、有限值和范数，并进行 L2 归一化。

### 8.4 Tokenizer

tokenizer 必须与模型包绑定，其 SHA 和行为属于 fingerprint。模型选型门禁同时选择 tokenizer 运行方案。

要求：

- 不访问网络。
- 支持模型要求的 special tokens、padding、truncation 和 attention mask。
- 与模型原始 tokenizer 在固定测试语料上的 token IDs 完全一致。
- macOS x64/ARM64、Windows x64 和受支持 Linux 架构通过打包测试。
- tokenizer 或 native library 加载失败时进入 `DEGRADED`，不回退到不兼容的简化分词。

优先选择可由纯 Java tokenizer 正确执行的候选；若胜出模型需要 native tokenizer，则其 native 产物必须与 ONNX Runtime 一起纳入平台签名、公证和离线安装测试。

## 9. 文档规范化与切块

### 9.1 规范文本

```text
passage:
title: 张三联系方式
aliases: 张工 | 老张
type: person
who: 张三
status: active

张三当前邮箱为 zhangsan@example.com。
```

纳入 title、aliases、type、who、status 和正文。不纳入绝对路径、mtime、SQLite 字段或无语义 Markdown 装饰。

`EmbeddingTextBuilder` 是唯一规范文本生成器。格式变化必须提升 `textSchemaVersion` 并重建向量。

### 9.2 Token 预算

以 `maxTokens=512` 的模型为基线：

```text
元数据头：最多 96 tokens
正文块：最多 320 tokens
相邻重叠：48 tokens
前缀、special tokens 和安全余量：约 96 tokens
```

最终实现从 manifest 读取模型上限，但正文预算比例保持确定性，不能依赖平台动态变化。

### 9.3 切块顺序

1. Markdown 标题边界。
2. 空行段落。
3. 列表项或表格行。
4. 句子边界。
5. token 硬截断。

每个 chunk 重复 title、aliases、type、who 和 status。代码块优先保持完整；超过正文预算后按行切分。

不设置静默的每文件 chunk 截断。总活动 chunk 超过 20,000 时进入超规模状态，但不丢弃文件尾部。

### 9.4 Chunk

```java
record EmbeddingChunk(
        String path,
        int chunkNo,
        int bodyStart,
        int bodyEnd,
        String canonicalText,
        String contentHash,
        String preview) {
}
```

hash：

```text
SHA-256(modelFingerprint + textSchemaVersion + canonicalText)
```

## 10. 向量持久化

### 10.1 Schema

```sql
CREATE TABLE embedding_models (
  fingerprint  TEXT PRIMARY KEY,
  dimensions   INTEGER NOT NULL,
  text_schema  INTEGER NOT NULL,
  state        TEXT NOT NULL,
  created_at   INTEGER NOT NULL,
  completed_at INTEGER
);

CREATE TABLE card_embeddings (
  path              TEXT NOT NULL,
  chunk_no          INTEGER NOT NULL,
  model_fingerprint TEXT NOT NULL,
  content_hash      TEXT NOT NULL,
  body_start        INTEGER NOT NULL,
  body_end          INTEGER NOT NULL,
  preview           TEXT,
  vector            BLOB NOT NULL,
  PRIMARY KEY(path, chunk_no, model_fingerprint)
);

CREATE INDEX idx_embeddings_model
ON card_embeddings(model_fingerprint);

CREATE TABLE embedding_jobs (
  path              TEXT NOT NULL,
  model_fingerprint TEXT NOT NULL,
  content_hash      TEXT NOT NULL,
  priority          INTEGER NOT NULL,
  state             TEXT NOT NULL,
  attempts          INTEGER NOT NULL,
  next_retry_at     INTEGER,
  last_error        TEXT,
  updated_at        INTEGER NOT NULL,
  PRIMARY KEY(path, model_fingerprint)
);
```

### 10.2 BLOB

- little-endian float32
- 384 维时固定 1,536 bytes
- 写入前 L2 归一化
- 读取时校验 `length == dimensions * 4`
- 拒绝 NaN、Infinity 和零向量
- 损坏行删除并创建重建 job

## 11. 增量生成

卡片写入成功后的顺序：

```text
Markdown 原子写入
→ FTS 单文件 upsert
→ 使该 path 的活动旧向量失效
→ 创建或替换 PENDING job
→ 写操作向用户返回成功
→ 后台读取最新文件
→ 切块并批量 ONNX 推理
→ SQLite 事务替换该 path 的全部向量
→ 更新内存 delta
```

旧向量必须立即失效，不能在新向量生成期间继续表达已经修改或撤销的事实。等待期间该卡片仅参与 BM25。

job 完成前再次读取当前文件 hash。若与 job hash 不同，丢弃推理结果并使用新 hash 重新排队。

### 11.1 状态机

```text
PENDING
  → RUNNING
      → DONE
      → RETRY_WAIT
      → FAILED
```

- 异常退出遗留的 RUNNING 在下次启动恢复成 PENDING。
- 单文件失败最多重试 3 次。
- 模型全局损坏不逐文件重试，runtime 进入 DEGRADED。
- 文件再次修改时，新 hash 替换旧 job。
- FAILED 文件仍保留 FTS。

优先级：

```text
查询 embedding
> 最近修改卡片
> 新建卡片
> 首次全库构建
> 模型升级重建
```

## 12. 内存精确向量索引

### 12.1 Snapshot

```java
record VectorSnapshot(
        ModelFingerprint fingerprint,
        int dimensions,
        float[] matrix,
        VectorRef[] refs) {
}
```

向量按 chunk 连续排列。查询向量已归一化，因此 cosine 等于点积。

### 12.2 主快照与 delta

- 主快照不可变，通过 `AtomicReference` 发布。
- 新增和更新进入小型 delta。
- 删除或失效 path 进入 tombstone。
- 查询扫描主快照时跳过 tombstone，再扫描 delta。
- delta 达到主快照 5% 或 1,000 chunks 时后台重建连续快照。
- 新快照构建完成后原子替换。

同一路径多个 chunk 只保留 cosine 最高的 chunk 进入向量卡片排名，避免长文件因 chunk 数量多获得额外融合票数。

### 12.3 规模边界

第一版保障：

```text
active chunks <= 20,000
dimensions ≈ 384
raw matrix ≈ 30MB
```

超过 20,000：

- 不静默删除或截断向量。
- 显示超规模状态。
- 继续精确扫描，但不承诺第 17 节 P95。
- 不自动安装或切换 HNSW。

## 13. 混合排序

### 13.1 候选

- 原词 FTS top 50 paths
- 同义词 FTS top 50 paths
- Vector top 50 paths
- 三路并集最多 150 paths
- 先按初步加权 RRF 截取 100 paths，再执行详细词法证据探测
- 日期、用户和明确状态过滤在分类前强制执行

### 13.2 RRF

```text
rrf(path) =
    1.0 / (60 + originalLexicalRank)
  + 0.7 / (60 + synonymLexicalRank)
  + 1.0 / (60 + vectorRank)
```

候选不在某列表时，该项为 0。同义词通道初始权重为 0.7，体现它弱于用户原词；最终权重必须通过开发黄金集确定。RRF 只融合排名，不比较 BM25 与 cosine 的数值。

### 13.3 证据

```java
record LexicalEvidence(
        int matchedOriginalGroups,
        int matchedSynonymGroups,
        int totalGroups,
        boolean allEntityAnchorsMatched,
        boolean exactTitleMatch,
        boolean exactAliasMatch,
        boolean exactWhoMatch,
        boolean exactPhraseMatch,
        Set<String> matchedSynonymGroupIds,
        Integer originalLexicalRank,
        Integer synonymLexicalRank,
        Integer vectorRank) {

    double originalCoverage();
    double synonymCoverage();
}
```

### 13.4 稳定排序

1. relevance：STRONG 在 WEAK 前。
2. 所有实体锚点命中。
3. 用户原词在 title/aliases/who 精确命中。
4. 原词 group 覆盖率。
5. 同义词 group 覆盖率。
6. 加权 RRF。
7. 原词 BM25 rank。
8. path 字典序。

## 14. 相关性分类

### 14.1 STRONG

满足任一条件：

1. 用户原词在 title、alias 或 who 精确命中，且所有实体锚点命中。
2. 所有实体锚点命中，原词 group 覆盖率至少 60%，原词 BM25 位于 top 20。
3. 同时位于原词 BM25 top 10 和向量 top 10，所有实体锚点命中，原词覆盖至少 40%。
4. 查询没有实体锚点时，原词 group 覆盖率为 100%，同时位于原词 BM25 top 10 和向量 top 10。
5. 所有实体锚点由用户原词命中，同义词 group 覆盖率为 100%，且候选同时位于同义词 BM25 top 10 和向量 top 10。

只有同义词命中、没有用户原词实体或向量佐证时，不能成为 STRONG。

### 14.2 WEAK

未达到 STRONG，但满足任一条件：

- 位于向量 top 10，缺少足够词法证据。
- 位于原词 BM25 top 20，原词覆盖不足。
- 位于同义词 BM25 top 20，但缺少实体或向量佐证。
- 只命中部分实体锚点。
- 单个宽泛词只命中正文。

### 14.3 REJECTED

- 日期或状态硬过滤失败。
- 查询有实体锚点，但候选一个实体锚点都未命中。
- 只命中停用短语或跨边界噪声 bigram。
- 不在原词、同义词或向量任一 top 50。

百分比和排名是初始设计常量。调整必须基于开发黄金集，并通过完整冻结验收集，不能针对单条线上问题热修魔法数字。

## 15. 异步 API 与入口行为

### 15.1 请求

```java
enum SearchMode {
    ASK_GROUNDING,
    KNOWLEDGE_TOOL,
    MANUAL_FIND
}

record SearchRequest(
        UUID requestId,
        String originalText,
        LocalDate today,
        SearchMode mode,
        int limit) {
}
```

### 15.2 结果

```java
enum Relevance {
    STRONG,
    WEAK
}

enum RetrievalSource {
    LEXICAL_ORIGINAL,
    LEXICAL_SYNONYM,
    VECTOR
}

record SearchHit(
        String relativePath,
        int line,
        String snippet,
        Relevance relevance,
        Set<RetrievalSource> sources,
        List<Reason> reasons,
        Integer originalLexicalRank,
        Integer synonymLexicalRank,
        Integer vectorRank,
        double rawOriginalBm25,
        double rawSynonymBm25,
        double cosine,
        double rrf) {
}
```

```java
enum RetrievalState {
    HYBRID_READY,
    LEXICAL_ONLY_BUILDING,
    LEXICAL_ONLY_DEGRADED,
    VECTOR_ONLY_DEGRADED,
    UNAVAILABLE
}

record SearchResponse(
        RetrievalState state,
        List<SearchHit> hits,
        Optional<RetrievalWarning> warning,
        SearchDiagnostics diagnostics) {
}
```

`KnowledgeStore` 主接口：

```java
CompletableFuture<SearchResponse> searchAsync(SearchRequest request);
```

现有 `search(FindQuery)` 作为迁移适配器保留到所有调用方迁移完成，随后删除。`KnowledgeStore.Hit.score` 不继续作为新接口。

### 15.3 ASK

- `AskGrounding.prepareAsync()` 取代同步方法。
- 只附 STRONG，最多 5 张。
- 没有 STRONG 时不附 WEAK。
- 向量超时或 BUILDING 时允许使用强词法结果。
- FTS 故障时，纯向量结果最多为 WEAK，因此 ASK 不自动附卡。
- 检索不可用时模型仍可回答一般问题，但收到明确的“本地知识检索不可用”系统提示。
- 不可用或降级零结果不能解释为“未归档”。

### 15.4 knowledge_search

- AgentScope 工具通过 `Mono.fromFuture(searchAsync(...))` 调用。
- 先返回 STRONG，再返回明确标记的 WEAK。
- 最多 20 张。
- 返回检索模式和降级警告。
- 模型仍需 `read_file` 才能形成知识结论。

### 15.5 /find

- 异步执行。
- STRONG 和 WEAK 分区显示。
- 显示“词法 + 本地语义”或“仅词法”。
- 支持显示命中原因。
- 新查询取消旧 Future，或通过 requestId 丢弃旧响应。
- 旧查询结果不得覆盖新查询。

## 16. 线程、连接与超时

### 16.1 SQLite

- 一个 writer connection，由单线程写队列独占。
- 一个 reader connection，由单线程检索执行器独占。
- 两个连接启用 WAL 和 busy timeout。
- JavaFX、Agent 和 embedding 线程不直接持有 JDBC Connection。
- embedding 持久化提交给 writer 队列。
- reconcile 仅在 writer 队列后台执行，不在查询热路径执行。

### 16.2 ONNX

- 进程级共享 Session。
- 延迟加载。
- 后台文档推理只允许一个批次并发。
- 每批初始 8～16 chunks，由模型基准确定。
- 查询任务在批次边界优先。
- JavaFX 线程不执行分词或推理。

### 16.3 查询预算

目标：

```text
QueryPlanner P95 < 5ms
FTS P95 < 30ms
query embedding P95 < 50ms
20K exact scan P95 < 30ms
fusion/classification P95 < 10ms
end-to-end P95 < 100ms
hard timeout < 200ms
```

超时：

- Vector 超时：使用词法结果。
- FTS 超时：向量结果只能为 WEAK。
- 两者超时：UNAVAILABLE。
- 取消无法立即中断的 ONNX 推理时允许推理结束，但必须丢弃结果。

## 17. 状态、降级与 UI

### 17.1 模型状态

```text
UNINITIALIZED
→ VALIDATING
→ LOADING
→ READY
→ DEGRADED
→ CLOSED
```

模型全局加载失败后不在每次查询重试。用户手动重试或应用下一次启动才重新初始化。

### 17.2 首次构建

```text
校验模型
→ 枚举 FTS paths
→ 创建 PENDING jobs
→ 后台批量生成
→ coverage = 100%
→ 构建 VectorSnapshot
→ 原子切换 HYBRID_READY
```

首次构建未完成前完全使用 BM25，不使用部分向量参与全局融合。

### 17.3 单文件变化

系统已 READY 后，单个新增或修改文件可暂时只有 BM25；其他已完成文件继续混合检索。

### 17.4 降级

向量不可用、FTS 正常：

- 使用加权 BM25。
- 允许强词法候选。
- UI 显示“当前仅使用关键词检索”。

FTS 不可用、向量正常：

- 所有结果最多 WEAK。
- ASK 不自动附卡。
- `/find` 和工具可以显示弱候选及警告。
- 后台重建 FTS。

两者不可用：

- 返回 UNAVAILABLE，不返回普通空列表。
- `read_file` 等直接文件能力不受影响。
- UI 提供重建索引操作。

### 17.5 UI 状态文本

构建：

```text
正在构建本地语义索引：1,240 / 8,320
当前使用关键词检索，完成后自动启用语义检索
```

就绪：

```text
本地语义索引已就绪 · 8,320 个片段
```

故障：

```text
本地语义索引不可用，当前仅使用关键词检索
[查看详情] [重试]
```

超规模：

```text
语义索引包含 23,410 个片段，超过第一版 20,000 个保障规模
```

构建状态只放在知识面板，不在每条聊天气泡重复显示。检索故障实际影响当前轮次时，对话中显示一次非阻塞提示。

## 18. 模型升级

模型 fingerprint 包含：

- model ID 和版本
- model SHA-256
- tokenizer SHA-256
- dimensions
- pooling
- query/document prefix
- textSchemaVersion

升级采用双版本：

```text
旧 fingerprint：READY，继续服务
新 fingerprint：BUILDING，后台生成
```

新版本覆盖率达到 100% 后：

1. 构建新 VectorSnapshot。
2. 原子切换 active fingerprint。
3. 新模型标记 READY。
4. 旧模型标记 RETIRED。
5. 延迟删除旧向量。

禁止在一次余弦排名中混用不同 fingerprint。

## 19. 数据迁移

Markdown 和现有卡片不改写。

版本拆分：

```text
fts_schema_version
embedding_schema_version
model_fingerprint
text_schema_version
```

升级：

1. 保留现有 FTS 数据。
2. 若 `cards_meta` 结构变化，仅重建派生 FTS/meta，不改 Markdown。
3. 创建 embedding 表。
4. 校验安装包模型。
5. 根据 FTS path 创建后台 jobs。
6. UI 立即使用修正后的纯 BM25。
7. 向量覆盖 100% 后切换 HYBRID_READY。

embedding 表损坏只重建 embedding。FTS 损坏只重建 FTS。任一派生索引故障都不能删除或改写 Markdown。

## 20. 测试设计

### 20.1 黄金集

建立 300～500 张合成知识卡和 200 条标注查询：

```java
record GoldenQuery(
        String query,
        LocalDate today,
        Set<String> requiredPaths,
        Set<String> acceptablePaths,
        Set<String> forbiddenPaths,
        SearchIntent intent) {
}
```

70% 作为开发集，30% 冻结为验收集。

覆盖：

- 精确中文
- 自然中文问句
- 中英混合
- 卡片 aliases 直接命中
- 全局受控同义词扩展命中
- 同义词扩展误命中和多义词污染
- license/licence 等受控拼写差异
- 无共同关键词的语义表达
- 日期窗口
- 当前与过期冲突
- 同名不同人
- 纯寒暄
- 无答案
- 字面相似但主体错误

### 20.2 质量门禁

- required Recall@5 >= 95%
- ASK Precision@5 >= 90%
- forbidden path 被 ASK 自动附上的数量为 0
- 精确 title/alias/who 回归通过率 100%
- 现有 `SKILL.md` 固定别名归档用例通过率 100%
- 受控同义词查询子集 Recall@5 不低于 95%
- 只有同义词、没有实体或向量佐证的候选成为 ASK STRONG 的数量为 0
- 日期窗口外候选成为 STRONG 的数量为 0
- 无答案查询产生 STRONG 的数量为 0
- 混合检索不降低纯 BM25 精确查询 Recall@5
- 语义查询子集 Recall@5 相比修正 BM25 至少提高 15 个百分点

### 20.3 模型选型门禁

所有候选在相同 chunk、RRF、分类规则和目标设备上比较：

- 许可证
- 模型及 tokenizer 大小
- 安装后 RSS
- 中文和中英混合 Recall@5
- 语义子集提升
- query P50/P95/P99
- 批量索引吞吐
- tokenizer 跨平台一致性
- native 打包稳定性

门禁只允许一个模型进入发布包。模型资源和 manifest SHA 固定后才允许合并。

### 20.4 单元测试

RetrievalQueryPlanner：

- NFKC、大小写、标点
- 日期分离
- 停用短语
- 实体最长匹配
- 删除实体后不产生跨边界 bigram
- 同义词组内扩展且不覆盖原词
- 同义词 term 冲突时禁用全局扩展
- `SKILL.md` 固定对照与词典 JSON 一致
- 纯寒暄不检索

LexicalRetriever：

- 原词和同义词分别返回原始负 BM25 顺序
- title/alias/who 权重高于正文
- 无伪 0～1 score
- 卡片 alias 中包含原查询词时归入原词通道
- 仅扩展词命中时归入同义词通道
- 批量 group 证据无 N+1

Vector：

- tokenizer 固定样例 token IDs
- pooling 和归一化
- cosine/点积一致
- BLOB 编解码
- NaN、Infinity、零向量拒绝
- path 多 chunk 去重

Hybrid：

- 三路加权 RRF
- 缺失任一排名
- 同义词通道权重低于原词通道
- 稳定 tie-break
- STRONG/WEAK/REJECTED 边界
- 纯向量不能成为 ASK STRONG
- 纯同义词命中不能成为 ASK STRONG

Jobs：

- hash 不变不重算
- 变化替换旧 job
- 事务失败无半张卡
- RUNNING 重启恢复
- 三次失败进入 FAILED
- 模型双版本原子切换
- 删除 path 产生 tombstone

### 20.5 集成和故障测试

- 三个入口基础排序和分类一致
- ASK 只截取前 5 个 STRONG
- BUILDING 纯 BM25
- ONNX 故障纯 BM25
- FTS 故障纯向量只有 WEAK
- 两者故障 UNAVAILABLE
- 故障空结果不等于未归档
- 用户切换后丢弃旧结果
- 新请求不被旧响应覆盖
- 查询、upsert、embedding、snapshot compaction 并发
- 磁盘满、BLOB 损坏、模型 SHA 错误、SQLite 锁竞争

真实模型测试标记为 `model`，在发布 CI 的目标平台运行；普通单测使用 `FakeEmbeddingRuntime`。

## 21. 性能与打包验收

规模：

```text
1,000 chunks
10,000 chunks
20,000 chunks
```

性能门禁采用第 16.3 节预算。基准必须预热，记录 P50/P95/P99，不得只打印一次耗时。

UI：

- 首屏不等待模型。
- JavaFX pulse 中不存在 SQLite、tokenizer、ONNX 或向量循环。
- 后台全库构建时窗口缩放和输入无明显停顿。

平台：

- macOS x64
- macOS ARM64
- Windows x64
- 项目实际支持的 Linux 架构

每个平台验证：

- ONNX Runtime 和 tokenizer native library 加载
- JPMS、jlink、jpackage
- 模型路径包含空格和中文
- 模型文件签名、公证和 SHA
- 完全断网运行

## 22. 可观测性

允许记录：

- 总耗时及各阶段耗时
- 原词 BM25、同义词 BM25、向量候选数量
- 命中的同义词 group ID；不记录原始用户文本
- STRONG/WEAK 数量
- runtime 状态
- embedding 进度
- retry/error 类型
- chunk 数和 snapshot 版本

禁止记录：

- 用户完整问题
- 卡片正文
- embedding 数值
- tokenizer 输入

## 23. 安全开关

默认启用本地语义检索，同时提供本地故障开关：

```text
-Dkelly.semanticSearch=false
```

关闭时：

- 不初始化 ONNX Runtime。
- 不执行 embedding jobs。
- 使用修正后的 BM25。
- 不删除已有向量。
- 不修改 Markdown。
- 下次启用后继续已有进度。

该开关不是远程配置入口。

## 24. 交付顺序约束

实现计划必须按以下依赖顺序拆分：

1. 建立黄金集和当前纯 BM25 基线。
2. 实现统一查询计划、卡片 aliases 保留和受控 SynonymLexicon。
3. 实现原词/同义词双词法通道与加权 BM25。
4. 实现结构化 SearchResponse 和三个入口异步化。
5. 完成模型选型门禁并锁定发布资源。
6. 实现本地 tokenizer 与 ONNX runtime。
7. 实现 embedding schema、jobs 和增量生成。
8. 实现内存精确向量索引。
9. 实现三路加权 RRF 和 RelevancePolicy。
10. 实现 UI 状态与降级。
11. 完成迁移、并发、性能和平台打包验收。

步骤 1～4 必须可独立以“原词 + 受控同义词 BM25”模式交付和回归。向量子系统不得成为正确词法检索的前置条件。

## 25. 完成定义

只有同时满足以下条件，本设计才算实现完成：

- 当前 BM25 负值转换代码已删除。
- 现有卡片 aliases 归档和检索行为保留。
- 原词、受控同义词和向量三路排名可以独立诊断。
- 不存在 LLM 或网络动态生成同义词的路径。
- 纯同义词命中不能独立成为 ASK STRONG。
- ASK、`/find` 和 `knowledge_search` 共用统一异步检索管线。
- 本地 ONNX 模型和 tokenizer 在所有目标平台离线工作。
- 不存在远程 embedding 网络路径。
- 首次构建和模型升级均不阻塞 JavaFX。
- 质量、性能和打包门禁全部通过。
- 故障状态不会伪装成未归档。
- 20,000 chunks 下达到约定 P95。
- Markdown 在所有索引故障和迁移场景中保持不变。
