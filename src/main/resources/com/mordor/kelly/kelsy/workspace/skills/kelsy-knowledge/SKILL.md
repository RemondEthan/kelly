---
name: kelsy-knowledge
description: 管理 Markdown 知识文章、SQLite 待办与长期工作记忆。
---

# 数据归属

文章正文存 Markdown，SQLite 只索引文章。TODO 的标题、说明、状态、截止日期及提醒存 SQLite。
禁止用 write_file/edit_file 创建或修改 TODO 文件，也不要维护 knowledge/KNOWLEDGE.md。

# 任务

创建任务使用 todo_create。查询未完成/到期/逾期任务使用 todo_list，不能根据文章内容判断任务当前状态。
参数 status 默认 open；from 包含、before 不包含；dueMode=undated 查询无日期任务。
更新前 todo_get 获取最新 uid/version，再用 todo_update、todo_complete、todo_reopen、todo_delete，携带 expectedVersion。
todo_update 完整替换标题、说明、截止日期、优先级；需要保留的字段必须完整传回。
日期不能推测：用户未提供截止日期则 dueDate=null。优先级范围0–3。
版本冲突时重新读取，不盲目覆盖。来源以 todo:<uuid> 引用，不能编造文件路径。

# 知识文章

归档会议/决定前确认谁、日期、主题、结论；缺必要信息先澄清。
使用 knowledge_create，path 位于 knowledge/ 下，kind 为 article/meeting/decision/person/project/playbook/inbox。
提供 title、bodyMarkdown、aliases、tags 和可选 date。工具自动生成稳定 UUID 与 YAML 元数据。
更新先 knowledge_get，然后 knowledge_update 提交完整 markdown 和 expectedHash，保留原 UUID。
不使用普通文件写工具绕过文章格式和并发检查。

# 检索与证据

本轮已有“已检索候选”时先 knowledge_get(path,expectedHash) 核验原文。
需要已归档事实但候选不足时，knowledge_search 补查，再 knowledge_get 读取命中正文。
最多读取5篇相关原文，引用 article:<uuid> 或实际路径。
BUILDING/STALE/FAILED 表示索引未就绪、陈旧或失败，空结果不能据此说没有归档。
读取冲突时刷新搜索；不能把陈旧片段当成最新结论。
禁止使用内置 memory_search，不为查一条事实遍历整个目录。
只依据实际工具结果回答，不从会话印象推断任务已完成或知识已归档。

# 工作集与日记

MEMORY.md 只保留偏好、当前项目及短指针，目标不超过4KB。
memory_save 负责工作集和当日日记；禁止 write_file/edit_file 改 MEMORY.md 或 memory/。
归档文章或任务后只向工作集保留必要短指针，不复制整篇正文。
