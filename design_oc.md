# Kelsy 知识库升级方案：向量检索

## 目标

从纯关键词搜索升级为**向量语义检索 + 关键词混合搜索**，保持本机部署、MD 文件为源数据。

---

## 架构总览

```
用户提问
  │
  ▼
┌──────────────────────────────────────────────┐
│  KnowledgeStore.search() (改造)              │
│  ├─ 向量检索：EmbeddingService → SQLite      │
│  ├─ 关键词检索：现有 line-by-line scan (保留) │
│  └─ 混合排序：向量分数 + 关键词命中           │
├──────────────────────────────────────────────┤
│  KnowledgeIndex (新增)                        │
│  ├─ SQLite: ~/.kelly/kelsy/index.db          │
│  ├─ 启动时全量索引 + WatchService 增量更新     │
│  └─ chunk 存储 + embedding BLOB              │
├──────────────────────────────────────────────┤
│  EmbeddingService (新增)                      │
│  ├─ 复用现有 OpenAI 兼容 API                  │
│  ├─ 批量 embedding                            │
│  └─ 相同文本缓存（避免重复调用）               │
├──────────────────────────────────────────────┤
│  MD 文件 (不变，源数据)                        │
│  ~/.kelly/kelsy/workspace/knowledge/**/*.md   │
│  ~/.kelly/kelsy/workspace/memory/*.md         │
└──────────────────────────────────────────────┘
```

---

## 技术选型

| 组件 | 选择 | 理由 |
|------|------|------|
| 向量存储 | **SQLite** (`org.xerial:sqlite-jdbc`) | 嵌入式、零配置、单文件、Java 21 兼容 |
| Embedding | **复用现有 LLM API** (OpenAI 兼容) | 零额外部署，已有 MiniMax/Kimi/GLM/DeepSeek |
| 向量相似度 | **Java 侧计算** (cosine similarity) | 知识库规模小（<100K chunks），内存加载全量 embedding 即可 |
| 分块策略 | **按 Markdown 标题分块** | 保留文档结构，语义完整性好 |

---

## 数据模型

### SQLite Schema

```sql
-- 文档分块
CREATE TABLE chunks (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path   TEXT NOT NULL,          -- 相对路径，如 knowledge/meetings/2026-09-04.md
    heading     TEXT,                   -- 标题，如 "# 客户XX会议"
    content     TEXT NOT NULL,          -- 分块文本内容
    line_start  INTEGER,               -- 起始行号
    line_end    INTEGER,               -- 结束行号
    file_mtime  INTEGER NOT NULL       -- 文件修改时间戳（增量索引用）
);

-- Embedding 向量
CREATE TABLE embeddings (
    chunk_id    INTEGER PRIMARY KEY,
    embedding   BLOB NOT NULL,         -- float[] 序列化为字节
    FOREIGN KEY (chunk_id) REFERENCES chunks(id) ON DELETE CASCADE
);

-- 索引元数据
CREATE TABLE meta (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL
);
-- 存储：schema_version, last_full_index_time, embedding_model
```

### 分块策略

```
# 客户XX会议                          ← heading: "# 客户XX会议"
- 日期：2026-09-04
- 参会：张三、李四                     ← 一个 chunk = 一个 section
- 结论：先申请再发货

# 待办                                ← 新 heading = 新 chunk
- 截止：2026-09-10
```

规则：
- 每个 Markdown 标题 (`#`, `##`, `###`) 划分一个 chunk
- 无标题的文件整体为一个 chunk
- 单 chunk 上限 500 字符，超过则按段落拆分
- 单 chunk 下限 50 字符，过小则与相邻 chunk 合并

---

## 核心类设计

### 1. EmbeddingService

**路径:** `com.mordor.kelly.kelsy.service.EmbeddingService`

```java
public final class EmbeddingService {
    // 复用现有 ModelFactory 的 OpenAI 兼容客户端
    private final OpenAIEmbeddingModel client;
    private final Map<String, float[]> cache;  // 文本哈希 → embedding

    // 批量 embedding（API 通常支持 batch）
    public List<float[]> embed(List<String> texts);

    // 单条 embedding
    public float[] embed(String text);

    // 缓存 key = text 的 SHA-256
}
```

**Embedding API 调用：**
- 使用 OpenAI 兼容的 `/v1/embeddings` 端点
- 模型：从 config.json 读取，新增 `embeddingModel` 字段（默认 `text-embedding-3-small`）
- 维度：1536（OpenAI 默认）或根据提供商调整

### 2. KnowledgeIndex

**路径:** `com.mordor.kelly.kelsy.service.KnowledgeIndex`

```java
public final class KnowledgeIndex {
    private final Path dbPath;           // ~/.kelly/kelsy/index.db
    private final KnowledgeStore store;
    private final EmbeddingService embedder;

    // 启动时全量重建
    public void rebuild();

    // 增量更新（只处理修改过的文件）
    public void update();

    // 向量搜索
    public List<ScoredChunk> search(String query, int topK, FindQuery filter);

    // 相似度分数
    record ScoredChunk(String filePath, String heading, String snippet,
                       double score, int lineStart, int lineEnd) {}
}
```

**启动流程：**
1. 打开/创建 SQLite DB
2. 检查 `meta.last_full_index_time`
3. 扫描 `knowledge/**/*.md` + `memory/*.md`
4. 对比 `file_mtime`，只处理新增/修改的文件
5. 分块 → embedding → 写入 DB
6. 安装 `WatchService` 监听文件变更

### 3. 文件监听器

```java
// 在 KelsyRuntime 启动时安装
WatchService watcher = FileSystems.getDefault().newWatchService();
workspace.register(watcher, ENTRY_MODIFY, ENTRY_CREATE, ENTRY_DELETE);

// 后台线程：收到事件后 debounce 500ms，调用 knowledgeIndex.update()
```

---

## 搜索算法

### 混合检索流程

```
用户查询: "客户XX交付licence的结论"
  │
  ▼
1. EmbeddingService.embed(query) → queryVector
  │
  ▼
2. SQLite 加载全量 embeddings → 内存中 cosine similarity
   → List<ScoredChunk> vectorResults (top 20)
  │
  ▼
3. 现有 KnowledgeStore.scanFile() 关键词搜索
   → List<Hit> keywordResults (top 20)
  │
  ▼
4. 合并 + 去重 (按 filePath + lineStart)
   │
   ├─ 仅向量命中：score = vectorScore * 1.0
   ├─ 仅关键词命中：score = 0.3 (保底分)
   └─ 双重命中：score = vectorScore * 1.0 + 0.3 (加权)
  │
  ▼
5. 日期范围过滤 (FindQuery.from/to)
  │
  ▼
6. 按 score 降序排列，返回 top 50
```

### Cosine Similarity (Java 实现)

```java
static double cosine(float[] a, float[] b) {
    double dot = 0, normA = 0, normB = 0;
    for (int i = 0; i < a.length; i++) {
        dot += a[i] * b[i];
        normA += a[i] * a[i];
        normB += b[i] * b[i];
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}
```

---

## 改造点清单

### 新增文件

| 文件 | 说明 |
|------|------|
| `kelsy/service/EmbeddingService.java` | Embedding API 客户端 + 缓存 |
| `kelsy/service/KnowledgeIndex.java` | SQLite 索引管理 + 向量搜索 |
| `kelsy/service/Chunker.java` | Markdown 分块逻辑 |
| `kelsy/service/WatchService.java` | 文件变更监听 + debounce |

### 修改文件

| 文件 | 改动 |
|------|------|
| `KnowledgeStore.java` | `search()` 方法改为混合检索；新增 `vectorSearch()` |
| `KelsyRuntime.java` | 启动时调用 `KnowledgeIndex.rebuild()`；安装 WatchService |
| `KelsyConfig.java` | 新增 `embeddingModel`、`embeddingDimension` 字段 |
| `ConfigLoader.java` | 模板新增 embedding 配置项 |
| `LocalAssistantService.java` | 注册新工具（如 `vector_search`）或改造现有工具 |
| `SKILL.md` (模板) | 更新检索协议，优先使用向量搜索 |
| `LocalEvidence.java` | `terms()` 提取的关键词同时用于向量查询 |

### 依赖新增 (pom.xml)

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.46.1.3</version>
</dependency>
```

---

## SKILL.md 检索协议更新

**现有（10 步）：**
```
1. 提取关键词
2. 转换时间引用
3. memory_search
4. memory_get
5. read_file
```

**升级后：**
```
1. 提取搜索意图（自然语言）
2. 调用 memory_vector_search(query) → 返回 top-K 相关 chunks
3. 对高分 chunk 调用 read_file 获取完整内容
4. 若向量结果不足，回退到 memory_search（关键词）
5. 日期过滤作为后处理
```

---

## 配置变更

`~/.kelly/kelsy/config.json` 新增：

```json
{
  "model": { ... },
  "embedding": {
    "provider": "minimax",
    "modelName": "text-embedding-3-small",
    "dimension": 1536
  },
  "workspaceDir": "~/.kelly/kelsy/workspace"
}
```

---

## 迁移策略

1. **零迁移成本**：MD 文件保持不变，索引是额外层
2. **首次启动**：自动扫描所有 MD 文件，生成 chunks + embeddings，写入 `index.db`
3. **后续启动**：增量更新（只处理修改过的文件）
4. **回退方案**：删除 `index.db` 即可回退到纯关键词搜索

---

## 性能预估

| 指标 | 预估 |
|------|------|
| 知识库规模 | 数百个 MD 文件，数千个 chunks |
| Embedding 向量大小 | 1536 维 × 4 字节 = 6KB/向量 |
| 全量 embedding 内存 | ~60MB（10K chunks） |
| 启动索引时间 | 首次 ~30s（API 调用），增量 <1s |
| 单次搜索延迟 | <50ms（内存 cosine similarity） |

---

## 实施阶段

### Phase 1: 基础向量检索
- [ ] 新增 `sqlite-jdbc` 依赖
- [ ] 实现 `EmbeddingService`（API 调用 + 缓存）
- [ ] 实现 `Chunker`（MD 分块）
- [ ] 实现 `KnowledgeIndex`（SQLite 存储 + 全量索引）
- [ ] 改造 `KnowledgeStore.search()` 为混合检索
- [ ] 更新 `KelsyRuntime` 启动流程

### Phase 2: 增量更新
- [ ] 实现 WatchService 文件监听
- [ ] 增量索引逻辑（对比 mtime）
- [ ] Debounce + 后台线程

### Phase 3: LLM 集成
- [ ] 更新 SKILL.md 检索协议
- [ ] 新增/改造 AgentScope 工具
- [ ] CitationTurn 适配新工具名

### Phase 4: 优化
- [ ] Embedding 缓存持久化（避免重复 API 调用）
- [ ] 分块策略调优（标题级别、大小阈值）
- [ ] 搜索结果质量评估
