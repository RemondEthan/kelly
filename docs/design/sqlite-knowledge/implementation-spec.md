# 实施规格：接口、SQL、接入与验收

状态：可编码规格；无历史数据导入。设计附件不代表运行功能已实现。
本文补齐并优先于上层概述中的示意 DDL，正式 schema 以同目录 data-v1.sql、index-v1.sql 为准。

## 1. 类型与调用约定

以下 Java 签名是待实现 API，集合均为不可变快照。内部 Repository 同步阻塞，
仅在后台执行器调用；UI 使用 CompletionStage<Result<T>> 门面。
Clock、ZoneId、用户根通过构造器注入；每个用户独立 Runtime，方法不接受任意用户根参数。
公开 API 不暴露 Connection、ResultSet、数据库整数主键或可执行 SQL。

```java
record Failure(ErrorCode code, String message, boolean retryable) {}
sealed interface Result<T> {
    record Ok<T>(T value) implements Result<T> {}
    record Err<T>(Failure failure) implements Result<T> {}
}
enum ErrorCode {
    INVALID_ARGUMENT, NOT_FOUND, CONFLICT, IDENTITY_CONFLICT,
    PATH_REJECTED, TOO_LARGE, IO_ERROR, STORE_UNAVAILABLE,
    INDEX_UNAVAILABLE, UNSUPPORTED_SCHEMA, CLOSED
}
record Page<T>(List<T> items, String nextCursor) {} // null cursor = 已结束
record Todo(UUID uid, String title, String descriptionMd, TodoStatus status,
            LocalDate dueDate, int priority, Instant createdAt, Instant updatedAt,
            Instant completedAt, Instant deletedAt, long version,
            List<TodoSource> sources) {}
record TodoSource(UUID documentUid, String lastKnownPath, String sourceHash,
                  String heading) {}
record CreateTodo(String title, String descriptionMd, LocalDate dueDate,
                  int priority, List<TodoSource> sources) {}
record EditTodo(String title, String descriptionMd, LocalDate dueDate,
                int priority, List<TodoSource> sources) {}
record TodoQuery(TodoStatus status, LocalDate dueFrom, LocalDate dueBefore,
                 DueMode dueMode, String keywords, int limit, String cursor) {}
enum DueMode { ANY, DATED, UNDATED }
```

约定：dueDate/completedAt/deletedAt 可空；任务来源可空列表；标题 trim 后 1–200 Unicode
code points，说明 UTF-8 <=256KiB，来源 <=20，priority 0–3；超限返回 INVALID_ARGUMENT。
说明保留原始换行。Java LocalDate 严格解析日期，SQL 日期 CHECK 仅作基本格式防护。
TodoStatus 沿用 OPEN/CLOSED，数据库 lower-case 编解码集中在 Repository。
status 查询为空表示两种状态；默认 OPEN，不默认查已删除内容。
日期区间 [dueFrom,dueBefore)，ANY+日期区间会自然排除无日期项；
UNDATED 与日期区间同时出现返回 INVALID_ARGUMENT。
limit 默认20，范围1–50；关键词 <=200 code points；空白关键词视为无关键词。

游标为带版本的 Base64URL JSON：查询条件指纹、排序模式及最后一项排序键；
不接收 SQL 片段；损坏或与当前过滤条件不同返回 INVALID_ARGUMENT。
日期列表按 due_date,id 升序；ANY 时有日期在前，无日期在后，用 phase 区分游标。
关键词列表按 BM25,id 升序，游标包含 rank/id；数据变化不保证跨页快照，UI 修改后从第一页刷新。
游标内部整数 id 仅用于分页，不作为业务引用；SQL 仍重新应用所有过滤条件。

```java
interface TodoRepository {
    Result<Todo> create(CreateTodo input);
    Result<Todo> get(UUID uid);
    Result<Page<Todo>> list(TodoQuery query);
    Result<Todo> edit(UUID uid, long expectedVersion, EditTodo input);
    Result<Todo> complete(UUID uid, long expectedVersion);
    Result<Todo> reopen(UUID uid, long expectedVersion);
    Result<Todo> delete(UUID uid, long expectedVersion); // 返回软删除快照
}
```

edit 是完整替换可编辑字段，不含 status；dueDate=null 表示清除截止日期，
sources=[] 表示清除来源，避免 patch 缺失与 null 语义不明。
创建 version=1；每次成功修改+1；所有变更检查 expectedVersion>=1。
get/list 默认不返回软删除项。不存在/已软删除返回 NOT_FOUND，版本不符返回 CONFLICT。
complete 已 CLOSED、reopen 已 OPEN，在版本匹配时返回当前对象，不追加事件；
版本不匹配仍 CONFLICT。没有物理删除和恢复接口，保留数据供备份和后续功能使用。
来源关联只验证格式和用户根内文章身份，不要求两库共同事务；
可接受索引缺失，但创建来源时必须直接校验文件 ID/hash，之后文件消失也不删任务。

任务写事务：BEGIN IMMEDIATE → 查当前状态/版本 → 修改 todos → 写 todo_events 全快照
（含 sources）→ 替换 todo_sources → 生成词项并 upsert todo_search_content → COMMIT。
软删除时删除 todo_search_content，让删除触发器清除 FTS；状态完成仍保留索引供 closed 查询。
任何中间步骤失败都 ROLLBACK。一次调用不自动重复非幂等 create，避免提交结果未知时重复建任务；
前端在 create pending 时禁用重复提交，STORE_UNAVAILABLE 的未知提交结果不得自动重试。

## 2. 文章存取与搜索契约

```java
record DocumentRef(UUID uid, String relativePath) {} // 文章 uid 必填；日记/工作集为空
record Document(DocumentRef ref, String markdown, String contentHash,
                String title, String kind, LocalDate eventDate) {}
record NewArticle(String relativePath, String title, String kind,
                  List<String> aliases, List<String> tags,
                  LocalDate eventDate, String bodyMarkdown) {}
record SaveOutcome(Document document, boolean indexReady) {}
interface MarkdownDocumentStore {
    Result<SaveOutcome> create(NewArticle input);
    Result<SaveOutcome> update(DocumentRef ref, String expectedHash, String markdown);
    Result<Document> read(DocumentRef ref, String expectedHash);
}
enum IndexState { BUILDING, READY, STALE, FAILED }
record IndexStatus(IndexState state, Instant lastReconciledAt, int failedPaths) {}
record ArticleQuery(String keywords, String kind, Set<String> tags,
                    LocalDate from, LocalDate before, int limit) {}
record ArticleHit(DocumentRef ref, String title, String heading,
                  int startLine, int endLine, String snippet,
                  String contentHash, double rank) {}
record ArticleResults(List<ArticleHit> hits, IndexStatus indexStatus,
                      boolean truncated) {}
interface ArticleSearch {
    Result<ArticleResults> search(ArticleQuery query);
}
interface KnowledgeSyncService extends AutoCloseable {
    CompletionStage<Result<IndexStatus>> start();
    void changed(String relativePath);
    CompletionStage<Result<IndexStatus>> reconcile();
    CompletionStage<Result<IndexStatus>> rebuild();
    IndexStatus status();
    void close();
}
```

日记/工作集在索引表使用固定命名空间 + 规范相对路径计算的确定性 UUID（UUID.nameUUIDFromBytes），
不写回文件，也不允许作为 todo_sources.document_uid；它只是满足索引内部身份约束。
knowledge/KNOWLEDGE.md 不创建、不作为正式文章索引，目录由UI根据索引生成。

文章路径必须 knowledge/ 下且不为 knowledge/todos/，扩展名 .md；
kind 枚举 article/meeting/decision/person/project/playbook/inbox；title 1–200字，
aliases/tags 各<=32项、单项<=100字，文件含元数据总大小<=256KiB。
create 自动生成 UUID，目标已存在则 CONFLICT，禁止覆盖。
update 必须携带 expectedHash，并禁止更改原有 UUID；保存后 UUID 冲突返回 IDENTITY_CONFLICT。
read 的 expectedHash 可空（主动打开最新）；有值且变化返回 CONFLICT，不悄悄返回新证据。
UUID 按索引定位后再次验证实际文件的 front matter；索引未就绪时可用 ref.relativePath
读取并核验 ID，不能只因索引缺失便断言文件不存在。
不存在返回 NOT_FOUND；超限 TOO_LARGE；越界 PATH_REJECTED；格式错误 INVALID_ARGUMENT。
文件保存成功、索引失败返回 Ok(indexReady=false)，不能返回一般失败诱使用户重复创建。

日期区间半开、tags 多项采用 AND；kind/tags/date 空则不过滤。
文章查询允许仅过滤不传关键词，此时按 event_date DESC（空值最后）、path ASC 排序，
rank=0；全空查询返回 INVALID_ARGUMENT，列目录仍走独立目录视图。
有效关键词太少返回 INVALID_ARGUMENT 并建议补充词，不将其包装为零命中。
limit 默认10、范围1–50；query 最大200字；snippet 最长240 code points。
READY+空结果才代表此次检索零命中，BUILDING/STALE 空结果需提示同步尚未完成；
FAILED 时返回 INDEX_UNAVAILABLE。失败路径使整体状态 STALE 并可查看错误详情。
lastReconciledAt 在完整对账后更新，为空表示未完成过；READY 不承诺外部修改零延迟可见。

搜索结果最多每篇一条（最佳块）；先 SQL 类型/标签/日期过滤再排序去重。
具体 SQL 用 DISTINCT/EXISTS 避免标签 JOIN 重复；分批取有序块，固定 maxCandidates=5000，
达到预算且仍有候选则 truncated=true。不无界读取所有匹配行。
读取全文后验证 hash，冲突时更新索引并最多重搜一次；仍变动则报告 CONFLICT。
UI 可以展示带陈旧提示的标题，但给模型的事实证据必须通过最新 read 校验。

## 3. 完整 SQL 与初始化约定

可执行附件：data-v1.sql、index-v1.sql，均包含 BEGIN/COMMIT 和 user_version=1。
程序仅在 user_version=0 且无业务表的新库执行；不重复执行 CREATE TABLE。
若 version=0 但已存在部分业务表，报告 UNSUPPORTED_SCHEMA，不自动 DROP。
user_version=1 时校验必需表/触发器；更高版本拒绝正式库写入。
连接先设置 WAL、busy_timeout=5000、foreign_keys=ON；data.db synchronous=FULL，
index.db 可 NORMAL。测试 in-memory 模式用于校验 SQL，磁盘/WAL 行为另测。

两个 FTS 都使用 external content；Java 生成规范化词项，SQL 触发器仅同步词项表到 FTS。
不能声称 SQLite 触发器自动执行 Java 中文切词。所有 todo 写入口必须经过 Repository，
禁止通过任意 SQL 直接修改 todos 而跳过词项生成。
外键级联删除 search_content 同样触发 FTS 删除，foreign_keys 必须每连接启用。

FTS 单独修复：

```sql
-- 词项表已完整时，重建倒排索引
INSERT INTO article_fts(article_fts) VALUES('rebuild');
INSERT INTO article_fts(article_fts,rank) VALUES('integrity-check',1);
-- TODO 库分别执行
INSERT INTO todo_fts(todo_fts) VALUES('rebuild');
INSERT INTO todo_fts(todo_fts,rank) VALUES('integrity-check',1);
```

TODO 词项生成规则变化时，事务内重新生成整个 todo_search_content，再执行 rebuild。
文章解析规则变化时从文件重新建所有索引，不从旧 chunks 反推正式正文。
初始化索引失败可重试重建；正式库失败明确报错，不返回空库。两库初始化不是共同事务，
Runtime 必须根据每库状态恢复；索引不可用时任务基础功能可继续使用。

## 4. 提醒接口与状态机

```java
record PendingReminder(UUID uid, LocalDate day, Set<ReminderSlot> slots,
                       List<Todo> todos) {}
interface ReminderRepository {
    Result<Optional<PendingReminder>> claim(LocalDateTime now);
    Result<List<PendingReminder>> pending();
    Result<PendingReminder> refresh(UUID batchUid);
    Result<Void> acknowledge(UUID batchUid);
    Result<Void> cancel(UUID batchUid);
}
```

claim 的 now 仅由服务从固定 ZoneId 的 Clock 计算，不接受工具/模型传入任意时钟。
候选条件：open、未删除、due_date非空且 <= today+3天，含全部逾期；
排序 due_date ASC、title 按 Java String.compareTo、uid 打破同名平局。
无日期任务不提醒，修正现有 TodoReminderPolicy 的 null due 假设。
符合时间的槽位：LOGIN 总是；TEN 在10:00起；FOURTEEN 在14:00起。
claim 使用 BEGIN IMMEDIATE，一次认领全部到期未消费槽位；无任务/无槽位则返回 empty。
reminder_batches 与所有 slots 同一事务提交；唯一键竞争时重读，不产生部分槽位批次。
一个用户同时只允许一个负责展示的提醒调度器，claim 串行，避免多个窗口重复展示。

pending 按 created_at,uid 排序；payload 使用格式版本1并存 uid+version 快照，
refresh 重查当前任务，已完成/已删除/修改到窗口外任务移除，剩余项更新为最新值。
无剩余项则调用 cancel：pending→cancelled，槽位仍已消费，防止当天循环重建空批次。
acknowledge：pending→delivered；重复 acknowledge delivered 返回成功；不存在批次返回 NOT_FOUND；
cancelled 后 acknowledge 返回 CONFLICT。重复 cancel cancelled 返回成功，delivered 后取消冲突。
状态变更条件更新确保并发不倒退；展示失败不确认，保留 pending 下次重试。
Runtime 启动先刷新 pending，再 claim 新槽位；历史 pending 以当前窗口重检，不回放已关闭任务。
第一期 pending 按生成日保存槽位，但重检窗口使用当前本地日期。

## 5. 模块接入清单（完成后不得残留双写入口）

| 文件/模块（均在 src/main 下） | 必须修改 | 验收证据 |
|---|---|---|
| java/.../kelsy/KelsyRuntime.java | 创建每用户 DataStore、文章仓储、同步服务；集中 close | 用户隔离；退出等待当前短写完成后关闭连接/监听 |
| java/.../kelsy/service/KnowledgeStore.java | 从 record 调整为可持有服务的门面；新 search/read 返回状态 | 无搜索前全目录 reconcile；保持路径防护 |
| KnowledgeIndex.java、CjkNgrams.java、CardFields.java | schema v1/FTS/分块；code point切词；front matter解析 | 词项/别名/日期/删除正确 |
| 新增 storage/DatabaseBootstrap、SqliteTodoRepository、TodoService | 加载SQL、事务、错误映射、审计 | 不开放原始 SQL 工具 |
| 新增 MarkdownDocumentStore、KnowledgeSyncService | 文件写入/哈希/监听/对账 | 原子保存、漏事件补偿 |
| service/IndexingAgentTool.java | 文件/日记写后入同步队列；拦截 TODO 文件写入 | 文件工具不能绕过任务服务创建 knowledge/todos |
| service/LocalAssistantService.java、KnowledgeSearchTool.java | 注册 knowledge_* 和 todo_*；统一错误与状态格式 | 无文件式任务归档提示 |
| service/AskGrounding.java、LocalEvidence.java、SlashCommands.java | 任务意图走仓储；混合证据分组；/find依然本地搜索 | 无 TodoScanner 或全文摘要推断任务状态 |
| service/CitationTurn.java、KnowledgePathExtractor.java | 支持 article uid/hash 与 todo uid/version；任务 get/list计入检索 | 新引用可打开，不被旧路径正则丢弃 |
| service/WorkspaceSeeder.java、MemoryCompactor.java | 不创建todos目录；inbox文章用仓储生成ID | 生成文件满足新协议，日记/工作集不受影响 |
| kelsy/todo/TodoCard.java、ReminderItem/Batch/Format.java | DTO改uid/version及nullable due；格式解析改结构化引用 | 新提醒可渲染，链接打开 todo_get |
| kelsy/todo/TodoReminderService.java、TodoReminderPolicy.java | 注入repository/clock；替换文件账本和扫描 | 无日期安全；due<=today+3 |
| kelsy/todo/TodoScanner.java、TodoCardParser.java、ReminderLedger.java | 删除或撤下生产调用；同步调整测试 | 生产代码不再引用旧持久化入口 |
| ui/chat/ChatController.java、ChatPane.java | Runtime注入提醒；移除addLocalEvidence文件扫描；异步结果回FX | 重登不叠加调度器，退出停止回调 |
| kelsy/ui/knowledge/KnowledgePane.java、kelsy/ui/AssistantBubble.java | 区分文章与任务来源，展示同步/冲突状态 | 不展示预分词正文；任务最新状态 |
| resources/.../workspace/skills/kelsy-knowledge及相关AGENTS模板 | 任务工具、文章协议、引用和失败规则 | 提示中不再指导写TODO卡片 |
| module-info.java、pom.xml | 必要的包/资源与解析器依赖；SQLite已有依赖优先复用 | 模块构建和安装包能加载SQL/FTS |

省略路径前缀统一为 com/mordor/kelly；实际编码前按文件名定位。
IndexingAgentTool 拦截只管应用工具，不阻止用户编辑器。外部 knowledge/todos/*.md 报不支持，
不解释成正式任务，也不纳入文章搜索。
数据库 SQL 附件实现时复制到 src/main/resources/.../storage/schema/；附件与资源不得长期各自演变。

工具参数 contract：create/edit 对应上述 record；update/complete/reopen/delete 必须 uid+expectedVersion；
knowledge_get 使用 uid、path fallback 和可选 expectedHash；knowledge_update 必须 expectedHash。
所有工具返回 JSON：{ok:true,data:...} 或 {ok:false,error:{code,message,retryable}}；
日期 ISO，UUID标准字符串，Instant UTC ISO；模型不能通过工具指定文件系统绝对路径。
已删除对象不给模型返回正文；STORE_UNAVAILABLE 不能转成“没有任务”。

## 6. 验收用例与交付门槛

| ID / 待实现测试类 | 输入与操作 | 必须断言 |
|---|---|---|
| DB01 DatabaseBootstrapTest | 新目录初始化两次、关库再开 | schema=1、第二次保留所有任务；高版本拒写 |
| DB02 DatabaseBootstrapTest | 建库事务注入故障 | 无半套提交；重试可用；不删除正式库 |
| TD01 SqliteTodoRepositoryTest | 创建/编辑/完成/重开/删除 | version按约定变化；completed_at准确；审计快照和来源一致 |
| TD02 SqliteTodoRepositoryTest | 两次以version=1编辑 | 仅一次成功，另一次CONFLICT；无多余事件 |
| TD03 SqliteTodoRepositoryTest | 审计或词项写入注入失败 | todos、sources、events、FTS全部回滚 |
| TD04 TodoQueryTest | 逾期/今天/周末/下周/无日期/closed/删除混合 | 半开边界、默认open、无日期独立、分页无重复（无并发时） |
| TD05 TodoQueryTest | 关键词高排名closed+低排名open，limit=1 | 仍返回符合条件的open；过滤先于LIMIT |
| FT01 SearchSchemaTest | insert→update→delete词项 | 新词命中，旧词消失，删除无残留 |
| FT02 SearchSchemaTest | 级联删除chunk；损坏/重建FTS | 无幽灵命中，integrity-check通过；正式TODO不丢 |
| FT03 CjkNgramsTest/ArticleSearchTest | 许可证/licence、扩展汉字、引号/OR/* | 别名能搜、Unicode不切断、用户语法不被执行 |
| AR01 MarkdownDocumentStoreTest | create、hash匹配更新、旧hash更新 | UUID稳定、过期更新冲突、文件未被覆盖 |
| AR02 ArticleIdentityTest | 重命名、复制同ID、缺少ID、无效front matter | 重命名保关联；重复ID冲突；格式错误可见 |
| AR03 ArticleSearchTest | 同文多块命中、标签/日期过滤、仅过滤查询 | 每篇一条、排序确定、摘要来自原文、候选预算有truncated |
| SY01 KnowledgeSyncServiceTest | 外部原子保存/新增子目录/delete-create | 合并事件、监听补齐、最终hash一致 |
| SY02 KnowledgeSyncServiceTest | 丢事件/overflow/同mtime同大小改写 | 周期/完整哈希对账修复；搜索不触发全盘扫描 |
| SY03 KnowledgeSyncServiceTest | 保存成功但索引事务失败 | 返回indexReady=false，文件存在；重试后命中 |
| SY04 AskGroundingTest | BUILDING空结果/陈旧hash/FAILED | 不声称未归档；旧块不作为最新证据 |
| RM01 TodoReminderServiceTest | 固定时钟9:59/10:00/13:59/14:00 | 对应槽位；due=today+3纳入、+4排除，逾期纳入，无日期排除 |
| RM02 ReminderRepositoryTest | 同日重复claim、重启、未确认批次 | 不重复认领槽位；pending可重试，ack幂等 |
| RM03 ReminderRepositoryTest | pending期间完成/删除/改期 | refresh移除；全部移除cancel；槽位仍消费 |
| RM04 ReminderRepositoryTest | 认领提交后崩溃/展示后确认前崩溃 | 前者可恢复；后者允许同batch重试，UI按uid去重 |
| IN01 LocalAssistantServiceTest | 工具创建任务/查询任务/写todos文件 | 走DB；禁止文件写入；失败结构化返回 |
| IN02 CitationTurnTest/KnowledgePaneTest | article uid/hash、todo uid/version | 两类来源能打开；缺失/变化不伪装最新 |
| IN03 KelsyRuntimeTest/ChatControllerKelsyTest | 用户A/B登录、重登、退出 | 数据隔离、只有一个调度器、无关闭后FX更新 |
| BK01 BackupRestoreTest | 创建文章+任务→备份→新目录恢复 | UUID/任务/提醒保留；索引可重建；改写冲突有提示 |
| OS01 平台验收 | Linux/macOS/Windows打包运行 | SQL资源/驱动/FTS加载，中文路径/监听降级/文件替换可用 |

所有Java测试使用临时用户根和固定Clock；监听单测使用注入事件源/调度器，不依赖长sleep。
少量真实文件系统集成测试单独标记，跨平台运行验证，不能用模拟监听测试代替。
性能用例独立于单测：10万TODO/10万块、热P95、冷启动、查询计划、同步延迟和内存；
达不到目标先记录实测与瓶颈，不扩大limit或取消过滤掩盖问题。

P1门槛：DB/TD/RM/IN01及Runtime生命周期通过，不再有任务文件写入口。
P2门槛：FT/AR/SY/IN02通过，文章/日记与模型证据接通。
P3门槛：BK/OS及性能报告完成。已有UI、提醒、引用、用户根隔离测试按新契约更新并回归。

## 7. 可执行设计验证

2026-11-04：在 SQLite 3.46.1 上运行 7 项设计 SQL 检查全部通过：
初始化版本与外键、TODO FTS 增改删、文章 FTS 与级联删除、事务回滚、
索引删除后 rebuild/integrity-check、过滤后 LIMIT、状态约束与提醒槽位原子性。


运行 `python3 docs/design/sqlite-knowledge/verify_schema.py`。
该脚本验证附件SQL的真实SQLite行为，不代表Java仓储、界面或三平台运行验收通过。
FTS行为依据 SQLite 官方文档：https://www.sqlite.org/fts5.html#external_content_tables。
