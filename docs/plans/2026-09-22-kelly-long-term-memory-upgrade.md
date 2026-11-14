# 智能秘书长期记忆升级方案 — 设计文档

> 版本：v1.0
> 日期：2026-09-22
> 状态：待实施（未动代码）
> 原则：本地自闭环、不引入第三方组件

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [设计原则](#2-设计原则)
3. [核心架构](#3-核心架构)
4. [存储后端设计](#4-存储后端设计)
5. [检索路径设计](#5-检索路径设计)
6. [重名与版本处理](#6-重名与版本处理)
7. [复合主键 vs path 主键](#7-复合主键-vs-path-主键)
8. [模型分发与生命周期](#8-模型分发与生命周期)
9. [错误处理与 LLM 契约](#9-错误处理与-llm-契约)
10. [iCloud / 多设备同步风险](#10-icloud-多设备同步风险)
11. [性能预期](#11-性能预期)
12. [实施阶段](#12-实施阶段)
13. [M5 真实数据回放评测](#13-m5-真实数据回放评测)
14. [风险登记](#14-风险登记)

---

## 1. 背景与目标

### 1.1 问题陈述

kelly 秘书当前长期记忆依赖 Markdown 文件 + SQLite FTS5 索引。当用户数据增长（预计 1k+ 卡片），面临：

- **精确召回局限**：错别字（「宏业 / 红叶」）、口语化查询（「上次那个项目」）召回率下降
- **语义召回缺失**：纯 FTS 无法理解语义相似性
- **散落 .md 性能衰减**：read/write/索引同步随卡片数线性退化

### 1.2 目标

在不引入第三方组件（无云服务、无外部依赖）的前提下：

1. 提升长期记忆的**召回率**（精确 + 语义双路）
2. 提升**读写性能**（数据库内嵌存储）
3. 保持**人类友好**（AGENTS.md / MEMORY.md / memory/日记 / skills 仍为文本）
4. 保持**真相一致性**（数据库表 + 历史版本表 + 文件三向同步）

### 1.3 非目标

- 不替代 LLM 生成答案（仅作召回）
- 不引入云端 embedding API
- 不支持多用户协同编辑
- 不替代 Obsidian / git 等外部工具（AGENTS.md / memory/日记仍可被外部工具编辑）

---

## 2. 设计原则

| 原则 | 说明 |
|---|---|
| **本地自闭环** | 所有计算在用户机器完成，零网络依赖 |
| **真相单源可同步** | 数据库表是唯一真相源，文件层可选双向同步 |
| **结构化优先** | 数据有 schema、有类型、有约束 |
| **LLM 友好契约** | 错误信息、API 设计考虑 LLM 理解成本 |
| **可逆** | 每个新能力都可独立降级回退 |

---

## 3. 核心架构

```
┌─────────────────────────────────────────────────────────────┐
│                       kelly 秘书                              │
│  ┌───────────────────────────────────────────────────────┐ │
│  │ UI Layer (JavaFX)                                     │ │
│  │  ChatController / KnowledgePane / CitationTurn        │ │
│  └────────────────────┬──────────────────────────────────┘ │
│                       │ chat(text, handler)                 │
│  ┌────────────────────▼──────────────────────────────────┐ │
│  │ AssistantService (LocalAssistantService)              │ │
│  │  - HarnessAgent (AgentScope, LLM client)              │ │
│  │  - 工具: knowledge_search / read_file / write_file    │ │
│  └────────────────────┬──────────────────────────────────┘ │
│                       │                                      │
│  ┌────────────────────▼──────────────────────────────────┐ │
│  │ AskGrounding  ← 【本方案扩展点】                       │ │
│  │  - FTS 5 召回 (现有)                                  │ │
│  │  - 向量召回 (新)                                       │ │
│  │  - RRF 融合 (新)                                       │ │
│  │  - 注入 system prompt                                  │ │
│  └────────────────────┬──────────────────────────────────┘ │
│                       │                                      │
│  ┌────────────────────▼──────────────────────────────────┐ │
│  │ KnowledgeStore / KnowledgeIndex                        │ │
│  │  - 现有: FTS5 (cards_fts) + cards_meta                │ │
│  │  - 新增: cards_body / vec_documents / cards_revisions │ │
│  └────────────────────┬──────────────────────────────────┘ │
│                       │                                      │
│  ┌────────────────────▼──────────────────────────────────┐ │
│  │ Embedding Pipeline (新)                                │ │
│  │  - ModelRegistry (模型检测/分发)                       │ │
│  │  - OnnxBgeEmbedder (bge-small-zh-v1.5)                │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                       │
                       ▼
       ~/.kelly/kelsy/workspace/<user>/
       ├── AGENTS.md              ← 文本（保留）
       ├── MEMORY.md              ← 文本（保留）
       ├── memory/                ← 文本（保留）
       ├── skills/                ← 文本（保留）
       └── .kelly-index.db        ← 唯一真相（新增表）
           ├── cards_fts
           ├── cards_meta
           ├── cards_body         (新)
           ├── cards_revisions    (新)
           └── vec_documents      (新)

       ~/.kelly/kelsy/models/
       └── bge-small-zh-v1.5-quantized.onnx   (用户手动放置)
```

**关键边界**：
- 向量只用于**召回**（找哪些文档相关）
- 真实数据在 SQLite `cards_body` BLOB 中
- AGENTS.md / MEMORY.md / memory/日记 / skills 保持文本

---

## 4. 存储后端设计

### 4.1 存储取舍

**决策**：完全切 SQLite（用户确认），仅 AGENTS.md / MEMORY.md / memory/日记 / skills 保留为文本。

### 4.2 表结构（终稿）

```sql
-- 现有表：保留
CREATE TABLE cards_meta (
  path TEXT PRIMARY KEY,
  title TEXT,
  date TEXT,                      -- YYYY-MM-DD（卡片事件日期，非 mtime）
  kind TEXT,                      -- meeting | todo | decision | person | project | ...
  mtime INTEGER NOT NULL
);

CREATE VIRTUAL TABLE cards_fts USING fts5(
  path UNINDEXED,
  body,
  title,
  tokenize = 'unicode61 remove_diacritics 2'
);

-- 新增表：卡片正文（完整 Markdown BLOB）
CREATE TABLE cards_body (
  path       TEXT PRIMARY KEY,
  body       BLOB NOT NULL,       -- UTF-8 Markdown 完整内容
  mtime      INTEGER NOT NULL,
  size       INTEGER NOT NULL,
  owner      TEXT NOT NULL,        -- 'human' | 'llm' | 'system'
  source     TEXT,                 -- 'agent:write_file' | 'reconcile:file:...'
  prev_path  TEXT,                 -- rename/merge前的 path
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  -- 可选结构化字段（nullable，应用层从 path 自动 infer）
  category   TEXT,                 -- meetings/todos/people/...
  card_date  TEXT,                 -- YYYY-MM-DD
  person     TEXT,
  topic      TEXT,
  slug       TEXT
);

-- 新增表：版本历史（重命名 / 合并 / 覆盖追踪）
CREATE TABLE cards_revisions (
  rev_id     INTEGER PRIMARY KEY AUTOINCREMENT,
  path       TEXT NOT NULL, -- 当前 path
  prev_path  TEXT,                 -- rename/merge 前的 path
  body       BLOB NOT NULL,        -- 旧 body
  mtime      INTEGER NOT NULL,
  reason     TEXT NOT NULL,        -- 'rename' | 'merge' | 'overwrite'
  created_at INTEGER NOT NULL
);

-- 新增表：向量索引
CREATE TABLE vec_documents (
  path   TEXT PRIMARY KEY,
  vec    BLOB NOT NULL, -- float32[512]
  model  TEXT NOT NULL,            -- 'bge-small-zh-v1.5'
  mtime  INTEGER NOT NULL,
  FOREIGN KEY (path) REFERENCES cards_body(path) ON DELETE CASCADE
);

-- 索引
CREATE INDEX idx_body_category ON cards_body(category) WHERE category IS NOT NULL;
CREATE INDEX idx_body_card_date ON cards_body(card_date) WHERE card_date IS NOT NULL;
CREATE INDEX idx_body_person ON cards_body(person) WHERE person IS NOT NULL;
CREATE INDEX idx_body_owner ON cards_body(owner);
CREATE INDEX idx_body_mtime ON cards_body(mtime);
CREATE INDEX idx_revisions_path ON cards_revisions(path);
```

### 4.3 主键决策：path 单一主键（终稿）

**理由汇总**（vs 复合主键 `(person, topic, date)`）：

| 维度 | path 主键 ✅ | 复合主键 ❌ |
|---|---|---|
| 外部契约稳定 | 单一字符串 | 三字段元组 |
| LLM 调用 token | ~30 字节 | ~90+ 字节 |
| NULL 容忍 | 不存在此问题 | 30% 真实知识缺字段 |
| 跨表 JOIN | O(1) | 三字段比较 |
| rename | INSERT + DELETE | UPDATE 三字段 |
| 查询灵活性 | LIKE 任意子串 | 必须按结构 |

**结构化字段以可空列 + 索引形式存在于 cards_body**，应用层自动从 path infer：

```java
CardMeta inferMeta(String path) {
    // path = "knowledge/meetings/2026-09-21-zhangsan-hongye.md"
    // → category=meetings, date=2026-09-21, person=zhangsan, topic=hongye
    // 规则在 PathInferrer 中定义
}
```

### 4.4 存储空间估算

| 数据 | 单条大小 | 10k 卡片总大小 |
|---|---|---|
| cards_body BLOB | ~1-3 KB | ~20 MB |
| vec_documents | ~2 KB | ~20 MB |
| FTS5 索引 | ~30% 卡片 | ~3 MB |
| 元数据 | ~200 B | ~2 MB |
| 索引 | ~80 B/行 | ~3 MB |
| **总计** | | **~50 MB** |

10k 卡片约 50MB，**完全本地化**。

---

## 5. 检索路径设计

### 5.1 AskGrounding 是什么

LLM 回答用户问题前，先去本地知识库搜相关卡片，把命中路径注入 system prompt，让 LLM 据此回答——这就是"grounding（接地）"。本升级方案是 **AskGrounding 这一层的扩展**，其他层（UI / ChatController / HarnessAgent / 原始 .md 文件）都不动。

### 5.2 召回融合策略

- **路由**：KnowledgeStore.search(FindQuery) 不变 (FTS 路径)
- **新增**：KnowledgeStore.vectorSearch(queryEmbedding, limit)
- **融合**：ReciprocalRankFusion (RRF)，k=60
- **阈值**：score >= 0.20（默认）/ 0.05（recall 场景）
- **输出**：top-k = 8

```
RRF 公式:
  score(d) = Σ_i  1 / (k + rank_i(d))
  其中 k=60, rank_i(d) 是文档 d 在第 i 路召回中的排名（1-indexed）
```

### 5.3 双路召回实现

```java
public record AskGrounding(boolean searched,
                           List<KnowledgeStore.Hit> attached,
                           String original) {

    public static AskGrounding prepare(KnowledgeStore store,
                                       EmbeddingModel embedder,
                                       String userText,
                                       LocalDate today) {
        // 1. FTS 召回（现有逻辑）
        List<KnowledgeStore.Hit> ftsHits = store.search(
            FindQuery.parse(userText, today)
        ).stream()
         .filter(h -> !KnowledgeStore.isAskCandidateExcluded(h.relativePath()))
         .filter(h -> h.score() >= minScore)
         .limit(MAX_ATTACH * 2)   // 多召回一些给 RRF
         .toList();

        // 2. 向量召回（新增）
        float[] queryVec = embedder.embed(List.of(userText)).get(0);
        List<VectorHit> vecHits = store.vectorSearch(queryVec, MAX_ATTACH * 2);

        // 3. RRF 融合
        List<KnowledgeStore.Hit> fused = ReciprocalRankFusion.fuse(
            ftsHits, vecHits, MAX_ATTACH
        );

        return new AskGrounding(true, fused, userText);
    }
}
```

### 5.4 文档粒度：一文档一向量

**决策**：每张卡片（一个 .md 文件对应一条 cards_body 记录）生成一个 512 维向量。

**理由**：
- 实现简单
- 与现有 FTS 粒度对位
- 用户级别 10k 卡片以内性能可接受

**未来扩展**：超过 50k 卡片时考虑按 heading 切块。

### 5.5 VectorIndex SQLite 表 + 全量 cosine 计算

```java
public class VectorIndex {
    public void upsert(String path, float[] vec, String model);
    public List<VectorHit> search(float[] query, int limit);
    public void delete(String path);  // 级联删除
    public void reconcile(); // 与 cards_body 同步
}
```

**全量 cosine 搜索**（Java 端计算，< 5k 文档毫秒级）：

```java
public List<VectorHit> search(float[] query, int limit) {
    List<VectorHit> hits = new ArrayList<>();
    try (Statement s = conn.createStatement();
         ResultSet rs = s.executeQuery("SELECT path, vec FROM vec_documents")) {
        while (rs.next()) {
            String path = rs.getString("path");
            float[] docVec = decodeFloats(rs.getBytes("vec"));
            double score = cosineSimilarity(query, docVec);
            hits.add(new VectorHit(path, score));
        }
    }
    return hits.stream()
        .sorted(Comparator.comparingDouble(VectorHit::score).reversed())
        .limit(limit)
        .toList();
}
```

**性能边界**：1k 文档 < 30ms / query；5k 文档 < 150ms / query。**未来超 5k 切 HNSW（sqlite-vss）**。

---

## 6. 重名与版本处理

### 6.1 重名发生的本质

SQLite 中"重名" = `cards_body.path` 主键 UNIQUE 约束冲突。**只有 INSERT 时发生**，UPDATE/DELETE/SELECT 无重名。

### 6.2 多层防护

| 层 | 机制 | 触发时机 |
|---|---|---|
| **L1. 命名约束** | slug pattern 校验（meeting/todo 必含日期） | 写入前 |
| **L2. 30 天阈值** | 旧 < 30 天 + owner=llm → 自动 rename；否则报错 | 写入时 |
| **L3. 报错即指引** | 错误信息含 3 种解决方案 | 报错时 |
| **L4. 定期合并** | 重名组合并扫描（hook 预留，本期不实现） | 启动时 |

### 6.3 重名处理流程

```java
public sealed interface WriteResult {
    record Ok(String path) implements WriteResult {}
    record Renamed(String newPath, String requestedPath) implements WriteResult {}
    record Collision(String path, CardMeta existing) implements WriteResult {}
}

public WriteResult writeCard(String path, byte[] body, WriteOptions opts) {
    validatePath(path);  // L1 命名约束
    
    if (!store.exists(path)) {
        doInsert(path, body);  // 简单路径
        return new WriteResult.Ok(path);
    }
    
    // 重名：进入 L2
    CardMeta existing = store.getMeta(path);
    long ageMs = now() - existing.mtime();
    boolean isRecent = ageMs < THIRTY_DAYS_MS;
    boolean isLLM = "llm".equals(existing.owner());
    
    if (isRecent && isLLM) {
        // 自动 rename
        String newPath = generateSuffix(path);
        doInsert(newPath, body);
        recordRevision(newPath, path, body, "overwrite_silenced");
        return new WriteResult.Renamed(newPath, path);
    }
    
    // 强制要求 read+merge
    throw new PathCollisionException(path, existing, List.of(
        new Resolution("rename_to_new", 
            "write_file(\"%s_<timestamp>.md\", body)", path),
        new Resolution("read_then_merge",
            "read_file(\"%s\") → 生成 merged body → write_file(path, merged)", path),
        new Resolution("force_overwrite",
            "write_file(path, body, force=true) // 慎用，丢失旧版")
    ));
}
```

### 6.4 命名约束 (L1)

```java
private static final Pattern MEETING_PATTERN = 
    Pattern.compile("^knowledge/meetings/\\d{4}-\\d{2}-\\d{2}-.+\\.md$");
private static final Pattern TODO_PATTERN = 
    Pattern.compile("^knowledge/todos/\\d{4}-\\d{2}-\\d{2}-.+\\.md$");

public void validatePath(String path) {
    // 1. 必须以 knowledge/ 或 memory/ 开头
    // 2. 必须以 .md 结尾
    // 3. category 必须在 {people, projects, playbooks, inbox, meetings, decisions, todos}
    // 4. meetings/todos 必须含日期 prefix
    // 5. 不允许 .. 或绝对路径
    // 6. 长度 ≤ 256
}
```

### 6.5 Rename 操作的 SQL

```sql
BEGIN TRANSACTION;

-- 1. 旧记录复制到 revisions
INSERT INTO cards_revisions (path, prev_path, body, mtime, reason, created_at)
SELECT path, path, body, mtime, 'rename', :now
FROM cards_body WHERE path = :oldPath;

-- 2. 删除旧记录（级联删除 vec_documents, cards_fts, cards_meta）
DELETE FROM cards_body WHERE path = :oldPath;
-- ON DELETE CASCADE 触发 vec_documents 同步删除

-- 3. 插入新记录
INSERT INTO cards_body (path, body, mtime, size, owner, source, prev_path, created_at, updated_at)
VALUES (:newPath, :body, :mtime, :size, :owner, :source, :oldPath, :now, :now);

-- 4. reconcile触发：vec_documents / cards_fts 重建

COMMIT;
```

### 6.6 历史 API

```java
public List<Revision> history(String path);
public void restore(String path, long revId);   // 从某个 revision 恢复
public WriteOutcome merge(String primary, String secondary, byte[] mergedBody);
```

---

## 7. 复合主键 vs path 主键

**已确认**：使用 path 单一主键。详见 §4.3 决策表。

---

## 8. 模型分发与生命周期

### 8.1 模型选择

- **模型名**：bge-small-zh-v1.5
- **形式**：int8 量化 ONNX
- **大小**：~24 MB
- **维度**：512
- **CPU 推理延迟**：< 50ms / query

### 8.2 分发策略：方案 A（用户手动放置）

```bash
# 首次启动
~/.kelly/kelsy/models/bge-small-zh-v1.5-quantized.onnx   ← 用户手动放置

# 代码检测路径：
String modelPath = System.getProperty("user.home") + "/.kelly/kelsy/models/bge-small-zh-v1.5-quantized.onnx";
if (!Files.exists(Paths.get(modelPath))) {
    throw new MissingModelException(modelPath, 
        "请从 HuggingFace 下载 bge-small-zh-v1.5 int8 量化版到该路径");
}
```

**好处**：
- ✅ 项目仓库不打包（不 +24MB）
- ✅ 完全本地自闭环（无网络依赖）
- ✅ 用户可自由选 fp32 / fp16 / int8 量化

### 8.3 加载时序

```
启动时: lazy load (不阻塞启动)
  ↓
首次调用 embed():加载模型 (~2s 一次性)
  ↓
后台线程预热: 预加载常用查询的向量
```

### 8.4 组件清单

```java
public interface EmbeddingModel {
    List<float[]> embed(List<String> texts);  // 批处理
    int dimensions();                          // 512
    String modelName();                        // 'bge-small-zh-v1.5'
}

public final class OnnxBgeEmbedder implements EmbeddingModel { ... }
public final class ModelRegistry {
    public static Path defaultModelPath();
    public static void ensureModel() throws MissingModelException;
    public static EmbeddingModel loadDefault();
}
```

---

## 9. 错误处理与 LLM 契约

### 9.1 错误信息必须给 LLM 行动指引

**反例**（禁止）：

```
[错误] path collision: knowledge/people/张三.md
```

**正例**（推荐）：

```
[错误] write_file 失败：路径已存在
  path: knowledge/people/张三.md
  现有 mtime: 2024-06-01 (超过 30 天，视为重要历史)
  现有 byte 数: 1024

  你应当:
  方案 A: 改写为新 path（推荐）
          write_file("knowledge/people/张三_2026.md", content)
  方案 B: read_file 后 merge 到原 path
          read_file("knowledge/people/张三.md")  // 得到旧 body
          // 在 system prompt 里 merge 后调用 write_file_with_merge
  方案 C: 强制覆写（慎用，会丢失旧内容）
          write_file("knowledge/people/张三.md", content, force=true)
```

### 9.2 LLM 契约

| 场景 | 应用层行为 | LLM 期望 |
|---|---|---|
| 写入成功 | 立即返回 `Ok(path)` | 继续下一步 |
| 30 天内自动 rename | 返回 `Renamed(newPath, oldPath)` | 记下新 path，后续引用用新 path |
| 30 天以上碰撞 | 抛 `PathCollisionException` + 三个方案 | 按指引选择 |
| 模型未安装 | 抛 `MissingModelException` + 路径 | 报错给用户（不是 LLM 决策） |
| FTS 索引损坏 | 内部 fallback scan | 用户无感知 |

---

## 10. iCloud / 多设备同步风险

### 10.1 风险描述

iCloud 同步会复制 `.db` 但**不复制 `.db-wal` 和 `.db-shm`**，导致 SQLite 启动时报「file is not a database」。

### 10.2 缓解策略

**默认路径**： `~/.kelly/kelsy/` 不在 iCloud 范围内 → **默认安全**。

**用户自定义路径**：如果用户改 `workspaceDir` 到 iCloud 路径：

```
"警告：你将工作空间放在 iCloud 路径下。
 SQLite 数据库文件需要 .db + .db-wal + .db-shm 三个文件同步。
 iCloud 不会同步 .db-wal 和 .db-shm，可能导致数据损坏。
 建议：将 workspaceDir 放在 ~/.kelly/kelsy/ 等非 iCloud 路径。"
```

### 10.3 单设备场景

默认单设备使用，**风险等级低**。

---

## 11. 性能预期

| 操作 | 散落 .md | + SQLite body | + SQLite + 向量 |
|---|---|---|---|
| 单条 read | 1-3 ms | 0.05-0.2 ms | 0.05-0.2 ms |
| 单条 write | 1-3 ms | 1-5 ms（事务） | 1-5 ms + embed |
| FTS rebuild (10k) | ~500 ms | ~10 ms | ~10 ms |
| Vector reconcile (1k) | — | — | ~30 ms |
| AskGrounding (1k) | ~10 ms | ~10 ms | ~50 ms（FTS+vec+RRF） |
| AskGrounding (10k) | ~40 ms | ~40 ms | ~150 ms |

**关键收益**：
- read 路径快 **10-100×**
- reconcile 快 **10-50×**
- 召回路径增加 **5-50ms**（语义召回成本）

---

## 12. 实施阶段（M1-M5 全含）

| 阶段 | 内容 | 验证方式 |
|---|---|---|
| **M1** | `OnnxBgeEmbedder` + `EmbeddingModel` 接口 + 模型分发脚本 | 单元测试 `embed("测试") → 512 维向量` |
| **M2** | `VectorIndex` (SQLite 表 + cosine 全量) + `cards_body` / `cards_revisions` 表 + reconcile 扩展 | 单元测试 `index 100 文档 → top-k 正确性` |
| **M3** | `AskGrounding` 扩展双路 + RRF 融合 + path collision 错误处理 | 对比测试：「张三认证」应同时命中 FTS 和 vector |
| **M4** | 回归：现有 305 个测试 + 新增 ~30 个 | `mvn test` 全过 |
| **M5** | 真实数据回放：拿 `~/.kelly_bak` 的卡片跑检索质量评估 | 人工评估 top-10 命中率对比表 |

### 12.1 组件依赖顺序

```
M1 ──► M2 ──► M3 ──► M4 ──► M5
 │       │       │       │
 │       │       │       └─► 回归测试
 │       │       └─►召回融合
 │       └─► 向量索引 + 表结构
 └─► 嵌入推理层
```

### 12.2 新增文件清单

```
src/main/java/com/mordor/kelly/kelsy/
├── service/
│   ├── embedding/
│   │   ├── EmbeddingModel.java          (新, 接口)
│   │   ├── OnnxBgeEmbedder.java         (新, ONNX 实现)
│   │   ├── ModelRegistry.java           (新, 模型检测)
│   │   └── MissingModelException.java   (新, 异常)
│   ├── vector/
│   │   ├── VectorIndex.java             (新, SQLite vec 表 + cosine)
│   │   ├── VectorHit.java               (新, 命中记录)
│   │   └── ReciprocalRankFusion.java    (新, RRF 工具)
│   ├── AskGrounding.java                (改, 双路召回)
│   └── KnowledgeIndex.java              (改, reconcile 同步向量)
pom.xml                                    (改, 加 onnxruntime-java)

src/test/java/com/mordor/kelly/kelsy/
├── service/
│   ├── embedding/                        (新)
│   │   ├── OnnxBgeEmbedderTest.java
│   │   └── ModelRegistryTest.java
│   ├── vector/ (新)
│   │   ├── VectorIndexTest.java
│   │   └── ReciprocalRankFusionTest.java
│   └── AskGroundingTest.java            (改, 加双路测试)
└── e2e/
    └── RealDataRecallEvalTest.java      (新, M5 真实数据回放)
```

### 12.3 工作量估算

| 阶段 | 代码量 | 测试代码量 | 总计 |
|---|---|---|---|
| M1 | ~250 行 | ~150 行 | ~400 行 |
| M2 | ~400 行 | ~300 行 | ~700 行 |
| M3 | ~150 行 | ~200 行 | ~350 行 |
| M4 | ~50 行 | ~300 行 | ~350 行 |
| M5 | ~100 行 | ~200 行 | ~300 行 |
| **总计** | **~950 行** | **~1150 行** | **~2100 行** |

---

## 13. M5 真实数据回放评测

### 13.1 数据来源

`~/.kelly_bak/kelsy/workspace/ksw_del/knowledge/`（用户备份的卡片，含会议纪要、todos、people/meetings/decisions/todos）

### 13.2 评测方法

```
1. 准备 20 个 query：
   - 精确查询：「张三邮箱」
   - 错别字：「红叶 集成方案」（实际是「宏业」）
   - 语义查询：「上周聊过的项目」
   - 时序查询：「2026-09-21 的会议」

2. 每 query 标注期望命中的 path（ground truth）

3. 三路召回对比：
   - FTS-only (现有 baseline)
   - Vector-only
   - FTS + Vector (RRF) ← 升级方案

4. 计算指标：
   - top-1 / top-5 / top-10 命中率
   - MRR (Mean Reciprocal Rank)
```

### 13.3 通过标准

- ✅ FTS+Vector top-5 命中率 ≥ FTS-only（不倒退）
- ✅ FTS+Vector 在错别字、跨主题查询上明显优于 FTS-only（预期 +20% 命中率）
- ✅ FTS+Vector MRR ≥ FTS-only MRR

### 13.4 评测输出

| Query              | FTS top-5 | Vec top-5 | RRF top-5 | GT       |
|-------------------|-----------|-----------|-----------|----------|
| 张三邮箱           | ❌         | ✅         | ✅         | 张三.md  |
| 红叶集成方案       | ❌         | ✅         | ✅         | 宏业.md  |
| 上周项目           | ❌         | ✅         | ✅         | alpha.md |
| ...                | ...       | ...       | ...       | ...      |
| 总命中率           | 60%       | 75%       | 85%       | -        |

---

## 14. 风险登记

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| ONNX Runtime 跨平台加载（macOS arm64 / x64） | 中 | 高 | 引入 `onnxruntime-java` 含各平台 so/dylib |
| int8 量化损失中文召回 | 中 | 中 | 评估脚本里实测；不行就切 fp16 / fp32 |
| 全量 cosine 在 10k 文档变慢 | 低（用户级 1k-2k） | 中 | 逻辑层预留接口；M6 切 HNSW |
| 首次启动 ONNX 加载 2s 延迟 | 高 | 低 | 懒加载 + 后台预热线程 |
| 用户机器无 bge 模型 | 高 | 高（启动失败） | 明确报错信息 + 放置路径 |
| iCloud 同步 .db corruption | 中（默认路径安全） | 高 | 文档警告；默认路径非 iCloud |
| LLM 写卡片 path 拼写漂移 | 中 | 中 | 命名约束 + 应用层 infer |
| cards_revisions 无限增长 | 低 | 低 | 启动时定期清理 >90 天的 revisions |
| 30 天阈值误判 | 中 | 中 | 阈值可配置（KelsyConfig） |

---

## 附录 A：决策记录（汇总）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 嵌入推理位置 | 本地 CPU | 完全本地自闭环 |
| 存储模型 | FTS 5 + 向量索引并存 | 精确 + 语义互补 |
| 检索触发 | 每次 chat（AskGrounding 同步） | LLM 决策成本最低 |
| 文档粒度 | 一文档一向量 | 简单，与 FTS 粒度对位 |
| 嵌入模型 | bge-small-zh-v1.5 | 中文友好，~24MB，CPU 推理快 |
| 模型分发 | A：项目不打包，用户手动放 | 完全本地 |
| 向量召回 | 全量 cosine + 预留 HNSW 接口 | 1k-5k 文档内最优 |
| 范围 | M1-M5 全做 | 含真实数据回放 |
| 存储后端 | 完全切 SQLite（仅文本文件保留） | 性能 + 一致性 |
| 主键 | path 单一主键 | 外部契约稳定 + LLM token 低 |
| 结构化字段 | 可空列 + 应用层 infer | NULL 容忍 |
| 重名处理 | 30 天阈值 + owner 判定 + 错误即指引 | 平衡自动与人工 |

---

## 附录 B：开放问题（待最终确认）

1. **30 天阈值是否合理**？要不要 7 天 / 60 天 / 可配置？
2. **AGENTS.md / MEMORY.md / memory/日记 / skills 是否进入 cards_body**？当前决定是**不进**，保持文本。
3. **cards_revisions 是否真需要**？简化方案：只存最近 N 个版本。
4. **结构化字段由应用层 infer** vs **LLM 显式提供**？
5. **path 字符集**：UTF-8 任意字符（含中文 / emoji）允许吗？
6. **rename 操作**：完全禁止 / 允许但写 revision / 允许且不写 revision？

---

## 附录 C：未来扩展（M6+）

| 阶段 | 内容 |
|---|---|
| **M6** | HNSW 索引（sqlite-vss）替代全量 cosine |
| **M7** | 按 heading 切块（细粒度向量） |
| **M8** | 多用户协同（OPFS / CRDT） |
| **M9** | 跨设备 SQLite replication |

---

**文档状态**：终稿 v1.0
**红线遵守**：未改任何代码
**下一步**：等您一句"开始 M1"才动代码