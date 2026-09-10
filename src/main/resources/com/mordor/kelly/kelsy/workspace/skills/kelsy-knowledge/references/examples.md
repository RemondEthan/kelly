# 归档与回忆例子

用 `load_skill_through_path` 加载本文件。不要把本页写进 MEMORY.md。

## 会议卡片：槽位不齐

用户 `/note 会上讲了交付`（你收到的是改写后的澄清指令）。

缺谁、结论、待办。只问缺的。此时不要 `memory_save`，不要写 `knowledge/meetings/`。

## 会议卡片：齐了再落盘

用户补全：客户 XX，结论「先申请再发货」，待办无。日期按当天 2026-09-04。

写卡片 `knowledge/meetings/2026-09-04-客户XX-交付licence.md`，正文含：

```
- 结论：先申请再发货
- 别名：licence, license, 许可证, 交付许可
```

`KNOWLEDGE.md` 加一行。`memory_save` 日记和 `MEMORY.md` 各一行，必须出现 `许可证`、`licence` 和卡片路径。

回复点名这些路径。不要说「已经记下了」却不写路径。

## 回忆：客户交付 licence 的会

用户：「有一个关于客户交付 licence 的会议，当时结论是什么？」

不要整句当一个词。search：`会议`、`交付`、`licence`、`许可证`。没给日期，不限制窗口。

命中指针后 `read_file` 卡片。答：「先申请再发货」+ `来源：knowledge/meetings/2026-09-04-客户XX-交付licence.md`。

另有一张不同客户的类似卡：两张都列出，让用户挑。

search 与目录都空：答「知识库没有这场会议的归档。」不要补一段像真的。

## 普通一条（非会议）

用户：「今天下午和张三把评审定在周五了。」

不是会议门闩。`memory_save`：

```
- 2026-09-02 与张三敲定评审方案，周五评审
```

不要收成：`- 定了评审`。

## 取消

澄清中用户说「先不记了」：不写任何文件。

## 会议待办拆成 todo 卡

用户 `/note` 一场会，待办是「申请 licence；周五发周报」。缺截止：只问截止，不写任何文件。

补全截止 2026-09-10、2026-09-12 后：先写会议卡，再写

- `knowledge/todos/2026-09-10-申请-licence.md`（`状态：open`）
- `knowledge/todos/2026-09-12-发周报.md`

`KNOWLEDGE.md` 与 `memory_save` 带上会议路径和这两张 todo 路径。

## 单独记一条待办

用户：「记一条待办：周五交周报。」问截止日。齐了只写 `knowledge/todos/` 卡，带 `- 原文：记一条待办：周五交周报。`，不要只 `memory_save` 一行没有状态的摘要。

## `/note` 回显

会议卡片落盘后，回复例如：「已写入 `knowledge/meetings/2026-09-04-客户XX-交付licence.md`、`MEMORY.md` 与 `memory/2026-09-04.md`。」
