# Markdown 文章 + SQLite 索引与 TODO：技术设计

状态：2026-11-04 修订设计草案；替代本文件此前“正文全部入库”方案。按无历史数据的新存储方案实施，尚未实现。

实施附件：[接口与接入规格](sqlite-knowledge/implementation-spec.md)、[正式库完整 SQL](sqlite-knowledge/data-v1.sql)、[索引库完整 SQL](sqlite-knowledge/index-v1.sql)、[SQL 验证脚本](sqlite-knowledge/verify_schema.py)。

## 1. 数据归属与目录

每个 KnowledgeStore.knowledgeRoot(workspace, username) 独立存储：

```text
<userRoot>/
  knowledge/{articles,meetings,decisions,people,projects,playbooks,inbox}/**.md
  memory/YYYY-MM-DD.md
  MEMORY.md
  .kelly-data.db           # 正式数据：TODO、提醒、来源关联
  .kelly-index.db          # 派生数据：文章元数据、块、全文索引
```

文章正文、标题、别名、标签、业务日期以 Markdown 为准；索引里的副本不可独立编辑。
TODO 的标题、说明（Markdown 文本）、状态、截止日期、优先级均以 data.db 为准；
TODO 不再拥有可编辑的正式 md 文件。可以导出 Markdown，但导出不构成双向同步。
提醒记录属于 data.db。MEMORY.md 保持现有 4KB 工作集，日记保持文件模式并继续索引。
配置、技能与聊天历史不在本期改造范围。

分成两个数据库的目的在于明确恢复边界：index.db 可重建，data.db 必须备份。
两库不依赖跨库原子事务，也不建立跨库外键。应用层以稳定文档 UUID 关联。
如果将来需要统一搜索 TODO 与文章，分别查询后展示两组结果，不比较不同 FTS 库的原始 BM25。

## 2. 文章身份与元数据协议

新文章使用 YAML front matter，UTF-8 编码：

```markdown
---
id: "fdc699b1-cbfa-4e52-a641-66b029f3e631"
type: meeting
title: 许可证交付评审
aliases: [licence, 许可证]
tags: [交付, 产品A]
date: 2026-11-04
---
# 许可证交付评审
正文……
```

知识文章必须包含合法 UUID 和 type/title；aliases、tags、date 可选。
应用创建时生成 UUID，外部创建的文章也需遵守同一格式；缺少必填字段时报告格式错误，
不隐式回退到旧格式或路径身份。日记和 MEMORY.md 按各自固定文件协议索引，
不要求 front matter，也不作为 TODO 的文章来源。
日期必须区分业务日期与文件修改时间；查询“某天会议”使用 date。
仅恢复文章文件即可从 front matter 重建文档身份。
重复 UUID（例如复制文件未改 id）进入冲突状态，不随机覆盖；UI 提示给副本生成新 ID。

路径使用用户根内相对路径，数据库统一保存 '/'；读取时由 Path 解析。
校验真实路径在用户根内，拒绝符号链接逃逸。保留大小写，不统一转小写；
在 Windows/macOS 的实际文件系统上检测大小写冲突。规范化日期与换行的方式不得改变原文哈希。

## 3. 正式数据库 schema v1

每个连接配置 foreign_keys=ON、busy_timeout=5000；正式库 WAL + synchronous=FULL。
时间戳为 UTC 毫秒；due_date 为用户本地业务日期 YYYY-MM-DD，不做 UTC 午夜转换。
第一期仅支持日期级截止，不把日期和精确提醒时间混在一个字段。
通过 PRAGMA user_version 和顺序迁移脚本维护版本，升级前备份；正式库异常禁止删库重建。

完整 schema 见 [data-v1.sql](sqlite-knowledge/data-v1.sql)，包含 TODO、来源、审计、提醒批次/槽位、词项表、FTS 和全部同步触发器。

服务层额外验证日期、UUID、标题长度、说明大小、状态转换和版本号。
任务可以没有来源；有关联时以 document_uid 标识文章，路径仅作最后已知位置缓存。
来源文件删除不级联删除 TODO。来源定位失败保留任务，显示“来源不可用”。
source_hash 用于判断来源是否已变化，第一期不保存文章历史快照；
因此不能承诺文件修改后仍可查看当时版本，UI 必须明确提示来源已更新。
历史文章版本依赖用户 Git/备份，后续可独立引入文件版本快照。

TODO 关键词检索在 data.db 内增加 todo_search_content + todo_fts，
列为 title_terms、body_terms，以 todo.id 为 rowid，通过触发器同步。
两者均为派生表，重建时只清理这两张表，绝不删除整个 data.db。
正式写事务同时更新 todos、todo_events、todo_search_content（并触发 FTS 更新）。

## 4. 文章索引 schema v1

完整 schema 见 [index-v1.sql](sqlite-knowledge/index-v1.sql)，包含文档元数据、标签、原文块、词项表、FTS 和全部同步触发器。

索引库缓存 raw_text 只为摘要和分块检索，不成为正文的第二个编辑入口。
search_content 的 INSERT/UPDATE/DELETE 触发器维护 external-content FTS；
DELETE 必须携带旧词项执行 FTS delete 命令。创建触发器不会自动索引旧行，需显式 rebuild。
更换切词或分块算法版本需要重新解析文件和生成 search_content，再重建 FTS。
仅执行 FTS rebuild 不足以应用新的分块算法。

按 Markdown 标题和段落切块，目标 500–1000 Unicode code points，重叠约 100 字符；
代码块和表格尽量保持完整，超长块设置上限并记录切分边界。
保存标题层级、原文行号、内容哈希；文章引用使用 uid + hash + 行号；日记和工作集使用固定路径 + hash + 行号，
索引内部 chunk.id 不能作为永久引用。
短文通常一块。默认继续遵守现有文件大小限制，超限明确显示未完整索引，
后续引入有界流式解析，不能假装超大文件已全文可搜。

## 5. 索引同步与一致性

### 应用内文章写入

1. 验证路径和内容，检查调用者 expectedHash，防止覆盖已知外部编辑。
2. 同目录临时文件写入并关闭，尽可能原子替换目标文件；不支持时使用可恢复的替换流程。
3. 在索引事务中替换该文档元数据、标签、块、search_content。
4. 返回 file_saved/index_ready 两个状态；索引失败不回滚已经保存的正文，进入重试队列。

文件和 SQLite 没有共同事务。文件为真相，崩溃后通过对账恢复索引。
expectedHash 是乐观检查，不能阻止不合作外部编辑器在检查与替换之间写入；
应用内部串行写入并在写后复核，发生冲突保留恢复副本，不声称文件级严格 CAS。

### 外部编辑

WatchService 监听目录，事件按路径去重并 debounce 300–500ms；
原子保存产生的 delete/create 合并处理，变化后重新注册新增子目录。
读取前后核对文件属性，读取期间仍变化则延迟重试；哈希按原始 UTF-8 字节计算。
删除先标记 missing，短暂宽限后再清理索引；重命名按 front matter UUID 识别。
WatchService 只是加速，不能作为唯一正确性来源：

- 启动后台遍历一次；mtime+size 快筛，变化项比较 hash。
- 后台周期对账（初始 5 分钟，可配置）；事件溢出立即安排全量对账。
- 低优先级完整哈希巡检发现同大小、同时间戳修改，频率依据实测调节。
- 搜索前不 Files.walk，不等待全盘对账。返回 indexState/lastReconciledAt。

新建索引期间可用结果标记 building；空结果不能直接解释为“没有归档”。
命中后读取正式文件并检查哈希；变化时刷新并重取一次，仍变化则报告冲突。
过期块不作为最新事实送给模型。索引损坏后台重建，TODO 服务继续工作。
纯关键词搜索无法发现尚未索引的新内容，必须通过 freshness 状态向调用者说明。

## 6. 查询设计

“今日到期”：due_date=today；“逾期”：due_date<today；“本周”：本地周起止日期半开区间。
提醒仍遵守现有 TodoReminderPolicy 的 LEAD_DAYS 和逾期规则，先用回归测试锁定语义。
无日期任务单独列出。所有日期边界由注入的 Clock + ZoneId 在 Java 计算。

```sql
SELECT uid,title,due_date,priority,version
FROM todos
WHERE deleted_at IS NULL AND status='open' AND due_date < :today
ORDER BY due_date,id
LIMIT :limit;
```

列表用 (due_date,id) 游标分页，空 due_date 单独分页，不用深 OFFSET。
关键词任务查询：todo_fts MATCH + todos 状态/日期过滤，在过滤完成后排序、LIMIT。
文章查询：article_fts MATCH + documents/type/date/tag 过滤，
按 bm25(article_fts, 8.0, 5.0, 1.0) 升序排序，再合并同文档最佳块。
权重是初始调参值，不是质量保证；不要将 BM25 转成伪概率。
实现优先按数据库排序结果分批取候选并按文档去重，取够 K 篇才停止；
若达到处理预算，返回 truncated 标记，不能静默当作完整结果。
不先截取固定 top24 再做状态/日期过滤。过滤复杂时用 EXPLAIN QUERY PLAN 和基准实测。

复用 CjkNgrams 的思路，修正为按 Unicode code point 切分；中英文归一化规则版本化。
查询词组内中文二元词以 AND 组合，多个业务词按查询意图组合；
避免将全部二元词宽泛 OR 导致大量误召回。显式别名 licence/许可证共同入索引。
单字查询第一期要求补充信息，不声称支持任意中文单字全文搜索。
SQL 参数绑定之外，MATCH 表达式也必须由受控构造器转义和组装，禁止直接拼接用户语法。
摘要来自原始块，不能展示预分词文本作为原文。搜索结果返回内容哈希和索引状态。

## 7. TODO 更新与提醒事务

完成任务：UPDATE todos ... WHERE uid=? AND version=? AND deleted_at IS NULL；
同一事务写 completed_at、version+1、todo_events 和搜索内容。
更新行数为 0 返回版本冲突或不存在，不覆盖较新修改。
重开任务时清空 completed_at。删除使用 deleted_at，默认查询与提醒均排除。

保留 LOGIN/TEN/FOURTEEN 每日槽位及现有合并批次语义：

1. 单写线程短事务查询到期待办，生成 pending batch，唯一约束认领未占用槽位。
2. 提交后在 JavaFX 线程展示，成功后独立事务标记 delivered。
3. 重启重投 pending batch；展示前复核任务状态，已完成/删除或已改期超出窗口的任务不再展示；全部移除时标记 cancelled，槽位保持已消费。
4. 未到期或候选为空不消耗新槽位。payload 保存任务 uid 与生成时版本。

这是至少一次投递：展示后、提交 delivered 前崩溃可能再次展示；
稳定 batch uid 可让聊天 UI 去重，但不承诺跨数据库与界面的严格恰好一次。
时区设置沿用用户本地 ZoneId，槽位 day 为该时区业务日期；切换时区当天可能再次提醒，
第一期明确此行为，不以 UTC 日期替代本地日历规则。

## 8. Java 模块与工具接入

| 组件 | 职责与变更 |
|---|---|
| KnowledgeStore | 保留门面，委托 MarkdownDocumentStore + KnowledgeIndex；状态化 search/read |
| MarkdownDocumentStore（新增） | 路径约束、front matter、原子写、hash 冲突检查 |
| KnowledgeIndex | 索引 v1；取消搜索触发 reconcileIfStale 全盘扫描 |
| KnowledgeSyncService（新增） | WatchService、去抖、周期对账、重试与索引状态 |
| TodoRepository / TodoService（新增） | JDBC 持久化、版本冲突、业务查询、事务审计 |
| TodoReminderService | 用 TodoRepository 替代 TodoScanner，用数据库批次替代文件账本 |
| KnowledgePane / CitationTurn | 文章解析稳定 ID/路径/哈希；任务解析 todo uid |
| LocalAssistantService / SlashCommands | 注册受控工具，任务查询分流到结构化接口 |
| WorkspaceSeeder / MemoryCompactor | 新文章有 UUID；不再写 TODO 卡片，inbox 仍写 Markdown |

工具：knowledge_search/get/create/update；todo_create/list/get/update/complete/reopen/delete。
模型不直接执行 SQL，也不通过 write_file/edit_file 修改 TODO 状态。
文章工具使用文件仓储；外部编辑继续允许。memory_save 和日记保持原流程，写后通知索引。
read_file 仍可读取真实文章；任务从第一版起统一以 todo uid 引用，使用 todo_get 读取。
移除文件式 TODO 写入、扫描和提醒账本入口，不建立旧路径映射或双存储模式。

检索先判别任务意图、知识意图或混合意图；混合查询返回分组 evidence。
AskGrounding、knowledge_search、/find 共享文章搜索引擎；
“未完成任务”必须 todo_list，而不是靠模型从文章 FTS 摘要判断状态。
模型引用正文前执行 knowledge_get 验证哈希，任务引用前检查最新 version。
L0 只留偏好、当前项目和稳定指针，检索结果不不断追加回 MEMORY.md。

数据库阻塞操作不进 FX 线程。data.db 和 index.db 分别有串行写执行器，
读连接按任务借用，不跨线程同时共享 Connection；短事务、明确关闭和有限 busy 重试。
两库本地磁盘存放，不支持将正在打开的 WAL 数据库置于网络共享或实时同步目录。

## 9. 初始化、后续升级与备份

首次打开用户根时幂等创建目录及两库 schema v1，正式库建表与 user_version 更新
在事务中完成；任一步失败明确报错，重试初始化不得删除已创建的正式数据。
随后启动文章索引服务和 TODO 提醒服务。
不实现旧 Markdown TODO 导入、旧提醒账本导入、旧引用映射、旧模式切换或降级兼容。
数据库已有数据时按新 schema 处理，不能因本次无需历史迁移而在每次启动时清空。

后续发布仍需 PRAGMA user_version 和顺序 schema 升级脚本，升级正式库前备份。
遇到高于当前程序支持的正式库版本时拒绝写入，不自动降级。
索引算法或 schema 变化可重建 index.db；正式库损坏不得复用索引删库重建逻辑。

备份包包含 Markdown、MEMORY.md/日记及 data.db 一致性快照，index.db 可省略。
使用 Online Backup API 或 VACUUM INTO 导出正式库；在线不能只复制主 db 文件。
协调备份时暂停应用写入，复制文件前后核验 manifest 的 hash；
外部编辑造成变化需重试或报告本次快照不一致，不能声称文件与数据库天然原子备份。
恢复后重建 index.db 并检查 todo_sources；缺失文章不影响恢复 TODO。

## 10. 交付顺序与验收

P1：data.db、TODO 仓储、首次初始化和 reminder batches；接通任务工具和 UI。
P2：Markdown 身份、索引 v1、分块中文搜索、后台同步；接通文章与日记查询。
P3：稳定引用、备份/恢复/导出、跨平台验收和性能调优。

必要测试：事务回滚与乐观锁；TODO 状态/日期组合；每日槽位与崩溃重投；
初始化中断与重试、重复启动不丢数据、后续 schema 升级；FTS 更新删除与 rebuild；中文/英文/别名；
日期过滤后 LIMIT；文件原子保存、重命名、重复 UUID、删除、事件溢出及同 mtime 修改；
搜索陈旧状态、读取时 hash 变化；正式库错误不删库；用户隔离与路径逃逸；备份恢复。

性能目标而非承诺：固定硬件，10 万 TODO、1 万文章约 10 万块；
热任务列表 P95<30ms，文章 top10 P95<100ms（包含去重和摘要，不含首次建库），
记录冷查询、索引体积、峰值内存和同步延迟。常见外部修改目标 2 秒内可检索；
漏事件恢复时间受对账周期影响，需单独验收。Linux/macOS/Windows 都测监听降级、
中文路径、大小写、SQLite 驱动和备份恢复。

## 参考

- 现有实现：KnowledgeStore、KnowledgeIndex、CjkNgrams、TodoScanner、TodoReminderService、ReminderLedger。
- FTS5（external content、BM25）：https://www.sqlite.org/fts5.html
- WAL（并发及文件系统限制）：https://www.sqlite.org/wal.html
- Backup：https://www.sqlite.org/backup.html
