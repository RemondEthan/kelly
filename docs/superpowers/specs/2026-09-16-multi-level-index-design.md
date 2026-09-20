# 多级索引 + 必含补丁：对话召回的确定性 — Design

**Date:** 2026-09-16
**Status:** Draft (awaiting user review)
**Owner:** user
**Supersedes:** 部分覆盖 `2026-09-15-long-term-memory-design.md` §5.4（`AskGrounding` → 改造为 `IntentGrounding`）

---

## 导读：用一句话理解这套设计

**意图层先选路, 索引层做两件事(全文召回 + 必含补丁), Markdown 仍是唯一真相。**

痛点: 当知识库里实际有 4 个 todo 时, 模型经过多轮对话却给出 3 个——根因不是 FTS 不准, 而是 `AskGrounding` 把"全文 FTS top-5"当成唯一召回源, BM25 排序在第 5 名之后的卡永远不会进入上下文, 模型自然看不见。

新方案: **意图命中 todo 类型后, L2 不再依赖 BM25 排序决定召回, 而是强制把该类型下所有 OPEN 卡列入"必读清单"**——这与 FTS 候选并联送给模型, 模型想漏也漏不掉。

---

## 1. Background

Kelsy 现有的对话检索走 `AskGrounding.prepare(store, userText, today)`(`src/main/java/com/mordor/kelly/kelsy/service/AskGrounding.java`):

1. `LocalEvidence.terms(userText)` 抽关键词
2. `KnowledgeStore.search(FindQuery.parse(userText, today))` 走 FTS5 BM25
3. 取分数 ≥ 阈值的前 5 条作为 `attached`
4. `messageForModel()` 把这 5 条路径 + 摘要拼到用户原文前

问题在 step 3: 当用户的问句命中"知识库里实际有 4 个 todo 卡"时, BM25 会按相关性给 4 张 todo 排序, 如果某张卡的标题/别名相关性较低、或 body 与问句没有重叠, 它会落在第 5 名之后——模型根本看不到这张卡的 path, 自然不会 `read_file` 它, 回答就漏了。

`/find` 是同一引擎的手工入口, 走相同路径, 漏召回的根因相同。

**为什么必须解决**: todo 是高频场景; 漏 todo 等于用户的工作流断裂。这不是优化, 是正确性问题。

---

## 2. Goals

- 命中意图类型(todo / meeting / decision / person / project / diary)后, **该类型下所有 OPEN 卡必须出现在模型可见路径列表里**, 一张都不能漏。
- 召回路径集合由 SQL 决定, 不由模型生成, 不由模型筛选。
- 召回延迟仍 < 50ms(已索引热路径)。
- 现有知识库自动升级, 不需要导出/导入/重新归档。
- Markdown 仍是唯一真相; 新增派生数据可删可重建。

## 3. Non-Goals

- 不上 embedding / 向量库 / 跨语言同义词挖掘。
- 不在 L1 调 LLM 做意图识别(确定性 + 零成本)。
- 不改 `KnowledgeIndex` schema(沿用现有 `cards_meta` / `cards_fts` 两张表)。
- 不动 `TodoScanner` / `TodoReminderService` / `MemoryCompactor` / `IndexingAgentTool`。
- 不改会议/决定/待办/人物/项目卡片的 Markdown 字段约定。
- 不做"分页 todo 列表"的 UI(那是另一条产品线)。

---

## 4. 当前痛点的精确拆解

用具体例子走一遍现状:

```
知识库:
  knowledge/todos/2026-09-10-寄快递.md      (aliases: 寄, 快递)
  knowledge/todos/2026-09-12-回邮件.md       (aliases: 邮件, 回复)
  knowledge/todos/2026-09-15-整理周报.md     (aliases: 周报, 周报格式)
  knowledge/todos/2026-09-20-定会议室.md     (aliases: 会议室, 预约)

用户问: "我最近的 todo 是什么?"
```

`LocalEvidence.terms("我最近的 todo 是什么")` → 过滤停用词后剩下 `["todo"]`(长度 ≥ 2)。

`KnowledgeStore.search` 走 FTS5 BM25, 对 `cards_fts MATCH 'todo'` 排序:

- "寄快递" / "回邮件" / "整理周报" / "定会议室" 全部 body 里都有 "todo" 字段的痕迹吗? 不一定, body 通常是任务描述而非 "todo" 字样。
- 真正命中的可能是 `title` 字段(如果标题里有"待办")或 body 任意位置出现的"todo"。
- 4 张卡按 BM25 排序后, 第 5 名之后的卡永远不会出现在 `attached`。

`MAX_ATTACH = 5`, 但若第 5 名之后还有 OPEN 状态的卡, 这部分就漏了。

**根因不是 FTS 不准, 是召回源单一 + 排序截断**。修复办法不是调阈值, 是**让意图类型为 todo 时, 召回源绕过 BM25 排序, 直接走"全集 + OPEN 过滤"**。

---

## 5. Architecture: 三层索引

```
用户原话
   │
   ▼
┌──────────────────────────────────────────────────────────────────┐
│ L1 意图层   IntentRecognizer (Java, 零 LLM 调用)                 │
│  输入: 用户原文                                                   │
│  输出: record Intent(primaryType, confidence, anchors, recallMode)│
│  metric 形状固定: type ∈ 7 个枚举 + 锚词列表 + 召回模式            │
└──────────────────────────────────────────────────────────────────┘
   │
   ▼
┌──────────────────────────────────────────────────────────────────┐
│ L2 索引层   RecallPlanner                                         │
│  段 1 FTS:                                                        │
│    SELECT path, snippet, bm25 FROM cards_fts                      │
│     WHERE cards_fts MATCH ?  -- anchors + 别名展开                 │
│       AND path LIKE 'knowledge/<primaryType>/%'                   │
│     ORDER BY bm25 LIMIT 24                                        │
│  段 2 必含补丁 (仅当 recallMode = FORCE_COVER):                    │
│    SELECT path FROM cards_meta                                    │
│     WHERE type = ? AND (status = 'open' OR status IS NULL)        │
│     ORDER BY date ASC, path ASC LIMIT 50                          │
│  合并: hits = unique(ftsHits ∪ mustCoverPaths)                    │
│  metric 形状固定: record RecallPlan(...)                          │
└──────────────────────────────────────────────────────────────────┘
   │
   ▼
┌──────────────────────────────────────────────────────────────────┐
│ L3 真相层   MarkdownCardReader (现有 KnowledgeStore.read)         │
│  不读 MD 全文 (避免上下文爆炸), 只读 path + title + status + date │
│  message 形状:                                                    │
│    【意图】TODO (confidence=0.85, recall=FORCE_COVER)              │
│    【锚词】广西客户 licence license 许可证                          │
│    【必读 todo 全集 · 8 张】L1 命中, 必须全部 read_file             │
│    - knowledge/todos/A.md | status=open | due=2026-09-20          │
│    ...                                                            │
│    【FTS 命中 · 2 张】供参考                                       │
│    - knowledge/meetings/广西客户licence.md | snippet: ...          │
│    用户原话: ...                                                   │
└──────────────────────────────────────────────────────────────────┘
   │
   ▼  送入模型
```

### 5.1 L1 意图层: 三层证据, 零 LLM 调用

**为什么不用 LLM**: 你的红线是"确定性不漏"。LLM 同输入可能不同输出, 而且每轮 300–2000ms 延迟 + 调用成本, 在已有必含补丁兜底的前提下是冗余的。详见 §7 技术选型。

L1 的判定由三层证据共同决定:

#### 证据 1: 关键词命中 (词表, 7 类)

```java
private static final Map<CardType, List<String>> TRIGGERS = Map.of(
    TODO,      List.of("待办", "todo", "待办事项", "待办清单"),
    MEETING,   List.of("会议", "纪要", "meeting", "minutes"),
    DECISION,  List.of("决定", "结论", "decision"),
    DIARY,     List.of("日记", "今天", "昨天", "上周", "本月", "diary"),
    PERSON,    List.of("联系人", "人物", "person"),
    PROJECT,   List.of("项目", "进度", "project"),
    OTHER,     List.of()
);
```

中文 + 英文 + 常见别名都进表。每个 type 一个触发词集合, 命中数最多者为 primary。

#### 证据 2: 句式规则 (15 条正则, 可扩展)

```java
// 句式 → primary 强制 + anchors 提取
private static final List<Rule> SYNTACTIC = List.of(
    // "X 上有几个 Y" / "X 里有什么 Y" → primary = Y, X 进 anchors
    new Rule(Pattern.compile("(\\S+)\\s*(?:上|里|中)\\s*(?:有|的)?\\s*(?:几个|哪些|什么)\\s*(todo|待办|会议|决定|人|项目|日记)"),
             "X_CONTEXT_Y"),
    // "我的 todos" / "我的待办"
    new Rule(Pattern.compile("(我的|所有|全部)\\s*(todo|待办)"),
             "POSSESSIVE"),
    // "X 的 todos"
    new Rule(Pattern.compile("(\\S+)\\s*的\\s*(todo|待办|会议)"),
             "X_POSSESSIVE_Y"),
    // "@xxx 的 todo"
    new Rule(Pattern.compile("@(\\S+)\\s*的\\s*(\\S+)"),
             "AT_POSSESSIVE"),
    // "xxx 项目相关" / "xxx 项目下的 todo"
    new Rule(Pattern.compile("(\\S+)\\s*项目(\\S{0,8})?(相关|下的)?\\s*(todo|待办)?"),
             "PROJECT_CONTEXT"),
    // 后续 10 条覆盖 "找一下 / 看看 / 列一下 / 总结一下" 等动词尾巴
);
```

句式规则解决"广西客户 licence 会议上有几个 todo"这种关键词平局但语义明确的情况。规则可以增量扩展, 不需要重训。

#### 证据 3: type 统计 (1 条 SQL, 毫秒级)

```sql
SELECT type, COUNT(*)
FROM cards_meta
WHERE status = 'open' OR status IS NULL
GROUP BY type;
```

不做 FTS, 只数每个 type 的存活卡片数。用途:

1. **消歧**: 问句"今天怎么样"无触发词, 但库里只有 diary 有 30 张、其他 0 → primary = DIARY。
2. **confidence 校准**: 触发词命中 1 + 统计命中 ≥ 1 → confidence 提到 0.7。

#### 同会话上一轮的 type context (可选, 弱证据)

当本轮 confidence < 0.4 且存在上一轮 Intent 时, 把上一轮 primaryType 作为弱证据(权重 0.3):

```java
if (intent.confidence < 0.4 && lastIntent != null) {
    intent = new Intent(
        lastIntent.primaryType(),
        max(intent.confidence, 0.5),
        intent.anchors(),
        lastIntent.recallMode()
    );
}
```

**默认情况下不补** (用户确认过), 仅当本轮置信度过低时启用。

#### 用你的例子走一遍

原话: "我记得一个关于广西客户licence的会议上有几个todo事项, 帮我找一下"

1. **证据 1**: TODO 触发词命中 1(todo), MEETING 触发词命中 1(会议), 平局。
2. **证据 2**: 命中 `X_CONTEXT_Y` 规则, 提取 X="会议", Y="todo" → **强制 primary = TODO**。
3. **证据 3**: `SELECT type, count FROM cards_meta` → todo=8, meeting=42, 不改变判定(已锁定 TODO)。
4. **最终 Intent**:
   ```java
   Intent(
     primaryType = TODO,
     confidence = 0.85,
     anchors = ["广西客户", "licence", "许可证", "会议"],
     recallMode = FORCE_COVER
   )
   ```

### 5.2 L2 索引层: FTS 段 + 必含补丁段 并联

L2 收到 `Intent` 后:

#### 段 1: FTS (全文召回)

```sql
SELECT path, snippet(cards_fts, 3, '', '', '…', 20) AS snip, bm25(cards_fts) AS rank
FROM cards_fts
WHERE cards_fts MATCH ?
  AND path LIKE 'knowledge/<primaryType>/%'
ORDER BY rank
LIMIT 24;
```

- `?` 是 anchors + 别名展开后的 query (复用 `CjkNgrams.forQuery`)。
- path 前缀由 L1 决定 (例: `knowledge/todos/`)——**FTS 也走类型边界**, 不会跨类型召回 todo 卡之外的脏数据。
- top 24 留缓冲, 取分数最高的进 message, 剩余丢弃。

#### 段 2: 必含补丁 (仅当 recallMode = FORCE_COVER)

```sql
SELECT path
FROM cards_meta
WHERE type = ? AND (status = 'open' OR status IS NULL)
ORDER BY date ASC, path ASC
LIMIT 50;
```

- type 由 L1 决定; OPEN 过滤保证不召回已完成 todo。
- 排序: 截止日期升序 → 路径字典序 (稳定排序)。
- **硬上限 50**, 超出截断并标 `overflow=true`。

#### 合并

```java
hits = unique(ftsHits.stream().map(Hit::relativePath)
                    .collect(toSet())
                ∪ mustCoverPaths);
// mustCoverPaths 永远不被去重删掉
// ftsHits 只用来补充信息(摘要), 不用来筛掉必含项
```

**关键不变量**: 必含补丁的结果永远不会被 FTS 段"覆盖"或"挤出"。哪怕 FTS 没命中, 必含项仍然以 path + 元数据形式出现在 message 里。

### 5.3 L3 真相层: 拼 message, 不读全文

为避免上下文爆炸, L3 不调 `KnowledgeStore.read` 读 MD body, 只生成路径 + 元数据:

```
【意图】TODO (confidence=0.85, recall=FORCE_COVER)
【锚词】广西客户 licence license 许可证
【必读 todo 全集 · 8 张】L1 命中, 必须全部 read_file
- knowledge/todos/2026-09-10-寄快递.md | status=open | due=2026-09-10
- knowledge/todos/2026-09-12-回邮件.md | status=open | due=2026-09-12
- knowledge/todos/2026-09-15-整理周报.md | status=open | due=2026-09-15
- knowledge/todos/2026-09-20-定会议室.md | status=open | due=2026-09-20
- knowledge/todos/2026-09-22-约客户.md | status=open | due=2026-09-22
- knowledge/todos/2026-09-25-签约.md | status=open | due=2026-09-25
- knowledge/todos/2026-09-28-付款.md | status=open | due=2026-09-28
- knowledge/todos/2026-09-30-验收.md | status=open | due=2026-09-30
【FTS 命中 · 2 张】供参考
- knowledge/meetings/广西客户licence.md | snippet: ...交付是否需要 licence...
- knowledge/meetings/广西客户kickoff.md | snippet: ...会议纪要...
用户原话: 我记得一个关于广西客户licence的会议上有几个todo事项, 帮我找一下
```

模型看到这个 message, **8 张 todo 一张都不可能漏**, 因为路径列表在那里。SKILL.md 里强制要求模型必须 `read_file` `【必读 todo 全集】` 列出的每一张卡。

### 5.4 数据结构 (固定 metric)

```java
// L1
public enum CardType { TODO, MEETING, DECISION, PERSON, PROJECT, DIARY, OTHER }
public enum RecallMode { FORCE_COVER, FTS }

public record Intent(
    CardType primaryType,
    double confidence,            // 0..1
    List<String> anchors,         // 不可变
    RecallMode recallMode
) {}

// L2
public record RecallPlan(
    Intent intent,
    String ftsQuery,              // 用于 debug / 诊断表
    List<Hit> ftsHits,            // 来自 cards_fts BM25 top 24
    List<String> mustCoverPaths,  // 来自 cards_meta 强制全集
    boolean overflow              // true 表示 mustCoverPaths 被截断
) {}

// L3
public record GroundedMessage(
    String text,                  // 拼好给模型的字符串
    List<String> citationPaths,   // UI 高亮引用
    List<String> mustReadPaths    // 给 UI 展示"已强制读"
) {}
```

**为什么 shape 固定**: 所有下游 (UI、模型输入、诊断日志、测试 fixture) 都从同一组 record 解析; 以后改实现不会破坏调用方。

### 5.5 接入点改造

- `AskGrounding` (现有) → `IntentGrounding` (新), 接口增广不破契约:
  - 保留 `attached` / `citationPaths()` / `messageForModel()` (旧 UI 行为不变)。
  - 新增 `IntentGrounding.intent()` / `mustReadPaths()` (UI 可选择性展示"已强制读"标签)。
- `ChatController.startAsk` 替换内部调用: `IntentGrounding.prepare(...)` 替代 `AskGrounding.prepare(...)`。
- `KnowledgeSearchTool` 不变 (仍供模型补查)。
- `CitationTurn` 不变 (仍消费 path 列表)。

### 5.6 SKILL.md 增量

```markdown
## 意图层已读
本轮 system 消息里出现【意图】+【必读 ... 全集】时, 必须按列出的 path 全部 read_file,
不允许跳读、不允许只看前 N 条、不允许用会话印象替代卡片内容。

【FTS 命中】段是参考, 不强制全部读完, 但【必读 ... 全集】段必须读完。
```

不替换现有 SKILL 内容, 仅在末尾追加。

---

## 6. 失败模式 (每种都有兜底, 不允许"静默空结果")

| 失败 | 检测 | 兜底 | 用户可见 |
|---|---|---|---|
| L1 误判 type (confidence < 0.4) | `Intent.confidence < 0.4` | 回退原 `AskGrounding.prepare` (FTS top-5) | 无 (等价于现状) |
| L1 confidence 边界 (0.4 ≤ c < 0.5) | 同上 | recallMode 强制 FTS (不进 FORCE_COVER) | 无 |
| L2 FTS 段抛异常 | catch RuntimeException | 跳过 FTS 段, 仅走必含补丁段 | UI 提示「检索异常, 仅显示必读」 |
| L2 必含补丁抛异常 | catch RuntimeException | 走 FTS 段 + 警告「可能漏 todo」 | UI 提示「知识索引异常, 检索较慢」 |
| L2 双段都抛异常 | catch | 走现有 `KnowledgeStore.scanSearch` 线性扫描 | UI 警告 |
| 必含补丁超 50 张 | `mustCoverPaths.size() > 50` | 截断 + `overflow=true` | UI 警告「todo 过多, 仅显示前 50」 |
| `knowledge/todos/` 不存在 | `Files.isDirectory == false` | `mustCoverPaths = []` + confidence 降级 | 无 |
| 索引文件损坏 | 启动 reconcile 检测 | 删除并重建一次; 重建失败走线性扫描 | UI 警告 |
| `aliases` 列缺失 | `CardFields.aliases == ""` | FTS query 不展开别名, 命中只靠原词 | 无 (行为退化为旧版) |
| 同会话上一轮 Intent 不存在 | `lastIntent == null` | 不补 type context | 无 |

**关键不变量**: **任何兜底路径都不允许返回"空命中"冒充"没归档"**。要么返回召回, 要么返回错误提示, 不允许静默。

---

## 7. 技术选型: 为什么 L1 用规则, 不用 LLM

### 7.1 主流方案对比

| 方案 | 速度 | 成本 | 确定性 | 可调试 | 7 类适用 | md-only 适用 |
|---|---|---|---|---|---|---|
| 1. 小模型分类 (DistilBERT / fastText) | ✓✓ < 5ms | ✓✓ 0 | ✓✓ | △ | ✓✓ | ✓✓ |
| 2. LLM 分类 (GPT-4o-mini / Haiku) | ✗ 300-2000ms | ✗ $0.001-0.01/轮 | ✗ 同输入可能不同输出 | ✗ | ✓✓ | ✓ |
| 3. Embedding 相似度 (bge-small) | ✓ < 20ms | ✓✓ 0 | △ | ✓ | △ | ✓✓ |
| 4. 规则 + 小模型混合 | ✓✓ 0-5ms | ✓✓ 0 | ✓✓ | ✓✓ | ✓✓ | ✓✓ |
| 5. Prompt 分类 (AgentScope) | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ |

**主流且成熟**: 方案 1 (DistilBERT) 和方案 4 (规则 + 模型) 是工业界客服意图识别的标准做法。Apple Siri、阿里小蜜、Google Assistant 早期都用方案 4 的演进路径。

### 7.2 对你的项目: 为何现在选规则, 未来可选升级到方案 1

**约束清单** (从前面讨论推出来):

1. 确定性 > 覆盖率 (同输入必须同输出)
2. 成本 = 0 (单机本地、无云端 API)
3. 可调试 > 智能化 (漏了要能立刻知道是哪条规则漏了)
4. 意图类型只有 7 个 (todo / meeting / decision / person / project / diary / other)
5. md 是真相 (意图识别错不能改 md)

**纯 LLM 方案对你不合适**:
- 不确定性直接违反"确定性不漏"红线。
- 每轮 500ms+ 延迟肉眼可见。
- 必含补丁已经把"漏召回"解掉了, LLM 的理解能力是冗余的。

**纯小模型方案对你现在也不合适**:
- 7 个类 + 数据稀疏 + 标注冷启动成本不值。
- 必含补丁已经把召回痛点解掉, 模型精度在这里是冗余的。
- 万一漏判, 规则版本能立刻回滚, 模型版本要重新加载。

**规则 + type 统计 + 必含补丁是当前约束下的最优解**: 它属于工业界方案 4 的"零训练数据起步版"。等你跑 1–2 个月发现规则库膨胀到 50+ 条维护困难时, 再升级到方案 1 (DistilBERT 本地 ONNX 推理 < 5ms) 是顺理成章的事。

### 7.3 未来升级路径 (不在本规格范围)

```python
# 训练侧 (一次性, 离线)
from transformers import DistilBertTokenizer, DistilBertForSequenceClassification
model = DistilBertForSequenceClassification.from_pretrained(
    "distilbert-base-multilingual-cased",  # 支持中文
    num_labels=7
)
# 训练数据: {"text": "广西客户 licence 会议 todo", "label": 0}  // 0=TODO
# 1000 条样本训练 1 epoch 约 3 分钟 (CPU)

# 推理侧 (本地 ONNX, 模型 250MB)
# 7 类推理 < 5ms
```

**但现在不做**。先把规则层 + 必含补丁上线, 跑 1–2 个月看实际漏判率, 再决定。

---

## 8. 组件职责 (每个单元的「干什么 / 不干什么 / 依赖什么」)

### 8.1 `IntentRecognizer` (L1)

- **干什么**: 把用户原文解析成 `Intent`。
- **不干什么**: 不查 FTS、不读 MD、不调 LLM、不改文件。
- **依赖**: `LocalEvidence.terms()` (已有)、`RecallCue` 关键词集合 (新增, 写死)、`cards_meta` GROUP BY type 查询 (已有 schema)。
- **判定顺序** (短路):
  1. 句式规则 (15 条正则) → 命中即锁 primary, 跳出。
  2. 触发词命中数最高 → primary。
  3. 平局 → 看 type 统计, 选存活卡片数最多的 type。
  4. 都 0 → OTHER, recallMode=FTS, confidence=0.3。
- **可单测**: 纯函数 + TempDir fixture。

### 8.2 `RecallPlanner` (L2)

- **干什么**: 拿 `Intent` + 知识库索引, 产出 `RecallPlan`。
- **不干什么**: 不读 MD 全文、不调模型、不改索引、不调 LLM。
- **依赖**: `KnowledgeIndex` (已有)、`CjkNgrams` (已有)、`CardFields` (已有)。
- **可单测**: fixture 种 60 张 todo 卡测 overflow; 种 0 张 todo 测空集不报错。

### 8.3 `GroundedMessageBuilder` (拼装 L3 喂入模型的文本)

- **干什么**: 拿 `RecallPlan` + 用户原文, 产出最终 message 字符串。
- **不干什么**: 不查索引、不调模型、不写文件。
- **依赖**: 无 (纯字符串拼接)。
- **输出分段**:
  - 【意图】段
  - 【锚词】段
  - 【必读 ... 全集】段 (仅当 recallMode = FORCE_COVER)
  - 【FTS 命中】段
  - 用户原话段
- **可单测**: 构造 `RecallPlan` 验证 4-5 段文字存在、顺序、anchors 顺序、空集退化。

### 8.4 不动的部分 (明示)

- `KnowledgeIndex` schema 不变 (沿用 cards_meta / cards_fts)。
- `KnowledgeStore.search` / `cardsContaining` 不变 (FTS 主路径仍可用)。
- `MemoryCompactor` 不变。
- `TodoScanner` / `TodoReminderService` 不变 (仍为提醒时钟)。
- `IndexingAgentTool` 不变 (LLM 写 aliases 仍在归档阶段)。
- `WorkspaceSeeder` 不变。
- `CitationTurn` / `SlashCommands` 不变。

---

## 9. Testing (不靠 LLM, 纯 fixture)

### 9.1 单元

- `IntentRecognizerTest`: 7 类意图各 1 case + 1 case 寒暄 + 1 case 触发词平局靠句式规则决胜 + 1 case confidence 边界。
- `RecallPlannerTest`:
  - 60 张 todo → `overflow=true`, 截到 50。
  - 4 张 todo + 命中 2 张 meeting → `ftsHits` ∪ `mustCoverPaths` 去重正确。
  - 0 张 todo → `mustCoverPaths = []`, 不抛。
- `GroundedMessageBuilderTest`: 4-5 段文本顺序、anchors 顺序、空集退化。
- `IntentGroundingTest` (端到端): fixture 种人物/会议/todo 卡, 喂"广西客户 licence 会议 todo", 断言 4 张 todo 全在 `mustReadPaths`, 至少 1 张 meeting 在 `citationPaths`。

### 9.2 集成

- 沿用 `KnowledgeUpgradeTest` 风格: 用旧 layout 种 4 todo + 1 meeting, 验证 L1 命中 → L2 必含 4 张全在 → L3 拼出 5 段文本。
- 不打真实 LLM API; intent 判定纯 Java。

### 9.3 回归

- `AskGrounding` 现有用例不能挂 (接口增广不破契约)。
- `CitationTurnTest` / `SlashCommandsTest` 不动。
- `TodoReminderService` 仍用 `TodoScanner`, 不受影响。

---

## 10. Success criteria

- **正确性**: 知识库有 N 张 OPEN todo 卡时, 用户问"我的 todo" / "待办" / "todo 列表" / 句式含 todo 触发词, `mustReadPaths` 必须包含全部 N 张 (N ≤ 50)。
- **性能**: 已索引热路径下, `IntentGrounding.prepare` P95 < 50ms (含 L1 SQL group by + L2 FTS + 必含补丁)。
- **可调试**: 任意一次调用, `RecallPlan.ftsQuery` 与 `last_query_plan` 诊断表可复现同输入同输出。
- **零成本**: L1 不调 LLM; L2 不调 LLM; 仅 `IndexingAgentTool` 在归档阶段调 LLM (已有)。
- **自动升级**: 旧 layout 不需要手动迁移, 启动时 reconcile 即可。

---

## 11. 实现概要 (文件清单)

新增:

- `src/main/java/com/mordor/kelly/kelsy/service/IntentRecognizer.java`
- `src/main/java/com/mordor/kelly/kelsy/service/RecallPlanner.java`
- `src/main/java/com/mordor/kelly/kelsy/service/GroundedMessageBuilder.java`
- `src/main/java/com/mordor/kelly/kelsy/service/IntentGrounding.java` (替换 `AskGrounding`, 保留旧 API 兼容)
- `src/main/java/com/mordor/kelly/kelsy/service/RecallCue.java` (关键词 + 句式规则集中定义)
- `src/test/java/com/mordor/kelly/kelsy/service/IntentRecognizerTest.java`
- `src/test/java/com/mordor/kelly/kelsy/service/RecallPlannerTest.java`
- `src/test/java/com/mordor/kelly/kelsy/service/GroundedMessageBuilderTest.java`
- `src/test/java/com/mordor/kelly/kelsy/service/IntentGroundingTest.java`

修改:

- `src/main/java/com/mordor/kelly/ui/chat/ChatController.java` — `startAsk` 调用 `IntentGrounding.prepare` 替代 `AskGrounding.prepare`
- `src/main/resources/com/mordor/kelly/kelsy/workspace/skills/kelsy-knowledge/SKILL.md` — 末尾追加「意图层已读」段

不动:

- `KnowledgeIndex` / `KnowledgeStore` / `CardFields` / `CjkNgrams` / `MemoryCompactor`
- `TodoScanner` / `TodoReminderService` / `IndexingAgentTool` / `WorkspaceSeeder`
- `CitationTurn` / `SlashCommands` / `KnowledgeSearchTool`
- `KnowledgeUpgradeTest` / `KnowledgeStoreSearchTest` / `KnowledgeStoreTest`

---

## 12. Self-review

- 占位符扫描: §1 §2 §3 §4 §5 §6 §7 §8 §9 §10 §11 §12 全部无 TBD/TODO。
- 内部一致性: §5 三层架构 ↔ §8 组件职责 ↔ §11 文件清单 一致; §6 失败模式 ↔ §8.1/8.2 兜底行为 一致。
- 范围: 单规格可落地为一个实施计划 (writing-plans 阶段会拆 task)。
- 模糊性: "confidence < 0.4 兜底" 阈值明确写在 §5.1 与 §6; "硬上限 50" 明确写在 §5.2 与 §6; "句式规则 15 条" 在 §5.1 标注为初始值, 后续可增量扩展。
