# MCP 工具说明

术语库（glossary）相关 MCP 工具的中文参考。来源为各工具的 MCP 定义（name / description / parameters）。

## 1. get_term_data_context（术语数据上下文查询）

> 输入术语全限定名，一次性返回写 SQL 所需的表、关联和字段信息。

### 功能概述

输入一个或多个术语全限定名（FQN，逗号分隔），一次性返回这些术语所在语义模型的完整 SQL 生成上下文：

- **字段级** FQN：如 `GL-WBS-ZH-001.SingleProject.ProjectName`
- **表级** FQN：如 `GL-WBS-ZH-001.SingleProject`
- 两者可混用，级别自动识别

### 入参

| 参数        | 类型     | 必填  | 默认值 | 说明                                                                                                 |
| --------- | ------ | --- | --- | -------------------------------------------------------------------------------------------------- |
| `termFqn` | string | ✅   | —   | 一个或多个术语全限定名，逗号分隔，如 `GL-WBS-ZH-001.SingleProject,GL-WBS-ZH-001.SingleProject.ProjectName`；字段级与表级可混用 |

### 返回内容（每个命中的语义模型）

| 内容                      | 说明                                                                                                                                                                    |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 全部数据表（`tables`）         | `tables[].tags` 携带表级术语标记；`tables[].databaseName` 为表所在数据库，**可能为空**——写 FROM/JOIN 时用 `databaseName.name` 限定表名，为空则退化为裸表名                                                  |
| 全部表间关联（`relations`）     | `relations[].joinData` 含 `sourceField`/`targetField`（sourceTable/targetTable 的 join 字段）；`relationSemantic` 为关联的业务含义（可能缺失）；`relations[].type` 为 join 类型（如 `left join`） |
| 全部字段（`models[].fields`） | 命中查询术语的行标 `termTagged=true`                                                                                                                                           |

### 返回结构

`{termFqns, models, truncated, usage}`

### 截断保护

| 限制 | 值 |
|---|---|
| 模型数上限 | 10 |
| 每模型字段数上限 | 500 |

`truncated=true` 表示结果不完整，需改用 `search_metadata` 以 `queryFilter terms` 按 `tags.tagFqn` 继续查询。

### 行为注解（annotations）

与 context_search 相同：只读、无破坏性、幂等、封闭数据集。

## 2. context_search（术语库搜索）

> 在术语库里按关键词或语义搜术语，支持翻页，混合搜索融合两种得分。

### 功能概述

对 `glossary`（术语库）和 `glossaryTerm`（术语）两类实体做关键词、语义或混合检索，返回每条结果的完整实体详情及其与术语库的关系边。

- 混合模式（hybrid）用 **RRF（倒数排名融合）** 排序
- `keywordScore` 与 `similarityScore` 两个分数**分开保留**，不合并
- 最终返回条数不受候选窗口配置影响

### 入参

| 参数            | 类型                                     | 必填  | 默认值      | 说明                                   |
| ------------- | -------------------------------------- | --- | -------- | ------------------------------------ |
| `query`       | string                                 | ✅   | —        | 搜索短语或业务问题                            |
| `mode`        | enum：`keyword` / `semantic` / `hybrid` | ❌   | `hybrid` | 检索策略                                 |
| `entityTypes` | enum 数组：`glossary` / `glossaryTerm`    | ❌   | 两者都搜     | 要检索的实体类型                             |
| `size`        | integer（1–10）                          | ❌   | `5`      | 返回的富化结果条数，最大 10                      |
| `from`        | integer（≥0）                            | ❌   | —        | 从 0 开始的结果偏移量；通常直接用上次返回的 `nextCursor` |
| `nextCursor`  | string                                 | ❌   | —        | 上次响应返回的不透明游标；**优先级高于 `from`**        |

> 翻页时 `query`、`mode`、`entityTypes` 必须保持不变。

### 返回结果

| 字段/内容 | 说明 |
|---|---|
| 每条结果 | 含完整实体详情 + 该实体与术语库（glossary/term）的关系边 |
| `keywordScore` | 关键词匹配分（与语义分分开保留，不合并） |
| `similarityScore` | 语义相似度分 |
| `nextCursor` | 翻页游标（供下一页使用） |
| 统计信息 | 区分两种截断：**候选深度截断** vs **分页大小截断** |
| 全局总数 | `hybrid` 模式下**无精确总数**（仅近似估计） |

### 行为注解（annotations）

| 注解 | 值 | 含义 |
|---|---|---|
| `readOnlyHint` | true | 只读操作，不修改数据 |
| `destructiveHint` | false | 无破坏性 |
| `idempotentHint` | true | 幂等，重复调用结果一致 |
| `openWorldHint` | false | 封闭数据集，不依赖外部世界状态 |
