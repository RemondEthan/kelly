# 混合存储实现说明

实现日期：2026-11-05。无旧数据导入或新旧双写。

## 已接通的主路径

- 每用户 .kelly-data.db：任务 CRUD、乐观版本检查、审计快照、文章来源、中文全文搜索与游标分页。
- 任务工具 todo_create/get/list/update/complete/reopen/delete，统一 JSON 成功/失败返回；JDBC 在后台线程运行。
- 提醒查询直接读取任务数据库，持久化认领 LOGIN/TEN/FOURTEEN 槽位；pending 可恢复，完成/删除/改期后重检，界面按批次 ID 去重。
- MarkdownDocumentStore：安全路径、YAML front matter/稳定 UUID、哈希检查与文件写入。
- .kelly-index.db：文章/日记分块、FTS5、标题与别名权重、SQL 日期/标签过滤、文档去重、陈旧哈希检查。
- 后台 WatchService 和每5分钟完整哈希对账。搜索不触发全目录扫描。
- knowledge_create/get/update/search 工具、文章和任务引用、知识面板、ASK 与 /find 接入。
- 模型技能与播种目录切换至新协议，普通文件工具拒绝对知识、任务和工作集进行绕过写入。
- 正式库在线备份接口及 Markdown+数据库 ZIP 备份/恢复服务；新库高版本拒写，只有文章派生索引允许隔离损坏副本后重建。

## API 与规格的具体化

Java 仓储用带枚举 Code 的 StorageException 表达失败，不返回空列表掩盖存储错误；
AgentTool 边界转换为约定的 {ok,data}/{ok,error} JSON。
任务来源与任务 CRUD 在 TodoRepository 内实现，文件仓储负责文章 ID/hash 校验。
工具类本身使用 boundedElastic 执行 JDBC；控制器的预检索、/find、提醒和知识预览也移到后台。
文章短段落按标题/空行切块，超长段落分成1000 code point片段并重叠100；行号指向原文。
现有 Hit.score 仅为旧 UI/ASK 接口的入选标识，不能视为置信度；真实排序使用 Hit.rank。

## 使用边界

- 新文章必须有合法 front matter；通过 knowledge_create 自动生成。日记/MEMORY.md 不要求该格式。
- TODO 由任务工具维护，knowledge/todos/*.md 不作为任务或文章导入。
- 文件与 SQLite 不是共同事务；正文已保存但索引失败时返回 indexReady=false，后台对账修复。
- 提醒为至少一次投递，进程跨重启不承诺界面严格恰好一次。
- HybridBackupService.backup 调用方需要暂停应用写入；外部文件变化在备份时核验并报错。
- restore 只接受不存在的目标目录；ZIP 解压预算每条目64MiB、总计512MiB，超限明确失败。
- 备份/恢复目前是 Java 服务接口，尚未新增专用的设置页按钮。
- 尚未实测 macOS/Windows 安装包，也未验证10万任务/10万块的性能目标。

## 验证命令

```bash
mvn -q -Dtest='HybridStorageTest,HybridAgentToolsTest' test
xvfb-run -a mvn -q -Djava.util.prefs.userRoot=/tmp/kelly-test-prefs test
python3 docs/design/sqlite-knowledge/verify_schema.py
```

完整 SQL 的运行版本位于 src/main/resources/com/mordor/kelly/kelsy/storage/schema/。
