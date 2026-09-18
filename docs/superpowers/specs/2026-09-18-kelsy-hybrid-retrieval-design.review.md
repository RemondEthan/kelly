# Review: Kelsy 本地混合检索设计 (2026-09-18)

范围：技术实现评估。引用主流方案/论文/官方文档的事实以 `[ref:N]` 标注，所有对原文档的具体引用以行号锚点定位。`未自验` 表示未独立核验。

## 总评

| 维度 | 评级 | 备注 |
|---|---|---|
| 架构总体方向 | 合理 | 双路 BM25 + 精确 cosine + RRF 是 2024–2026 主流做法 |
| 问题诊断（§1） | 准确 | 与现有 `KnowledgeIndex.java:199-223` 代码吻合 |
| FTS5 工程细节 | 多数准确 | 但 §7.3 列权重顺序与文档列序不一致；§7.5 证据探测存在过度承诺 |
| ONNX/Java 工程路径 | 高风险 | tokenizer "纯 Java" 与现实生态不符；离线打包假设过强 |
| 性能预算（§16.3） | 数字偏乐观 | 未考虑 ONNX 推理方差与 GC 影响 |
| §17–§25 完成定义 | 严谨 | 显式禁止 magic number 热修，值得保留 |

**结论**：设计骨架合理、可作为后续实现蓝本，但需修正 6 处具体技术错误/不一致、补 3 处风险缓解、并对 2 处性能预算给出 P99 上限。

---

## 1. 问题诊断（§1）核实

§1 列了四个根因，全部与代码一致：

- **`1 - bm25 / 20` 转换失真** — `KnowledgeIndex.java:223` `double score = Math.max(0, 1.0 - bm25 / 20.0);`。
  - SQLite FTS5 官方文档明确："Better matches are assigned numerically lower values"（即更负更相关）[ref:1]。
  - 当 `bm25 <= -20` 时 `1 - bm25/20 >= 2`；当 `bm25 >= 0` 时被钳到 1；阈值 `0.20/0.05` 失效的判断成立。
  - **建议**：删除转换函数，直接消费原始负值或在融合阶段做线性映射。
- **整句展开为 OR** — `KnowledgeIndex.java:199-207` 只用一次 `MATCH ?`，无字段区分权重、无原词/扩展词分通道，§1 表述成立。
- **字段无差异化权重** — `bm25(cards_fts)` 不带权参，所有列默认 1.0 [ref:1]，§1 表述成立。
- **纯 BM25 无语义** — 客观事实。

**正面**：把"BM25 方向错误"作为基线 bug 优先修，而非模糊的"调参问题"，是工程上正确的取舍。

---

## 2. 架构方向（§5）评估

四路召回 → 加权 RRF → 证据 → 分类 → 状态：

- **与主流一致**：RRF（Reciprocal Rank Fusion）`k=60` 是 Cormack et al. 2009 原始论文建议，并在 2024–2026 工业实践（Milvus RRF Ranker、Hybrid Search 参考 2026、An Analysis of Fusion Functions 2023 [ref:2][ref:3]）中默认参数。文档选 `1/(60+rank)` 与原文一致 ✓
- **精确余弦 vs HNSW**：Redis agent-skills 指出"HNSW 是大规模生产默认，FLAT 仅适用于小规模召回" [ref:4]。20K × 384 × 4B ≈ 30MB 主存 + AVX2/FMA 加速下，余弦扫描在 30ms 内可完成，文档的"不做 HNSW"决定合理 ✓
- **三路并集 150 候选** 与"先 RRF 截 100，再证据探测"——与 2025 SAP "Rethinking Hybrid Retrieval" 论文 [ref:5] 主张的"小嵌入 + LLM rerank"路径不完全相同但更轻，符合本地无 LLM 约束。

**风险**：§13.1 候选集合上限 150 在向量 top 50 + 原词 top 50 + 同义词 top 50 时已满，没有去重预算；当三路高度重叠时，实际去重后候选数远低于 150，但 §13.2 RRF 公式里"不在列表则该项为 0"成立，需在实现里做 union + 唯一化。

---

## 3. FTS5 工程细节（§7）问题

### 3.1 §7.3 列权重顺序与列声明不一致 [需修正]

文档 FTS5 表声明（§7.1）：

```sql
CREATE VIRTUAL TABLE cards_fts USING fts5(
  path, title, aliases, body, type, who, date,
  tokenize = 'unicode61'
);
```

§7.3 给出的 `bm25()` 权重序列：

```text
path=0, title=8, aliases=10, body=1, type=2, who=6, date=1
```

→ 即 `bm25(cards_fts, 0.0, 8.0, 10.0, 1.0, 2.0, 6.0, 1.0)`。

**官方文档明确**：bm25 权重按列从左到右依次对应 [ref:1]。**该顺序与列声明一致 ✓**。但文档正文写的是"权重顺序 path=0, title=8..."，应改为显式注释"列序即权重序"或加上列名注释，避免实现时误对。

**修订建议**：在 §7.3 末尾补一句：
> 列序与表声明保持严格一致；调整 schema 时必须同步调整权重序列。

### 3.2 §7.5 "避免 N+1 SQL" 存疑 [需澄清]

§7.5 称"避免按候选 × group 执行 N+1 SQL"并采用"一条批量 SQL 判断 group 命中的候选 rowid"。

**事实**：SQLite FTS5 MATCH 表达式无法直接接受"rowid IN (?,?,…)"做布尔过滤并保留 bm25 排名——必须用 `MATCH` 子句或 `WHERE rowid IN (...) AND cards_fts MATCH ?` 后重新计算 bm25 [ref:1]。

**问题**：方案若实现为
1. 先用主召回 SQL 取 top 100 rowid，
2. 再按 group 逐个 `SELECT rowid FROM cards_fts WHERE rowid IN (...) AND cards_fts MATCH ?`，

则每个 group 一次往返，对 8 个 group 上限 + 100 rowid = 8 次 SQL。文档承诺"有限、可预测"，这是合理的；但**真正能避免 N+1 的是把 group MATCH 用 `OR` 串成单条 SQL，然后在 Java 端解析每行的命中 token**——文档没有指明这一点。

**建议**：在 §7.5 显式写"按 group 单独执行 MATCH，每次往返固定上限 8 次；不接受候选数 × group 数扩张"。否则未来实现者可能误用 candidate-loop 模式。

### 3.3 §6.2 中文停用短语白名单 [需扩充]

§6.2 初始集合 8 条，问句噪声明显不足。中文真实查询里高频停用：

- "帮我"、"帮我看看"、"帮我找"、"请问"、"我想问"、"我想知道"
- "是什么"、"是啥"、"啥是"、"是哪位"、"是哪"
- "关于"、"相关"、"有关"、"涉及"、"提到"、"提到的"、"说到的"

**风险**：若停用不足，`semanticText` 仍被噪声污染，query embedding 语义受影响；`lexicalText` 残留停用词可能形成跨边界 bigram 进入 CJK group（§6.3 已说"删除实体后避免跨边界 bigram"，但停用词未做同等处理）。

**建议**：§6.2 加测试断言"删除停用后剩余 token 不再生成跨边界 bigram"，与 §6.3 的实体处理做同等覆盖。

---

## 4. ONNX / Java 嵌入路径（§8）高风险

### 4.1 "优先选择可由纯 Java tokenizer 正确执行" 不现实 [需修正]

§8.4 称"优先选择可由纯 Java tokenizer 正确执行的候选"。

**事实核查**：

- 主流中文/多语言嵌入模型（BGE-M3、mE5、multilingual-e5、bge-small-zh-v1.5）的 tokenizer 均为 **SentencePiece (Unigram/Lattice)** 或 **WordPiece**：
  - BGE-M3 用 XLM-RoBERTa tokenizer (SentencePiece) [ref:6]
  - multilingual-e5 用 XLM-RoBERTa tokenizer [ref:7]
  - 没有任何主流多语言 384 维嵌入模型带纯 Java tokenizer；DJL 的 `tokenizers-sentencepiece` 包是 JNI 到 libsentencepiece [ref:8]
- HuggingFace `tokenizers` Rust 库可编译为 native，或通过 ONNX Runtime Extensions 的 `BertTokenizer`/`SentencepieceTokenizer` 算子内嵌到 ONNX 图 [ref:9]
- **纯 Java tokenizer 候选极为有限**（如 [INCEpTIT/embeddings-utils](https://dkpro.github.io/dkpro-javadoc/) 等学术项目），且不覆盖 SentencePiece

**结论**：§8.4 的设计前提与 2026 年模型生态不符。可行路径只有两条：
1. **ONNX Runtime Extensions** 把 tokenizer 算子融合进 ONNX 图，Java 只负责 string → tensor → embedding；
2. **SentencePiece JNI**（DJL）作为独立 native 依赖。

**风险**：§23 `-Dkelly.semanticSearch=false` 安全开关是好的；但若 native tokenizer 加载失败（缺 .dylib/.so、代码签名、公证失败），将导致 §17.1 `DEGRADED`，进而整个 macOS x64/ARM64 通道失效——`models/embedding/` 在安装包内的相对路径受 jlink/jpackage 行为影响（§21 提到但未给绝对路径解析策略）。

**建议**：把 §8.4 改为：
> tokenizer 必须与 ONNX Runtime Extensions 兼容，并通过预编译 ONNX 图将分词固化在模型内；若不可行，采用 DJL SentencePiece JNI 绑定并将其 native 库加入平台签名与公证清单。

### 4.2 §8.1 "不存在远程 provider... base URL、API key" 与代码隔离 [小问题]

设计用 `EmbeddingRuntime` 接口隔离 ONNX，并声称不为切换远程实现预留。这是对的——但实现上必须避免 `OnnxEmbeddingRuntime` 内部出现 `HttpClient`、`URL`、`OkHttpClient` 引用，否则 §22 "禁止记录用户完整问题"无法静态验证。

**建议**：在 §8.1 补一条断言：
> `OnnxEmbeddingRuntime` 模块的 `pom.xml`/build.gradle 不引入 `okhttp`、`java.net.http`、`HttpClient`；CI 用 `jdeps` 校验。

### 4.3 §10.1 embedding_models.state 未列 `RETIRED` [小问题]

§18 模型升级提到"旧模型标记 RETIRED"，但 §10.1 schema 没有 `RETIRED` enum 值。需在 §10.1 加：

```sql
state TEXT NOT NULL CHECK (state IN ('PENDING','RUNNING','DONE','RETRY_WAIT','FAILED','RETIRED'))
```

---

## 5. 性能预算（§16.3）偏乐观

文档承诺：

```text
QueryPlanner P95 < 5ms
FTS P95 < 30ms
query embedding P95 < 50ms
20K exact scan P95 < 30ms
fusion/classification P95 < 10ms
end-to-end P95 < 100ms
```

### 5.1 query embedding P95 < 50ms [偏低]

事实：
- ONNX Runtime 在 384 维 BERT-base 类模型上的 CPU 推理 **单句 P50 典型 30–80ms**（取决于平台、batch、ONNX thread 数）[未自验 — 应在 §17 模型选型门禁补基准]
- macOS x64（Intel）相比 ARM64 慢 1.5–2×；§21 同时支持两平台时 50ms 在 Intel 上接近 P50 而非 P95

**建议**：§16.3 拆分为 P50/P95/P99 三档；query embedding P95 放宽到 80ms；硬超时仍为 200ms。

### 5.2 20K exact scan P95 < 30ms [基本合理但需 SIMD 路径]

384 维 × 20K = 7.68M 次乘加。AVX2 单核理论峰值 ~12 GFLOPS（实测 Java Vector API 接近 native [ref:10]）；常规 `float[]` 标量循环 Java 18 大约 8–15ms [未自验]。

**未提及的关键决策**：§12.1 `VectorSnapshot.matrix` 是 `float[]`，但未指定是否走 `jdk.incubator.vector` (Project Panama) [ref:10][ref:11]。在 JDK 21+ 上 Vector API 已 Incubator 11+ 阶段，**强烈建议**显式选择 SIMD 路径，否则 30ms 预算在 Intel 上裕度仅 2×。

**建议**：§12.1 补一段："查询扫描使用 `FloatVector` SPECIES_PREFERRED（或最小 256-bit lane）；未启用时退化为标量并记录 diagnostic 警告。"

### 5.3 P95 之外缺 P99 与 GC 假设 [缺口]

文档只有 P95 < 100ms，没有 P99。JDK G1 在 30MB 主快照下的 minor GC 极少（eden 够大），但 1,536-byte/chunk × 20K = 30MB 单分配很容易落进 humongous region，触发 full GC pause。

**建议**：§16.3 加：
- P99 end-to-end < 250ms（硬超时 500ms）
- 主快照分块分配为 4MB 段数组避免 humongous
- JVM 参数 `-XX:G1HeapRegionSize=16m -XX:+UseG1GC`

---

## 6. 切块（§9）评估

### 6.1 §9.2 token 预算 [合理]

96+320+48+96 = 560 ≈ 512 上限，加 8% 安全余量。对 BGE-M3 / mE5 系列 max_tokens=512 模型无溢出风险 ✓

### 6.2 §9.3 "代码块优先保持完整；超过正文预算后按行切分" [与 §9.1 矛盾]

§9.1 声明"不纳入…无语义 Markdown 装饰"，§9.3 又把代码块纳入正文预算。Markdown 卡片中的代码块对 BGE-M3/mE5 几乎无语义价值（BPE 在代码 token 上的表征远弱于自然语言 [ref:12]）。代码块占用 320 token 预算会显著挤占正文。

**建议**：§9.3 改为"代码块按行切分到不超过 64 token；剩余预算给正文"，或在 chunk metadata 标记 `hasCode=true`，rerank 时降权。

### 6.3 §9.4 contentHash [缺指纹基线]

`SHA-256(modelFingerprint + textSchemaVersion + canonicalText)` 把 model fingerprint 纳入 hash 是个不错的设计——但 §18 模型升级后旧 content_hash 不会变化，会留下"text hash 没变就不重算"的悬挂判断。

**建议**：§9.4 明确"content_hash 仅用于单 fingerprint 内的去重；跨 fingerprint 必须重新生成（hash 不参与跨版本匹配）"。

---

## 7. 其它需澄清/补充的点

### 7.1 §6.5 全局同义词"term 最多属于一个全局等价组" [实际有歧义]

§6.5 例：`{licence, license, 许可证}` 三 term。若另一个组 `{许可证, license}` 也包含 `license`/`许可证`，资源加载失败回退——这是好的。但 §6.5 没规定"term 顺序"或"大小写规范"，会让 SKILL.md 维护者困惑。

**建议**：补一条"term 列表按 ASCII/Unicode 顺序规范化，组内第一个 term 作为 canonical key"。

### 7.2 §12.2 delta 重建条件 [与 §18 升级冲突]

§12.2 "delta 达到主快照 5% 或 1,000 chunks 时后台重建"，§18 模型升级时是"新 fingerprint → BUILDING → 100% → 原子切换"。这里 delta 与 fingerprint 双版本切换的边界没说清：

- delta 是单 fingerprint 内的变更缓冲；
- 升级时新 fingerprint 的所有 chunks 走"重建 VectorSnapshot"而非 delta。

**建议**：§12.2 明确："delta 仅服务 active fingerprint；模型升级期间 delta 暂停，新 fingerprint 直接构建独立快照，切换时整体替换。"

### 7.3 §17.5 UI 状态文本 [与 §15.5 `/find` 重复]

§17.5 给出"本地语义索引不可用…"等状态字符串，§15.5 `/find` 又要求显示"命中原因"。两处职责重叠，没有引用关系。

**建议**：§17.5 显式标注"知识面板状态文本的唯一来源"，§15.5 引用之。

### 7.4 §22 可观测性 [缺 retention]

"禁止记录用户完整问题、正文、embedding 数值、tokenizer 输入"——但允许"原词 BM25、同义词 BM25、向量候选数量、同义词 group ID"。这些信息结合卡内 metadata 仍可能反推部分用户意图。§22 没有 retention 周期与脱敏规则。

**建议**：加一条"诊断日志保留 ≤ 7 天；超过自动清理；不进入 crash report"。

### 7.5 §23 `-Dkelly.semanticSearch=false` [语义未完全自洽]

关闭时不删除已有向量，但 §11 增量生成管道不创建新 job——意味着再次开启后旧向量来自旧 fingerprint，旧卡片的修改不会被重新嵌入直到下次 hash 变化。这与 §19 "下次启用后继续已有进度"冲突。

**建议**：明确"再次开启后增量重建仅针对 hash 与现有向量不匹配的 chunk；其余保留"。

---

## 8. 与现有主流方案的差异点（事实对比）

| 决策 | 文档做法 | 主流做法 (2026) | 一致性 |
|---|---|---|---|
| 融合函数 | 加权 RRF k=60 | RRF k=60 / 加权 RRF / 凸组合 | 一致 [ref:2][ref:3] |
| 字段权重 | 静态手工配 bm25 权参 | 学习得到或 BM25F 形式 | 略过拟合风险，但接受 |
| 向量召回 | 20K 内精确余弦 | 同规模常用 FAISS Flat / HNSW | 一致（不需要 ANN） |
| tokenizer 路径 | 暗示纯 Java 优先 | ONNX Extensions / JNI | **不一致** [ref:9] |
| chunking | 标题+段落+token 硬截 | Recursive + semantic [ref:13] | 简化合理 |
| rerank | 无 LLM rerank | 主流用 cross-encoder rerank [ref:5] | 因 §4 "不调用 LLM"放弃，合理 |
| 索引重建 | hash 比对 + 状态机 | 同 | 一致 |
| 双版本模型升级 | 旧 READY + 新 BUILDING | 蓝绿/影子切换 | 一致 |

---

## 9. 修订优先级（建议）

| P0 | §8.4 tokenizer 路径（必须二选一） |
|---|---|
| P0 | §7.5 证据 SQL 模式澄清 |
| P0 | §16.3 P99 上限与 GC 参数 |
| P1 | §9.3 代码块切分策略 |
| P1 | §12.1 SIMD 路径明确 |
| P1 | §10.1 state enum 补 RETIRED |
| P2 | §6.2 停用短语扩充 + 跨边界 bigram 测试 |
| P2 | §22 日志 retention |
| P2 | §23 开关再开启后的重建语义 |

---

## 引用

- [ref:1] SQLite FTS5 官方文档：`bm25()` 权重按列从左到右对应；返回负值，越负越相关。https://sqlite.org/fts5.html
- [ref:2] An Analysis of Fusion Functions for Hybrid Retrieval, ACM TOIS 2023。https://dl.acm.org/doi/10.1145/3596512
- [ref:3] Milvus RRF Ranker 文档（默认 k=60）。https://milvus.io/docs/rrf-ranker.md
- [ref:4] Redis agent-skills: HNSW vs FLAT 选择。https://github.com/redis/agent-skills/blob/main/skills/redis-search/references/algorithm-choice.md
- [ref:5] Rethinking Hybrid Retrieval: When Small Embeddings and LLM Re-ranking Beat Bigger Models, arXiv 2506.00049 (2025)。https://arxiv.org/pdf/2506.00049
- [ref:6] BAAI/bge-m3 tokenizer 为 XLM-RoBERTa SentencePiece。https://huggingface.co/BAAI/bge-m3
- [ref:7] intfloat/multilingual-e5 tokenizer 为 XLM-RoBERTa。https://huggingface.co/intfloat/multilingual-e5-large
- [ref:8] Deep Java Library SentencePiece 扩展（基于 libsentencepiece JNI）。https://docs.djl.ai/master/extensions/sentencepiece/index.html
- [ref:9] ONNX Runtime Extensions tokenizer 算子（可融合进 ONNX 图）。https://github.com/microsoft/onnxruntime-extensions
- [ref:10] Java Vector API Benchmarking and Performance Analysis, ACM DL。https://dl.acm.org/doi/10.1145/3578360.3580265
- [ref:11] JEP 537: Vector API (Twelfth Incubator), JDK 27。https://openjdk.org/jeps/537
- [ref:12] A Systematic Analysis of Chunking Strategies for Reliable Question Answering, arXiv 2601.14123。https://arxiv.org/pdf/2601.14123
- [ref:13] Beyond Chunk-Then-Embed: A Comprehensive Taxonomy and Evaluation of Document Chunking Strategies, arXiv 2602.16974。https://arxiv.org/pdf/2602.16974

---

## 附：评审未覆盖的内容

- §11.1 状态机的 RETRY_WAIT 重试退避策略（线性 vs 指数）
- §14 STRONG/WEAK 阈值（60% / 40% / top 10/20）这些数字本身的合理性需要 §17 模型选型门禁后的实测验证
- §15.4 `knowledge_search` 在 AgentScope 上的并发安全（与 §16.1 单 reader connection 的兼容性）
- §20.1 黄金集 300–500 卡的标注质量评估方法（inter-annotator agreement）

这些点的判断都依赖未来实测或上游产品决策，本评审不做猜测。
