# Kelly 长期记忆生产级方案 v2

> 版本：v2.0
> 日期：2026-09-23
> 状态：设计已确认，待实施计划
> 前序文档：`2026-09-22-kelly-long-term-memory-upgrade.md`（保留为评审前版本）
> 约束：本地运行、零远程 embedding、未经用户明确批准不得实施

---

## 1. 结论与核心决策

Kelly v2 将 `knowledge/**` 卡片迁移为 SQLite 权威存储，同时把检索索引与权威数据物理分离：

- `.kelly-data.db` 是 knowledge 卡片的唯一真相源，禁止自动删除。
- `.kelly-index.db` 保存 FTS、chunks、vectors 和索引任务，可删除重建。
- `AGENTS.md`、`MEMORY.md`、`memory/`、`skills/` 继续以文件为各自真相源。
- knowledge Markdown 仅用于显式导入、导出和迁移归档，不做自动镜像或双向同步。
- 卡片使用稳定 `card_id`；`path` 是唯一、可变的业务地址。
- 检索采用原词 FTS、受控同义词 FTS、本地向量三路召回。
- 加权 RRF 只负责排序；独立 `RelevancePolicy` 决定 STRONG、WEAK、REJECTED。
- embedding 使用用户手动安装的 `bge-small-zh-v1.5` int8 完整模型包。
- 所有 SQLite、tokenizer、ONNX 和向量计算均不得运行在 JavaFX 线程。

本设计取代前序文档中的以下决策：

- 使用 `.kelly-index.db` 同时承载正文和索引；
- 使用 `path` 作为内部主键；
- 30 天自动 rename；
- 一张卡始终只有一个向量；
- 每次查询从 SQLite 全量读取向量 BLOB；
- 对 RRF 使用 `0.20` 或 `0.05` 阈值；
- 用户只放置单个 ONNX 文件即可工作。

---

## 2. 目标与非目标

### 2.1 目标

1. 为 knowledge 卡片提供事务化、可恢复、可审计的权威存储。
2. 提升精确、别名、错别字和纯语义查询的召回质量。
3. 在本地完成 embedding 和向量检索，不上传查询、正文或向量。
4. 支持卡片 rename、merge、restore、软删除和永久清理。
5. 保证索引损坏不影响权威正文，权威库损坏不被静默覆盖。
6. 在 10,000 张卡片、20,000 个活跃 chunks 内满足发布门禁。

### 2.2 非目标

- 不提供远程 embedding API。
- 不引入独立向量数据库或 SQLite 向量扩展。
- 不实现 knowledge Markdown 自动镜像或双向同步。
- 不把 `AGENTS.md`、`MEMORY.md`、`memory/`、`skills/` 迁入权威数据库。
- 不实现多设备并发写、CRDT 或 SQLite replication。
- 不把 BM25、cosine 或 RRF 分数解释为事实概率。
- v1 不提供应用层正文加密或 SQLCipher。

---

## 3. 总体架构

```text
                             ┌────────────────────────────┐
                             │        JavaFX UI           │
                             │ Chat / Knowledge / Todo    │
                             └─────────────┬──────────────┘
                                           │ async
                             ┌─────────────▼──────────────┐
                             │     KnowledgeRepository    │
                             │ read/write/rename/history  │
                             └───────┬───────────┬────────┘
                                     │           │
                    authoritative    │           │ derived
                                     │           │
                    ┌────────────────▼──┐   ┌────▼────────────────┐
                    │ .kelly-data.db    │   │ .kelly-index.db     │
                    │ cards/revisions   │   │ FTS/chunks/vectors  │
                    │ path history      │   │ generations/state   │
                    │ index outbox      │   │                     │
                    └───────────────────┘   └─────────┬───────────┘
                                                     │
                                           ┌─────────▼──────────┐
                                           │ In-memory vectors  │
                                           │ contiguous matrix  │
                                           └────────────────────┘

 Files as independent truth:
 AGENTS.md / MEMORY.md / memory/ / skills/
```

### 3.1 物理位置

权威数据库不得跟随用户可配置的 `workspaceDir`。固定布局：

```text
~/.kelly/kelsy/
├── data/<canonical-user-id>/
│   ├── .kelly-data.db
│   ├── .kelly-index.db
│   └── backups/
├── models/
│   └── bge-small-zh-v1.5-int8/
└── workspace/<user>/
    ├── AGENTS.md
    ├── MEMORY.md
    ├── memory/
    └── skills/
```

数据库目录必须位于本机文件系统，不得位于 iCloud、Dropbox、OneDrive、网络盘或用户可同步工作区。

---

## 4. 权威数据模型

### 4.1 卡片身份

- `card_id` 是永久身份，使用应用生成的 UUIDv7。
- `path` 是对用户和 LLM 暴露的业务地址，必须唯一但允许 rename。
- 内部引用、revision、chunk 和审计记录均关联 `card_id`，不得依赖 path 保持不变。
- LLM 日常工具调用继续使用 path，不暴露内部 ID，以控制认知和 token 成本。

### 4.2 概念 schema

```sql
CREATE TABLE cards (
  card_id         TEXT PRIMARY KEY,
  path            TEXT NOT NULL,
  path_key        TEXT NOT NULL UNIQUE,
  category        TEXT NOT NULL,
  title           TEXT,
  aliases_json    TEXT NOT NULL DEFAULT '[]',
  card_date       TEXT,
  person          TEXT,
  topic           TEXT,
  status          TEXT,
  body            TEXT NOT NULL,
  content_sha256  BLOB NOT NULL,
  owner           TEXT NOT NULL,
  source          TEXT,
  row_version     INTEGER NOT NULL,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL,
  deleted_at      INTEGER
);

CREATE TABLE card_revisions (
  revision_id     TEXT PRIMARY KEY,
  card_id         TEXT NOT NULL,
  revision_no     INTEGER NOT NULL,
  event_type      TEXT NOT NULL,
  path            TEXT NOT NULL,
  metadata_json   TEXT NOT NULL,
  body            TEXT NOT NULL,
  content_sha256  BLOB NOT NULL,
  actor           TEXT NOT NULL,
  source          TEXT,
  created_at      INTEGER NOT NULL,
  FOREIGN KEY (card_id) REFERENCES cards(card_id),
  UNIQUE (card_id, revision_no)
);

CREATE TABLE path_history (
  path_key        TEXT PRIMARY KEY,
  old_path        TEXT NOT NULL,
  card_id         TEXT NOT NULL,
  changed_at      INTEGER NOT NULL,
  FOREIGN KEY (card_id) REFERENCES cards(card_id)
);

CREATE TABLE index_outbox (
  job_id            TEXT PRIMARY KEY,
  card_id           TEXT NOT NULL,
  operation         TEXT NOT NULL,
  source_row_version INTEGER NOT NULL,
  source_sha256     BLOB,
  attempts          INTEGER NOT NULL DEFAULT 0,
  next_retry_at     INTEGER NOT NULL,
  last_error_code   TEXT,
  state             TEXT NOT NULL,
  created_at        INTEGER NOT NULL,
  updated_at        INTEGER NOT NULL,
  FOREIGN KEY (card_id) REFERENCES cards(card_id)
);
```

所有时间字段使用 Unix epoch milliseconds。实际 schema 必须为 owner、event、operation 和 state 使用封闭枚举 CHECK 约束，并在每条连接上显式执行：

```sql
PRAGMA foreign_keys = ON;
PRAGMA busy_timeout = 5000;
```

### 4.3 正文与元数据

- 正文使用 SQLite `TEXT`，不重复保存 UTF-8 BLOB。
- 写入和导入时把正文换行统一为 LF，不改变其他字符。
- `content_sha256` 保存 LF 规范化正文 UTF-8 字节的 SHA-256，用于迁移、备份和索引一致性校验。
- 结构化字段以 cards 显式列为权威。
- 写入接口显式接收元数据；缺失字段可由确定性 Markdown 解析器补全。
- path 只用于地址和 category 校验，不推断完整业务语义。

### 4.4 路径规则

- 允许跨平台可移植 UTF-8 和中文。
- 写入前统一 Unicode NFC。
- 禁止绝对路径、`..`、控制字符、NUL、平台保留名和不可导出字符组合。
- `path_key` 使用确定性的大小写折叠规则，防止 Windows/macOS 导出冲突。
- 每个 path segment 的 UTF-8 长度不超过 120 bytes，总相对 path 不超过 240 bytes。
- 核心 category 包括：
  - `people`
  - `projects`
  - `playbooks`
  - `inbox`
  - `meetings`
  - `decisions`
  - `todos`
- 允许自定义 category，但自定义类别只获得通用存储和检索能力。

### 4.5 revision、rename 与删除

- 每次正文或元数据变更都生成不可变 revision。
- revision 不按时间或数量自动清理。
- rename 保持 `card_id` 不变，在同一事务更新 path 并写入历史事件。
- 旧 path 写入 `path_history`，用于旧引用跳转。
- path_history 中的旧 path 持续保留并占用，除非用户显式永久清理，因此不得被另一张卡复用。
- 普通删除使用 `deleted_at` 软删除，并立即从派生索引移除。
- 永久清理是独立高风险操作，必须显式确认后才能删除卡片、revision 和 path history。
- path 冲突不得自动 rename、自动覆盖或根据 mtime/owner 判断。

---

## 5. 数据库持久化与并发

### 5.1 data.db

- `journal_mode=WAL`
- `synchronous=FULL`
- 单写队列串行提交正文、revision 和 index outbox 写事务
- 少量有界只读连接
- 禁止因 schema 版本、打开失败或完整性失败自动删除

### 5.2 index.db

- `journal_mode=WAL`
- `synchronous=NORMAL`
- 单写队列维护 FTS、chunks、vectors、generation 和重建进度
- 少量有界只读连接
- 允许隔离后整体重建

禁止多个线程共享同一个 JDBC Connection。不要使用通用多写连接池制造 SQLite writer 竞争。

### 5.3 schema migration

- 使用单调递增 schema version。
- 每次 data.db migration 前创建 SQLite 一致性快照。
- migration 必须在事务中执行。
- 禁止通过 DROP 用户正文表实现版本升级。
- migration 完成后运行完整性和外键检查。
- 任一步失败时保持旧 schema 和旧运行模式，不得半割接。

---

## 6. 写入与索引数据流

### 6.1 卡片写入

```text
validate path + metadata
  → begin data.db transaction
  → insert/update card
  → append immutable revision
  → append index outbox event
  → commit data.db
  → index worker consumes outbox
  → return write result
```

正文提交不等待 FTS 或 embedding。单卡提交后：

- 正文立即可按 card ID 或 path 读取；
- FTS 最迟 250ms 可检索；
- 向量最迟 5 秒可检索。

该新鲜度门禁只适用于单张交互式写入，不适用于首次导入或 generation 全量重建。

### 6.2 索引任务

data.db 的 `index_outbox` 与正文、revision 在同一事务提交，消除“正文已提交但索引任务未入队”的崩溃窗口。任务至少包含：

- card ID；
- source content hash；
- model/tokenizer fingerprint；
- target generation；
- attempts；
- next retry time；
- last error；
- state。

任务必须支持幂等执行、指数退避、断点续跑和 dead-letter 状态。应用关闭后，未完成任务在下次启动继续。删除或重建 index.db 后，worker 必须以 data.db 全量状态重新生成索引，不依赖旧 index 状态恢复。

### 6.3 path 冲突

返回结构化 `PathConflict`，包含现有 card、请求 path 和允许动作：

- 选择新 path；
- read-then-merge；
- 版本化覆盖。

调用方必须明确选择，系统不得根据“30 天”或 owner 自动决定。

---

## 7. Embedding 模型契约

### 7.1 固定模型

- 模型：`bge-small-zh-v1.5`
- 发布推理格式：int8 ONNX
- 维度：512
- fp32 作为离线质量基线
- int8 的 Recall@5 和 MRR 相对 fp32 下降均不得超过 3 个百分点

### 7.2 手动模型包

用户安装的是完整目录，不是单个 ONNX 文件：

```text
bge-small-zh-v1.5-int8/
├── model.onnx
├── tokenizer.json
├── tokenizer_config.json
└── manifest.json
```

manifest 必须声明并校验：

- model ID 和版本；
- tokenizer fingerprint；
- ONNX opset；
- input/output 节点；
- embedding 维度；
- 量化类型；
- query instruction；
- 每个文件的 SHA-256。

模型包缺失、损坏或版本不兼容时：

- Kelly 主程序正常启动；
- Kelsy 长期记忆子系统不得启用；
- UI 显示缺失文件、期望路径和校验失败原因。

Kelsy 接收第一条消息前必须完成 manifest 校验、tokenizer 加载、ONNX session 初始化和 smoke test。

### 7.3 输入处理

- query 使用 BGE 官方中文检索 instruction。
- document 不加 query instruction。
- 使用 CLS pooling。
- query 和 document 均执行 L2 normalization。
- tokenizer、截断、special token 和 normalization 必须由 golden vector 测试固定。

---

## 8. Chunk 与向量索引

### 8.1 自适应切块

- 短卡使用单个 chunk。
- 长卡优先按 Markdown heading 和段落切块。
- 单个正文 chunk 预算约 384 tokens。
- 相邻块最多重叠 64 tokens。
- 只有单段超过预算时才使用 token 窗口。

每个 chunk 必须记录：

- chunk ID；
- card ID；
- ordinal；
- heading；
- token 范围；
- source content hash；
- embedding generation。

### 8.2 Embedding 文本

使用确定性元数据头和 chunk 正文，字段顺序固定：

```text
title
aliases
person/who
type/category
date
heading
body
```

空字段跳过。不得调用 LLM 生成摘要或改写后再 embedding。

### 8.3 运行时索引

- index.db 持久化 float32 向量。
- 热查询使用进程内连续向量矩阵执行精确点积。
- 查询路径不得每次从 SQLite 解码全部向量 BLOB。
- upsert/delete 通过增量 delta 更新内存索引。
- 超过 20,000 个活跃 chunks 时触发容量告警，但 v1 不自动切换 ANN。

### 8.4 模型升级

模型或 tokenizer fingerprint 变化时：

1. 创建新 generation；
2. 在旧 generation 继续服务期间并行重建；
3. 校验 chunk 数、维度、hash 和质量；
4. 原子切换 active generation；
5. 延迟清理旧 generation。

禁止新旧模型向量混合参与同一次搜索。

---

## 9. 混合检索

### 9.1 统一入口

ASK、`/find` 和 `knowledge_search` 共用同一个查询规划、召回、融合与相关性引擎。不同入口只改变输出策略：

- ASK：只自动附加 STRONG；
- `/find`：可展示 STRONG 和 WEAK；
- `knowledge_search`：返回候选、证据类型和完整性状态。

### 9.2 三路召回

1. 原词 FTS；
2. 受控同义词 FTS；
3. 本地向量。

各路分别保留排名、原始证据和执行状态。结构化条件必须在召回和融合结果阶段双重硬过滤：

- user；
- date；
- status；
- category/type；
- `deleted_at`。

### 9.3 融合

- 使用加权 RRF，`k=60`。
- RRF 只决定顺序，不承担相关性阈值职责。
- 禁止对 RRF 使用 `0.20` 或 `0.05` 阈值。
- 通道权重只能在调参集上从预先声明的有限网格选择，选择后版本化；盲测集不得参与调参。

### 9.4 RelevancePolicy

`RelevancePolicy` 使用可解释证据划分：

- STRONG；
- WEAK；
- REJECTED。

证据至少包括：

- 实体锚点；
- 原词覆盖；
- 同义词覆盖；
- title/aliases/person 等字段命中；
- cosine；
- 向量第一名与后续候选差距；
- 明确回忆意图；
- 结构化过滤结果。

纯向量候选只有同时满足以下条件时才可成为 STRONG：

- 查询具有明确回忆意图；
- cosine 达到评测集校准门槛；
- 与后续候选有足够差距；
- user/date/status/type 等硬过滤全部通过。

具体门槛由冻结调参集产生，保存为版本化检索配置，并由盲测集验证。

### 9.5 ASK 输出

- 只附加 STRONG 候选。
- 候选数按证据自适应，通常 1～5 条，硬上限 8。
- 不得为凑满 top-k 加入 WEAK。
- 附加内容包括 path、命中原因和必要 snippet；正文由统一 DB-backed read 工具读取。

---

## 10. 线程、截止与资源门禁

### 10.1 线程规则

JavaFX 线程只负责：

- 提交异步检索；
- 更新 loading 状态；
- 接收最终结果；
- 更新 UI。

JavaFX 线程禁止执行：

- SQLite 查询或事务；
- tokenizer；
- ONNX 推理；
- 向量扫描；
- 文件树扫描；
- reconcile。

### 10.2 查询截止

- 每次有效检索使用 500ms 硬截止。
- 截止时使用已完成通道并标记完整性。
- 截止后完成的结果不得进入当前 LLM 请求。
- 本设计不设置检索 P50/P95/P99 门禁。
- 在模型预热、索引 READY、容量边界内，至少 99% 的有效查询必须在 500ms 内完成三路召回、融合和相关性判定。

完整完成率：

```text
500ms 内完整完成全部三路的有效查询数 / 有效检索查询总数
```

问候等不需要检索的输入、用户取消和系统休眠不计入分母。

### 10.3 容量与内存

- 最低基准硬件：4 核 CPU、8GB RAM、SSD、Java 21。
- 目标平台：Windows x64、Linux x64、macOS arm64。
- 保证容量：10,000 张卡片、20,000 个活跃 chunks。
- 稳态增量 RSS：不超过 400MiB。
- generation 并行重建峰值：不超过 700MiB。
- 超过容量边界时允许继续运行，但必须显示容量告警，不再承诺 99%/500ms。
- 单卡 data.db 写事务不设置延迟门禁。

---

## 11. 错误与降级

| 故障 | 行为 |
|---|---|
| 模型包缺失/校验失败 | Kelly 可启动；Kelsy 长期记忆子系统不可启用 |
| 运行期向量失败/超时 | 使用已完成 FTS 通道，标记 `PARTIAL` |
| 同义词通道失败 | 原词和向量继续，标记通道故障 |
| FTS 失败 | 向量结果可返回但标记词法证据缺失 |
| 全部检索通道失败 | 返回检索故障；禁止解释为“没有记忆” |
| index.db 损坏 | 隔离并后台重建，不影响 data.db |
| data.db 完整性失败 | 停止写入、只读隔离，等待用户选择恢复 |
| 索引任务重复失败 | 退避重试后进入 dead-letter，并在 UI 显示 |
| path 冲突 | 返回结构化冲突，事务不做隐式决定 |
| rename 目标冲突 | 整个事务回滚 |

结果完整性必须成为检索返回类型的一部分，不得只写日志。

---

## 12. 迁移、导入、导出与备份

### 12.1 首次迁移

采用分阶段割接：

1. 对现有 Markdown knowledge 目录创建只读归档和清单。
2. 创建新的 data.db，不修改原卡片。
3. 导入卡片并生成稳定 card ID。
4. 逐条校验 path、UTF-8 字节数和 SHA-256。
5. 校验卡片总数、分类总数和解析错误清单。
6. 通过用户确认后切换读写入口。
7. 保留原 Markdown 归档，直到用户显式清理。

任一步失败时继续使用旧文件模式，不得进入半割接状态。

### 12.2 显式导出

- 导出完整 knowledge 目录。
- 每个 Markdown 文件使用 YAML front matter 保存：
  - `kelly_card_id`
  - category
  - title
  - date
  - person
  - topic
  - status
  - revision/version
  - content hash
- 导出到临时目录后完成 hash 校验，再原子替换目标目录。

### 12.3 显式导入

- 导入前解析 front matter 并校验路径规则。
- 已存在 card ID 视为更新候选。
- path 冲突进入显式冲突流程。
- 不带 card ID 的外部 Markdown 视为新卡。
- 导入必须生成 revision，并记录 source。

### 12.4 自动备份

使用 SQLite Online Backup API 创建一致性快照：

- 每次 data.db schema migration 前一份；
- 最近 7 个日备份；
- 最近 4 个周备份。

备份完成后必须运行完整性检查。不得通过直接复制运行中的 `.db`、`-wal`、`-shm` 作为备份实现。

---

## 13. 安全与隐私

- data、index 和 backups 目录使用当前用户最小文件权限。
- v1 依赖 FileVault、BitLocker 等系统磁盘加密，不引入 SQLCipher。
- embedding 子系统不得包含网络访问代码。
- 不记录完整用户查询、卡片正文、向量、模型输入或私人路径到诊断日志。
- 真实个人评测数据不得提交仓库。
- 永久清理必须明确说明会同时删除正文、revision、path history 和备份中的未来可恢复性。

---

## 14. 质量评测

### 14.1 双层评测数据

1. 仓库内匿名/合成冻结集，用于 CI。
2. 本机真实数据回放，只输出聚合指标，不提交正文、query 或 path。

冻结集最低要求：

- 至少 100 个 queries；
- 覆盖至少 100 张 cards；
- 精确查询至少 20；
- 别名/拼写查询至少 20；
- 错别字查询至少 20；
- 纯语义查询至少 20；
- 日期/状态查询至少 20；
- 划分调参集与独立盲测集。

### 14.2 发布质量门禁

- Hybrid Recall@5 不低于 FTS baseline。
- Hybrid MRR 不低于 FTS baseline。
- Hybrid nDCG@5 不低于 FTS baseline。
- 错别字子集 Recall@5 至少提升 15 个百分点。
- 纯语义子集 Recall@5 至少提升 15 个百分点。
- ASK STRONG Precision@5 不低于 95%。
- user/date/status/deleted 过滤正确率为 100%。
- int8 相对 fp32 的 Recall@5 和 MRR 下降均不超过 3 个百分点。

示例数据不得作为最终门禁结果。所有指标必须由可重复执行的评测程序生成。

---

## 15. 测试与发布门禁

发布前必须通过：

1. 全量现有回归测试；
2. data.db schema、事务、revision、rename、merge、restore、软删除测试；
3. index job 幂等、重试、dead-letter、generation 切换测试；
4. tokenizer token ID、golden vector、CLS pooling、L2 normalization 测试；
5. 长卡尾部事实召回测试；
6. 三路 RRF 与 RelevancePolicy 测试；
7. 迁移中断、磁盘不足、坏卡片和重复 path 测试；
8. data.db/index.db 损坏隔离测试；
9. 在线备份和恢复演练；
10. Windows x64、Linux x64、macOS arm64 模型加载 smoke test；
11. 10k cards / 20k chunks 的 99%/500ms 完成率测试；
12. 稳态和 generation 重建内存门禁；
13. 匿名 CI 集和本机真实数据回放质量门禁。

任一硬门禁失败时，不得标记为生产可用。

---

## 16. 实施边界

后续实施计划必须拆分为可独立验收的阶段，至少覆盖：

1. data.db 与 Repository seam；
2. 迁移、导入、导出、备份和恢复；
3. DB-backed Agent 工具、KnowledgePane 和 Todo 读取；
4. index.db、FTS 与持久化任务队列；
5. 模型包校验、tokenizer、ONNX 和 chunk pipeline；
6. 内存向量矩阵与 generation；
7. 统一查询规划、三路召回、RRF 和 RelevancePolicy；
8. UI 就绪状态、索引进度、故障和完整性展示；
9. 质量评测、容量、性能、内存和三平台发布验证。

未经用户对实施计划的逐阶段批准，不得修改代码、依赖、测试或现有数据。

---

## 17. 红线

1. 禁止把现有可自动删除的 `.kelly-index.db` 直接升级为权威正文库。
2. 禁止 data.db 打开失败、版本不符或完整性失败时自动删除或重建空库。
3. 禁止 knowledge Markdown 与 data.db 同时作为可写真相源。
4. 禁止基于 30 天、mtime 或 owner 自动 rename 或覆盖。
5. 禁止在 JavaFX 线程执行 SQLite、tokenizer、ONNX、向量扫描或 reconcile。
6. 禁止混用不同 model/tokenizer fingerprint 的向量。
7. 禁止把 RRF、BM25 或 cosine 当作事实概率。
8. 禁止把检索通道故障解释为“没有记录”。
9. 禁止直接复制运行中的 WAL 数据库作为备份。
10. 未通过迁移、恢复、质量、完成率、内存和三平台门禁，不得宣称生产可用。

