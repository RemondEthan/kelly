# Long-Term Memory (分层工作集 + 进程内 FTS) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让对话检索走进程内 SQLite FTS，并把 `MEMORY.md` 收成硬上限 4KB 的工作集，现有 Markdown 知识库自动升级、无需搬家。

**Architecture:** Markdown 仍是唯一真相。每个用户知识根旁生成 `.kelly-index.db`。`KnowledgeStore.search` / `cardsContaining` / 对话 `AskGrounding` / 工具 `knowledge_search` 共用这一索引。ASK 在调用模型前按词预检索，分数够才附 top 5 候选。`memory_search` 用 `ToolsConfig.deny` 关掉。

**Tech Stack:** Java 21, JUnit 5.10.2, `org.xerial:sqlite-jdbc:3.47.1.0`（JPMS 模块名 `org.xerial.sqlitejdbc`；`sqlite.jdbc` 无法编译）, AgentScope 2.0.1 `Toolkit.registerAgentTool` / `ToolsConfig.setDeny`.

**Spec:** `docs/superpowers/specs/2026-09-15-long-term-memory-design.md`

## Global Constraints

- 不上 embedding、不上 Redis/ES/Qdrant；只加 sqlite-jdbc。
- 不改会议/决定/待办卡片字段约定；不拆 `KNOWLEDGE.md`。
- 不覆盖用户改过的 `AGENTS.md` / `MEMORY.md` / `knowledge/KNOWLEDGE.md`。
- `memory_save` 仍只写 `MEMORY.md` 和当天日记。
- `/find` 不发给模型。
- 索引文件：`<knowledgeRoot>/.kelly-index.db`。
- L0 硬上限：4096 字节。
- 每轮：FTS 至多 2 次（预检索 + 一次补查），`read_file` ≤ 5 张由 SKILL 约束。
- 搜索失败必须回退线性扫描并警告，禁止空结果冒充没归档。
- 单测不打真实 embedding / 真实 LLM API。
- 测试命令：`mvn -q -Dtest=<Class>#<method> test`
- 提交信息用 `feat:` / `test:` / `docs:`，不要 `--no-verify`。

---

## File Structure

新增：

- `src/main/java/com/mordor/kelly/kelsy/service/CjkNgrams.java`
- `src/main/java/com/mordor/kelly/kelsy/service/CardFields.java`
- `src/main/java/com/mordor/kelly/kelsy/service/KnowledgeIndex.java`
- `src/main/java/com/mordor/kelly/kelsy/service/MemoryCompactor.java`
- `src/main/java/com/mordor/kelly/kelsy/service/AskGrounding.java`
- `src/main/java/com/mordor/kelly/kelsy/service/KnowledgeSearchTool.java`
- `src/test/java/com/mordor/kelly/kelsy/service/CjkNgramsTest.java`
- `src/test/java/com/mordor/kelly/kelsy/service/CardFieldsTest.java`
- `src/test/java/com/mordor/kelly/kelsy/service/KnowledgeIndexTest.java`
- `src/test/java/com/mordor/kelly/kelsy/service/MemoryCompactorTest.java`
- `src/test/java/com/mordor/kelly/kelsy/service/AskGroundingTest.java`
- `src/test/java/com/mordor/kelly/kelsy/service/KnowledgeUpgradeTest.java`

修改：

- `pom.xml` — sqlite-jdbc
- `src/main/java/module-info.java` — `requires sqlite.jdbc;`
- `src/main/java/com/mordor/kelly/kelsy/service/KnowledgeStore.java` — 打开索引；`Hit` 增加 `score`；`search`/`cardsContaining` 走 FTS
- `src/main/java/com/mordor/kelly/kelsy/KelsyRuntime.java` — seed 后 reconcile + compact
- `src/main/java/com/mordor/kelly/kelsy/service/LocalAssistantService.java` — deny `memory_search`，注册 `knowledge_search`
- `src/main/java/com/mordor/kelly/kelsy/service/CitationTurn.java` — RETRIEVAL 加 `knowledge_search`
- `src/main/java/com/mordor/kelly/ui/chat/ChatController.java` — `startAsk` / `addLocalEvidence` 走 `AskGrounding`
- `src/main/java/com/mordor/kelly/kelsy/service/SlashCommands.java` — `/today` 文案
- `src/main/resources/com/mordor/kelly/kelsy/workspace/skills/kelsy-knowledge/SKILL.md`
- `src/main/resources/com/mordor/kelly/kelsy/workspace/skills/kelsy-knowledge/references/examples.md`
- 现有测试：`KnowledgeStoreSearchTest`、`CitationTurnTest`、`SlashCommandsTest`、`LocalEvidenceTest`

---

### Task 1: 加入 sqlite-jdbc 与模块依赖

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/module-info.java`

**Interfaces:**
- Consumes: 无
- Produces: 编译期可 `import org.sqlite.SQLiteConfig`；`module-info` 使用 `requires org.xerial.sqlitejdbc;`

- [ ] **Step 1: 在 `pom.xml` 的 `</dependencies>` 前加入依赖**

插在 commonmark-ext-gfm-tables 依赖块之后、junit 之前：

```xml
        <dependency>
            <groupId>org.xerial</groupId>
            <artifactId>sqlite-jdbc</artifactId>
            <version>3.47.1.0</version>
        </dependency>
```

- [ ] **Step 2: 在 `module-info.java` 的 AI Agent 框架段落后加一行**

```java
    requires sqlite.jdbc;                   // 嵌入式 SQLite：知识库 FTS 索引
```

- [ ] **Step 3: 编译确认模块名正确**

Run: `mvn -q -DskipTests compile`

Expected: BUILD SUCCESS。若报 `module not found: sqlite.jdbc`，用 `jar --file=$HOME/.m2/repository/org/xerial/sqlite-jdbc/3.47.1.0/sqlite-jdbc-3.47.1.0.jar --describe-module` 核对自动模块名后再改 `requires`。

- [ ] **Step 4: Commit**

```bash
git add pom.xml src/main/java/module-info.java
git commit -m "$(cat <<'EOF'
feat: add embedded sqlite-jdbc for knowledge FTS

EOF
)"
```

---

### Task 2: CJK 二元切分

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/service/CjkNgrams.java`
- Test: `src/test/java/com/mordor/kelly/kelsy/service/CjkNgramsTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `CjkNgrams.forIndex(String raw) -> String`；`CjkNgrams.forQuery(String raw) -> String`。CJK 连续段输出「全部相邻二元 + 原段」；ASCII/数字词原样保留，空白分隔。

- [ ] **Step 1: 写失败测试**

```java
package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CjkNgramsTest {

    @Test
    void licenceKeepsAscii() {
        assertEquals("licence", CjkNgrams.forIndex("licence").strip());
        assertEquals("licence", CjkNgrams.forQuery("licence").strip());
    }

    @Test
    void chineseEmitsBigramsAndOriginal() {
        String out = CjkNgrams.forIndex("许可证");
        assertTrue(out.contains("许可"));
        assertTrue(out.contains("可证"));
        assertTrue(out.contains("许可证"));
    }

    @Test
    void mixedKeepsBoth() {
        String out = CjkNgrams.forIndex("交付 licence");
        assertTrue(out.contains("licence"));
        assertTrue(out.contains("交付"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=CjkNgramsTest test`

Expected: FAIL，`CjkNgrams` 不存在。

- [ ] **Step 3: 实现**

```java
package com.mordor.kelly.kelsy.service;

public final class CjkNgrams {

    private CjkNgrams() {
    }

    public static String forIndex(String raw) {
        return expand(raw);
    }

    public static String forQuery(String raw) {
        return expand(raw);
    }

    private static String expand(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        StringBuilder other = new StringBuilder();
        Runnable flushCjk = () -> {
            if (cjk.isEmpty()) {
                return;
            }
            String run = cjk.toString();
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(run);
            if (run.length() >= 2) {
                for (int i = 0; i < run.length() - 1; i++) {
                    out.append(' ').append(run, i, i + 2);
                }
            }
            cjk.setLength(0);
        };
        Runnable flushOther = () -> {
            if (other.isEmpty()) {
                return;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(other);
            other.setLength(0);
        };
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                flushOther.run();
                cjk.appendCodePoint(cp);
            } else if (Character.isWhitespace(cp)) {
                flushCjk.run();
                flushOther.run();
            } else {
                flushCjk.run();
                other.appendCodePoint(cp);
            }
        }
        flushCjk.run();
        flushOther.run();
        return out.toString();
    }

    static boolean isCjk(int cp) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(cp);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=CjkNgramsTest test`

Expected: BUILD SUCCESS，3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mordor/kelly/kelsy/service/CjkNgrams.java src/test/java/com/mordor/kelly/kelsy/service/CjkNgramsTest.java
git commit -m "$(cat <<'EOF'
feat: add CJK bigram tokenizer for FTS

EOF
)"
```

---

### Task 3: 卡片字段解析

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/service/CardFields.java`
- Test: `src/test/java/com/mordor/kelly/kelsy/service/CardFieldsTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `CardFields.parse(String relativePath, String markdown)` → `record CardFields(String type, String who, String date, String title, String aliases, String status)`。缺字段为 `""`。`title` 优先 `# ` 首行，否则用文件名（去 `.md`）。识别 `- 类型：` `- 谁：` `- 日期：` `- 截止：`（截止日期也写入 `date`）`- 别名：` `- 状态：` `- 主题：`（主题可补进 title 若 title 空）。

- [ ] **Step 1: 写失败测试**

```java
package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CardFieldsTest {

    @Test
    void parsesMeetingCard() {
        var f = CardFields.parse(
                "knowledge/meetings/2026-09-04-客户XX-交付licence.md",
                """
                # 会议 · 客户XX · 交付 licence
                - 日期：2026-09-04
                - 类型：会议
                - 谁：客户XX
                - 主题：交付是否需要 licence
                - 别名：licence, license, 许可证
                """);
        assertEquals("会议", f.type());
        assertEquals("客户XX", f.who());
        assertEquals("2026-09-04", f.date());
        assertEquals("licence, license, 许可证", f.aliases());
        assertEquals("会议 · 客户XX · 交付 licence", f.title());
    }

    @Test
    void todoUsesDeadlineAndStatus() {
        var f = CardFields.parse(
                "knowledge/todos/2026-03-20-周报.md",
                """
                # 待办 · 周报
                - 截止：2026-03-20
                - 状态：open
                """);
        assertEquals("2026-03-20", f.date());
        assertEquals("open", f.status());
    }

    @Test
    void missingFieldsAreEmpty() {
        var f = CardFields.parse("knowledge/inbox/note.md", "随便一句话");
        assertEquals("", f.type());
        assertEquals("note", f.title());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=CardFieldsTest test`

Expected: FAIL。

- [ ] **Step 3: 实现 `CardFields`**

按上面契约逐行扫：`strip` 后若以 `# ` 开头则 title；若匹配 `- 键：值`（全角/半角冒号都认）。`截止` 写入 `date`。文件名 title：`Path.of(relativePath).getFileName()` 去掉 `.md`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=CardFieldsTest test`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mordor/kelly/kelsy/service/CardFields.java src/test/java/com/mordor/kelly/kelsy/service/CardFieldsTest.java
git commit -m "$(cat <<'EOF'
feat: parse structured fields from knowledge cards

EOF
)"
```

---

### Task 4: KnowledgeIndex（SQLite FTS）

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/service/KnowledgeIndex.java`
- Test: `src/test/java/com/mordor/kelly/kelsy/service/KnowledgeIndexTest.java`

**Interfaces:**
- Consumes: `CjkNgrams`、`CardFields`、`FindQuery`、`KnowledgeStore.MAX_FILE_BYTES`（256KB）、`KnowledgeStore.Hit`
- Produces:
  - `KnowledgeIndex.open(Path workspace) -> KnowledgeIndex`（`workspace/.kelly-index.db`，WAL）
  - `void reconcile()`
  - `void upsert(String relativePath)`
  - `void delete(String relativePath)`
  - `List<KnowledgeStore.Hit> search(FindQuery query, int limit)`
  - `void close()`
  - schema_version 放 `meta(key,value)`，当前 `"1"`；不一致则 `DROP` 后重建
  - 索引范围：`MEMORY.md`、`memory/*.md`、`knowledge/**/*.md`。不索引 `sessions/`、`skills/`、`AGENTS.md`
  - 查询：对每个 keyword 做 `CjkNgrams.forQuery`，FTS `AND`；`bm25` 转为 `score = max(0, 1.0 - bm25/20.0)`；日期用 `cards_meta.date` 或日记文件名过滤（复用 `FindQuery.matchesDailyFile`）
  - 单文件 > 256KB：只索引 path + title，body 空
  - `Hit.line` 用 1（行号尽力而为；/find 仍显示 snippet）

先把 `KnowledgeStore.Hit` 扩成四字段，否则本任务编不过。

- [ ] **Step 1: 扩展 `Hit`，保持旧调用能编译**

在 `KnowledgeStore.java` 把

```java
    public record Hit(String relativePath, int line, String snippet) {
    }
```

改成：

```java
    public record Hit(String relativePath, int line, String snippet, double score) {
        public Hit(String relativePath, int line, String snippet) {
            this(relativePath, line, snippet, 0);
        }
    }
```

现有 `new Hit(...)` 三参调用继续可用。`scanFile` 暂仍用三参。

- [ ] **Step 2: 写 `KnowledgeIndexTest`**

```java
package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeIndexTest {

    @TempDir Path dir;

    @Test
    void findsAliasAfterReconcile() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/meetings"));
        Files.writeString(dir.resolve("MEMORY.md"), "- ptr\n");
        Files.writeString(
                dir.resolve("knowledge/meetings/2026-09-04-客户XX-交付licence.md"),
                """
                # 会议 · 客户XX · 交付 licence
                - 别名：licence, license, 许可证, 交付许可
                - 结论：先申请再发货
                """);
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
            var hits = idx.search(FindQuery.parse("许可证", LocalDate.of(2026, 9, 4)), 12);
            assertTrue(hits.stream().anyMatch(h ->
                    h.relativePath().equals("knowledge/meetings/2026-09-04-客户XX-交付licence.md")));
        }
    }

    @Test
    void dateWindowSkipsOutOfRangeDiary() throws Exception {
        Files.createDirectories(dir.resolve("memory"));
        Files.writeString(dir.resolve("memory/2026-03-15.md"), "- 与张三敲定评审方案\n");
        Files.writeString(dir.resolve("memory/2026-08-01.md"), "- 与张三喝咖啡\n");
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
            var hits = idx.search(FindQuery.parse("半年前 张三", LocalDate.of(2026, 9, 2)), 12);
            assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("memory/2026-03-15.md")));
            assertTrue(hits.stream().noneMatch(h -> h.relativePath().equals("memory/2026-08-01.md")));
        }
    }

    @Test
    void schemaBumpRebuilds() throws Exception {
        Files.writeString(dir.resolve("MEMORY.md"), "- a\n");
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
        }
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve(".kelly-index.db"))) {
            c.createStatement().executeUpdate("UPDATE meta SET value='0' WHERE key='schema_version'");
        }
        try (KnowledgeIndex idx = KnowledgeIndex.open(dir)) {
            idx.reconcile();
            var hits = idx.search(FindQuery.parse("a", LocalDate.of(2026, 1, 1)), 12);
            assertTrue(hits.stream().anyMatch(h -> h.relativePath().equals("MEMORY.md")));
        }
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -q -Dtest=KnowledgeIndexTest test`

Expected: FAIL。

- [ ] **Step 4: 实现 `KnowledgeIndex`**

要点（必须做到，不要另起 schema）：

```sql
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS cards_meta (
  path TEXT PRIMARY KEY,
  mtime INTEGER NOT NULL,
  type TEXT, date TEXT, who TEXT, status TEXT
);
CREATE VIRTUAL TABLE IF NOT EXISTS cards_fts USING fts5(
  path, title, aliases, body, type, who, date,
  tokenize = 'unicode61'
);
```

`open`：`jdbc:sqlite:<abs>/.kelly-index.db`，`PRAGMA journal_mode=WAL`。读 `schema_version`，不是 `1` 就 drop 三张表再 create。

`reconcile`：收集磁盘目标 path 集合；meta 里有磁盘没有的 → `delete`；磁盘有的若 mtime 不同或缺失 → `upsert`。

`upsert`：`resolve` 相对路径（禁止 `..`）；读文本；`CardFields.parse`；FTS 各列写入 `CjkNgrams.forIndex(...)`；`INSERT OR REPLACE` meta；FTS 先 `DELETE FROM cards_fts WHERE path=?` 再 INSERT。

`search`：keywords 空且有日期 → 只按日记窗口 + meta.date 过滤（最多 limit 条，score=0.5）。keywords 非空 →

```sql
SELECT path, snippet(cards_fts, 3, '', '', '…', 20) AS snip, bm25(cards_fts) AS rank
FROM cards_fts WHERE cards_fts MATCH ?
ORDER BY rank LIMIT ?
```

MATCH 字符串：各 keyword 的 `forQuery` 用 `AND` 连接，词内空格改 `OR` 后整体加括号，例如 `许可证` → `(许可 OR 可证 OR 许可证)`。日期过滤在 Java 侧。

打开失败抛 `UncheckedIOException`。`close` 关 Connection。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q -Dtest=KnowledgeIndexTest test`

Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/mordor/kelly/kelsy/service/KnowledgeIndex.java src/main/java/com/mordor/kelly/kelsy/service/KnowledgeStore.java src/test/java/com/mordor/kelly/kelsy/service/KnowledgeIndexTest.java
git commit -m "$(cat <<'EOF'
feat: add SQLite FTS knowledge index

EOF
)"
```

---

### Task 5: KnowledgeStore 改走索引，保留扫描回退

**Files:**
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/KnowledgeStore.java`
- Test: `src/test/java/com/mordor/kelly/kelsy/service/KnowledgeStoreSearchTest.java`（应继续绿）
- Test: `src/test/java/com/mordor/kelly/kelsy/service/KnowledgeStoreTest.java`

**Interfaces:**
- Consumes: `KnowledgeIndex`
- Produces: `KnowledgeStore` 仍可用 `new KnowledgeStore(Path)`。新增 `KnowledgeIndex index()`；`search`/`cardsContaining` 先 `index.reconcile()` + FTS；索引抛错则走原来的 `scanFile`/`addIfContains`。

- [ ] **Step 1: 把 record 扩成带 index**

```java
public record KnowledgeStore(Path workspace, KnowledgeIndex index) {

    public KnowledgeStore(Path workspace) {
        this(openIndex(workspace.toAbsolutePath().normalize()));
    }

    private KnowledgeStore(IndexPair pair) {
        this(pair.workspace(), pair.index());
    }

    private record IndexPair(Path workspace, KnowledgeIndex index) {
    }

    private static IndexPair openIndex(Path workspace) {
        KnowledgeIndex idx;
        try {
            idx = KnowledgeIndex.open(workspace);
        } catch (RuntimeException e) {
            idx = null;
        }
        return new IndexPair(workspace, idx);
    }
}
```

若你更想保持可读性：一参构造里直接 `this(ws, tryOpen(ws))`，`tryOpen` 失败返回 null。`index()` 允许 null。

`search`：

```java
    public List<Hit> search(FindQuery query) {
        if (index != null) {
            try {
                index.reconcile();
                return index.search(query, MAX_HITS);
            } catch (RuntimeException ignored) {
            }
        }
        return scanSearch(query);
    }
```

把现在的 walk/`scanFile` 挪到 `scanSearch`。`cardsContaining` 同样：有 index 时 `search(new FindQuery(null,null,needles), MAX_HITS)` 再按 path 去重，排除 `knowledge/KNOWLEDGE.md`。

- [ ] **Step 2: 跑现有搜索测试**

Run: `mvn -q -Dtest=KnowledgeStoreSearchTest,KnowledgeStoreTest,KnowledgeStoreUserRootTest test`

Expected: PASS。`KnowledgeStoreSearchTest` 写文件后 `search` 必须能 reconcile 到。

- [ ] **Step 3: 若 `listsFourRootsOnly` 等因打开 db 失败，修 `open` 使空目录也能建库**

空 workspace 也要 `CREATE` 成功。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mordor/kelly/kelsy/service/KnowledgeStore.java src/test/java/com/mordor/kelly/kelsy/service/KnowledgeStoreSearchTest.java
git commit -m "$(cat <<'EOF'
feat: route KnowledgeStore search through FTS

EOF
)"
```

---

### Task 6: MEMORY.md 确定性压缩

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/service/MemoryCompactor.java`
- Test: `src/test/java/com/mordor/kelly/kelsy/service/MemoryCompactorTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `MemoryCompactor.LIMIT_BYTES = 4096`
  - `record InboxCard(String relativePath, String markdown)`
  - `record Result(String memoryMarkdown, List<InboxCard> inboxCards, boolean backup)`
  - `static Result compact(String memoryMarkdown, LocalDate today)`
  - `static void apply(Path workspace, Result result) throws IOException`：写 inbox 卡；若 `backup` 且不存在 `MEMORY.md.bak` 则复制当前文件；再用临时文件 replace `MEMORY.md`

压缩规则（按顺序，直到 ≤4096 或没有可删行）：

1. 保留无 `YYYY-MM-DD` 的行（常青）。
2. 保留行内日期或 `memory/YYYY-MM-DD` 在近 14 天内的行。
3. 其余若含 `knowledge/`：丢掉。
4. 其余：生成 `knowledge/inbox/YYYY-MM-DD-<slug>.md`，正文为原行（`# 收件箱` + 原文），再丢掉指针。slug 取前 20 个安全字符。
5. 同一 `knowledge/people/` 或 `projects/` 出现多行时只留最后一行。
6. 保留文件头 `# Memory` 段。

- [ ] **Step 1: 写失败测试**

```java
package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryCompactorTest {

    @Test
    void dropsOldPointerWithCardPath() {
        String fat = "# Memory\n\n" + "x".repeat(5000)
                + "\n- 2020-01-01 旧会 → knowledge/meetings/2020-01-01-旧.md\n"
                + "- 喜欢深色主题\n";
        var r = MemoryCompactor.compact(fat, LocalDate.of(2026, 9, 15));
        assertTrue(r.memoryMarkdown().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= MemoryCompactor.LIMIT_BYTES);
        assertTrue(r.memoryMarkdown().contains("深色主题"));
        assertFalse(r.memoryMarkdown().contains("2020-01-01-旧.md"));
        assertTrue(r.inboxCards().isEmpty());
    }

    @Test
    void promotesOrphanLineToInbox() {
        String fat = "# Memory\n\n" + "y".repeat(5000) + "\n- 2020-01-01 没有路径的流水\n";
        var r = MemoryCompactor.compact(fat, LocalDate.of(2026, 9, 15));
        assertEquals(1, r.inboxCards().size());
        assertTrue(r.inboxCards().get(0).relativePath().startsWith("knowledge/inbox/"));
        assertTrue(r.inboxCards().get(0).markdown().contains("没有路径的流水"));
    }

    @Test
    void underLimitUnchanged() {
        String small = "# Memory\n\n- 喜欢深色主题\n";
        var r = MemoryCompactor.compact(small, LocalDate.of(2026, 9, 15));
        assertEquals(small, r.memoryMarkdown());
        assertTrue(r.inboxCards().isEmpty());
        assertFalse(r.backup());
    }
}
```

`backup` 在 `compact` 里：仅当输入字节 > LIMIT 时为 true。`apply` 才真正写 `.bak`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=MemoryCompactorTest test`

Expected: FAIL。

- [ ] **Step 3: 实现并跑通**

Run: `mvn -q -Dtest=MemoryCompactorTest test`

Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mordor/kelly/kelsy/service/MemoryCompactor.java src/test/java/com/mordor/kelly/kelsy/service/MemoryCompactorTest.java
git commit -m "$(cat <<'EOF'
feat: compact MEMORY.md to a 4KB working set

EOF
)"
```

---

### Task 7: 启动时 reconcile + 压缩

**Files:**
- Modify: `src/main/java/com/mordor/kelly/kelsy/KelsyRuntime.java`
- Test: `src/test/java/com/mordor/kelly/kelsy/service/KnowledgeUpgradeTest.java`

**Interfaces:**
- Consumes: `KnowledgeStore`、`KnowledgeIndex`、`MemoryCompactor`
- Produces: `KelsyRuntime.open` 在两次 `WorkspaceSeeder.seed` 之后调用 `upgradeKnowledge(userRoot)`：`store.index().reconcile()`（index 为 null 则跳过）；若 `MEMORY.md` > 4096 则 `MemoryCompactor.apply`。异常只打日志，不阻断启动。

- [ ] **Step 1: 写升级测试（不启动 FX）**

```java
package com.mordor.kelly.kelsy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeUpgradeTest {

    @TempDir Path dir;

    @Test
    void oldLayoutIndexedWithoutRewritingCards() throws Exception {
        Files.createDirectories(dir.resolve("knowledge/meetings"));
        Path card = dir.resolve("knowledge/meetings/2026-09-04-客户XX-交付licence.md");
        Files.writeString(card, "- 别名：许可证\n- 结论：先申请再发货\n");
        long mtime = Files.getLastModifiedTime(card).toMillis();
        Files.writeString(dir.resolve("MEMORY.md"), "- 2026-09-04 licence → knowledge/meetings/2026-09-04-客户XX-交付licence.md\n");
        try (var store = open(dir)) {
            store.index().reconcile();
            var paths = store.search(FindQuery.parse("许可证", LocalDate.of(2026, 9, 4))).stream()
                    .map(KnowledgeStore.Hit::relativePath).collect(Collectors.toSet());
            assertTrue(paths.contains("knowledge/meetings/2026-09-04-客户XX-交付licence.md"));
            assertEquals(mtime, Files.getLastModifiedTime(card).toMillis());
            assertTrue(Files.isRegularFile(dir.resolve(".kelly-index.db")));
        }
    }

    private static KnowledgeStore open(Path dir) {
        return new KnowledgeStore(dir);
    }
}
```

注意：若 `KnowledgeStore.search` 已是 `search(FindQuery)` 两参不存在，测试里用现有单参 `search(query)`。给 `KnowledgeStore` 加 `AutoCloseable`：`close()` → `if (index != null) index.close()`。

- [ ] **Step 2: 跑测试**

Run: `mvn -q -Dtest=KnowledgeUpgradeTest test`

Expected: 先红后绿（实现 `close` + 测试用现有 search）。

- [ ] **Step 3: 在 `KelsyRuntime.open` 末尾、`return new KelsyRuntime` 之前**

```java
        upgradeKnowledge(KnowledgeStore.knowledgeRoot(paths.workspace(), username));
```

```java
    static void upgradeKnowledge(Path userRoot) {
        try (KnowledgeStore store = new KnowledgeStore(userRoot)) {
            if (store.index() != null) {
                store.index().reconcile();
            }
            if (store.memoryBytes() > MemoryCompactor.LIMIT_BYTES) {
                String raw = Files.readString(userRoot.resolve("MEMORY.md"));
                var result = MemoryCompactor.compact(raw, LocalDate.now());
                MemoryCompactor.apply(userRoot, result);
                if (store.index() != null) {
                    store.index().reconcile();
                }
            }
        } catch (Exception e) {
            Diagnostics.warn("kelsy", "knowledge upgrade skipped: %s", e.toString());
        }
    }
```

`Diagnostics` 已在 `com.mordor.kelly.common`。给 `KelsyRuntime` 加 import。单测可直接调 `KelsyRuntime.upgradeKnowledge`（包可见或 public）。

- [ ] **Step 4: 跑 `KelsyRuntimeTest` + `KnowledgeUpgradeTest`**

Run: `mvn -q -Dtest=KelsyRuntimeTest,KnowledgeUpgradeTest test`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mordor/kelly/kelsy/KelsyRuntime.java src/main/java/com/mordor/kelly/kelsy/service/KnowledgeStore.java src/test/java/com/mordor/kelly/kelsy/service/KnowledgeUpgradeTest.java
git commit -m "$(cat <<'EOF'
feat: auto-index existing workspaces on open

EOF
)"
```

---

### Task 8: AskGrounding（对话预检索）

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/service/AskGrounding.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/service/AskGroundingTest.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/LocalEvidence.java` — 加 `looksLikeRecall`

**Interfaces:**
- Consumes: `KnowledgeStore.search`、`LocalEvidence.terms`、`FindQuery`
- Produces:
  - `AskGrounding.DEFAULT_MIN_SCORE = 0.20`
  - `AskGrounding.RECALL_MIN_SCORE = 0.05`
  - `AskGrounding.MAX_ATTACH = 5`
  - `record AskGrounding(boolean searched, List<KnowledgeStore.Hit> attached, String original)`
  - `static AskGrounding prepare(KnowledgeStore store, String userText, LocalDate today)`
  - `String messageForModel()`
  - `List<String> citationPaths()`
  - `LocalEvidence.looksLikeRecall(String text)`：含 `当时|会议|纪要|决定|待办|昨天|上周|上月|本月|半年前|去年` 或 `\\d{4}-\\d{2}`

`prepare`：

1. `terms = LocalEvidence.terms(userText)` 为空 → `searched=false`，`attached=[]`，`messageForModel()` 返回原文。
2. 否则 `store.search(FindQuery.parse(userText, today))`，`searched=true`。
3. `min = looksLikeRecall ? RECALL_MIN_SCORE : DEFAULT_MIN_SCORE`。
4. 过滤 `score >= min`，取前 5。`attached` 为这些 Hit。
5. `messageForModel()` 若 attached 空则原文，否则：

```
【已检索候选】请先 read_file 下列路径，结论以卡片字段为准。不要把 MEMORY.md 里没有当成没归档。
- knowledge/people/张三.md （snippet）
- knowledge/meetings/….md （snippet）

用户原话：
<原文>
```

- [ ] **Step 1: `LocalEvidenceTest` 增加**

```java
    @Test
    void recallCues() {
        assertTrue(LocalEvidence.looksLikeRecall("当时怎么定的"));
        assertTrue(LocalEvidence.looksLikeRecall("2026-03 评审"));
        assertFalse(LocalEvidence.looksLikeRecall("你好"));
    }
```

- [ ] **Step 2: 写 `AskGroundingTest`**

用 TempDir 种人物卡，`prepare(..., "张三邮箱是什么", date)` → `searched` true，attached 含 `knowledge/people/张三.md`，`messageForModel` 含 `【已检索候选】` 与原文。

`prepare(..., "你好", date)` → searched false，message 即「你好」。

- [ ] **Step 3: 实现后跑**

Run: `mvn -q -Dtest=AskGroundingTest,LocalEvidenceTest test`

Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mordor/kelly/kelsy/service/AskGrounding.java src/main/java/com/mordor/kelly/kelsy/service/LocalEvidence.java src/test/java/com/mordor/kelly/kelsy/service/AskGroundingTest.java src/test/java/com/mordor/kelly/kelsy/service/LocalEvidenceTest.java
git commit -m "$(cat <<'EOF'
feat: ground asks with FTS candidates before the model

EOF
)"
```

---

### Task 9: Citation、斜杠命令、SKILL 文案

**Files:**
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/CitationTurn.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/SlashCommands.java`
- Modify: `src/main/resources/com/mordor/kelly/kelsy/workspace/skills/kelsy-knowledge/SKILL.md`
- Modify: `src/main/resources/com/mordor/kelly/kelsy/workspace/skills/kelsy-knowledge/references/examples.md`
- Test: `CitationTurnTest`、`SlashCommandsTest`

**Interfaces:**
- Consumes: 无新类型
- Produces: `CitationTurn.isRetrievalTool("knowledge_search") == true`；`/today` 的 outgoing **不含** `memory_search`，**含** `knowledge_search` 或「已检索候选」；SKILL 按规格 §5.5 九条改写。

- [ ] **Step 1: 改 `CitationTurn.RETRIEVAL`**

```java
            "memory_get", "memory_search", "knowledge_search", "read_file", "list_files");
```

`CitationTurnTest.writesAreNotRetrievals` 加 `assertTrue(CitationTurn.isRetrievalTool("knowledge_search"));`

- [ ] **Step 2: 改 `SlashCommands` `/today`**

```java
            case "/today" -> Result.send(
                    "请根据本轮已检索候选汇总今天已归档的工作；不够再 knowledge_search。列出条目并注明来源路径。不要 memory_search。");
```

`SlashCommandsTest.todayIgnoresTrailingText`：`assertTrue(r.outgoing().contains("knowledge_search"));` 且 `assertFalse(r.outgoing().contains("memory_search"));`

- [ ] **Step 3: 重写 SKILL「怎么查」与 examples「回忆」段**

必须出现：已检索候选优先；禁止把 MEMORY.md 没有当成没归档；`knowledge_search` 补查；零命中才说没归档；`memory_search` 不在主协议。examples 里「search：会议、交付…」改成「先看已检索候选，不够再 knowledge_search」。

- [ ] **Step 4: 跑测试**

Run: `mvn -q -Dtest=CitationTurnTest,SlashCommandsTest,WorkspaceSeederTest test`

Expected: PASS。`WorkspaceSeederTest` 仍只查文件存在。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/mordor/kelly/kelsy/service/CitationTurn.java src/main/java/com/mordor/kelly/kelsy/service/SlashCommands.java src/main/resources/com/mordor/kelly/kelsy/workspace/skills/kelsy-knowledge src/test/java/com/mordor/kelly/kelsy/service/CitationTurnTest.java src/test/java/com/mordor/kelly/kelsy/service/SlashCommandsTest.java
git commit -m "$(cat <<'EOF'
feat: switch recall protocol to FTS candidates

EOF
)"
```

---

### Task 10: 接上对话与 knowledge_search 工具

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/service/KnowledgeSearchTool.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/LocalAssistantService.java`
- Modify: `src/main/java/com/mordor/kelly/ui/chat/ChatController.java`
- Test: 扩 `AskGroundingTest` 已覆盖预检索；再在 `CitationTurnTest` 覆盖工具名即可。工具类用纯单元测 `getName()`。

**Interfaces:**
- Consumes: `AskGrounding`、`KnowledgeStore`、`io.agentscope.core.tool.AgentTool`
- Produces: `KnowledgeSearchTool.getName() == "knowledge_search"`；`callAsync` 解析 `query` 字符串（无则空），`FindQuery.parse(query, LocalDate.now())`，`store.search`，把 top 12 格式化成文本（path + snippet + score）。`LocalAssistantService.create`：`ToolsConfig deny = new ToolsConfig(); deny.setDeny(List.of("memory_search"));` 然后 `.toolsConfig(deny)`；`build()` 后 `agent.getToolkit().registerAgentTool(new KnowledgeSearchTool(KnowledgeStore.forUser(config.workspacePath(), userId)))`。

`ChatController.startAsk`：

```java
        AskGrounding grounding = AskGrounding.prepare(knowledgeStore(), outgoing, LocalDate.now());
        citations.addPaths(grounding.citationPaths());
        assistant.chat(grounding.messageForModel(), new AssistantService.ReplyHandler() {
```

`addLocalEvidence`：删掉 `cardPaths("knowledge/meetings")` / `decisions` 整目录。待办仍用 `TodoScanner` 滤 OPEN，但只把 **也出现在 `grounding.citationPaths()` 或 FTS 命中 todos 的路径** 放进去。最简单且符合规格：待办提到时，`store.search(FindQuery.parse(outgoing, today))` 的 path 里滤 `knowledge/todos/` 且 OPEN；不要 `cardPaths` 全量。

把 `AskGrounding` 保存在 `startAsk` 局部变量，`onComplete` 里的 `addLocalEvidence(outgoing)` 改为 `addLocalEvidence(outgoing, grounding)`。

- [ ] **Step 1: `KnowledgeSearchTool` 单测**

```java
    @Test
    void nameIsStable() {
        assertEquals("knowledge_search", new KnowledgeSearchTool(new KnowledgeStore(dir)).getName());
    }
```

`getParameters()` 返回 OpenAI 风格 map：`type=object`，`properties.query` string，`required=["query"]`。`callAsync` 用 `param.getInput()` / 或 `ToolCallParam` 的 public getter——实现时 `javap io.agentscope.core.tool.ToolCallParam`，从 JSON/`getArguments()` 取 `query`。返回 `Mono.just(ToolResultBlock.of(...))`；`javap ToolResultBlock` 找静态工厂，用 text 块即可。

- [ ] **Step 2: 改 `LocalAssistantService.create`**

```java
        io.agentscope.harness.agent.tools.ToolsConfig toolsConfig = new io.agentscope.harness.agent.tools.ToolsConfig();
        toolsConfig.setDeny(List.of("memory_search"));
        HarnessAgent agent = HarnessAgent.builder()
                .name("tars")
                .sysPrompt(SYS_PROMPT)
                .model(model)
                .workspace(config.workspacePath())
                .toolsConfig(toolsConfig)
                .disableShellTool()
                .disableDynamicSkills()
                .disableSubagents()
                .disableDynamicSubagents()
                .maxIters(20)
                .build();
        KnowledgeStore store = KnowledgeStore.forUser(config.workspacePath(), userId);
        agent.getToolkit().registerAgentTool(new KnowledgeSearchTool(store));
```

- [ ] **Step 3: 改 ChatController 如上。跑**

Run: `mvn -q -Dtest=AskGroundingTest,CitationTurnTest,ChatControllerKelsyTest,KnowledgeSearchToolTest test`

Expected: PASS。`ChatControllerKelsyTest` 不应因预检索挂掉（mock assistant 只记 asked 文本——若测试断言 asked 等于原文，把断言改为 `asked` 等于 `messageForModel()` 或测试用「你好」避免附候选）。

先读 `ChatControllerKelsyTest` 里 `asked` 的断言：若等于用户原文，预检索测试输入改用无词寒暄，或断言 `asked.get(0).contains(原文)`。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/mordor/kelly/kelsy/service/KnowledgeSearchTool.java src/main/java/com/mordor/kelly/kelsy/service/LocalAssistantService.java src/main/java/com/mordor/kelly/ui/chat/ChatController.java src/test/java/com/mordor/kelly/kelsy/service/KnowledgeSearchToolTest.java src/test/java/com/mordor/kelly/ui/chat/ChatControllerKelsyTest.java
git commit -m "$(cat <<'EOF'
feat: ground chat asks and register knowledge_search

EOF
)"
```

---

### Task 11: 全量回归与规格对照

**Files:** 无新生产代码，除非测试失败要修。

- [ ] **Step 1: 跑 kelsy 包全部测试**

Run: `mvn -q -Dtest=com.mordor.kelly.kelsy.**.*Test test`

Expected: BUILD SUCCESS。

- [ ] **Step 2: 跑 UI 相关**

Run: `mvn -q -Dtest=ChatControllerKelsyTest,ChatControllerScrollSendTest,ChatControllerImageTest test`

Expected: BUILD SUCCESS。

- [ ] **Step 3: 规格自检（对照 `docs/superpowers/specs/2026-09-15-long-term-memory-design.md`）**

确认每条都已落地：

| 规格 | 对应实现 |
|---|---|
| 导读 / §5.4 预检索 | `AskGrounding` + `ChatController.startAsk` |
| SQLite 唯一搜索引擎 | `KnowledgeStore.search` / `cardsContaining` / `KnowledgeSearchTool` |
| 关掉 memory_search | `ToolsConfig.setDeny` |
| L0 4KB | `MemoryCompactor` + `KelsyRuntime.upgradeKnowledge` |
| 自动升级 | `KnowledgeUpgradeTest` + open 钩子 |
| SKILL | 资源文件 writeAlways |
| 不改卡片格式 | 无迁移改写 meetings/decisions |
| 回退扫描 | `KnowledgeStore.scanSearch` |

缺哪条就在本任务修，不要新开范围。

- [ ] **Step 4: Commit（仅当本任务有代码改动）**

```bash
git add -u
git commit -m "$(cat <<'EOF'
test: cover long-term memory upgrade path

EOF
)"
```

---

## Self-review (spec coverage)

- 导读四种对话：Task 8 + 10
- FTS schema / CJK / 别名 / 日期窗：Task 2–5
- MEMORY 压缩与 inbox：Task 6–7
- 启动自动升级、不改卡片 mtime：Task 7
- 入口表（/find、ASK、knowledge_search）：Task 5、8、10
- memory_search 非主路：Task 9–10
- 索引失败回退：Task 5
- SKILL /today：Task 9
- 会话 20 轮：Harness 无稳定「只留 20 轮」公开 API；本计划不改 `sessionId=main`，靠 L0 封顶 + 预检索满足「工作集不膨胀」。不在本计划里猜 `maxContextTokens`。

无 TBD。类型名以 Task 2–8 的 Produces 为准。
