# 示例

- “下周一提交报告”：确认本地日期后 todo_create(title="提交报告",dueDate="YYYY-MM-DD",descriptionMd="",priority=0)。
- “还有哪些待办”：todo_list(status="open")，按 nextCursor 取后续页面，不能声称第一页是全部。
- “完成这个任务”：todo_get(uid)，再 todo_complete(uid,expectedVersion)。
- “记录评审决定”：槽位齐全后 knowledge_create(kind="decision",path="knowledge/decisions/主题.md",title=主题,bodyMarkdown=原文,aliases=[相关别名],tags=[])。
- “许可证当时怎么定的”：knowledge_search(query="许可证")，knowledge_get(path=命中路径,expectedHash=命中哈希)。
- 工具存储错误：如实说明暂时不可用，不能解释为用户没有任务/没有归档。
