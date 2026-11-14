# Kelsy review fixes

## Batch 1: review items 1–4

Branch: `codex/kelsy-review-1-4`, based on `3f87de6`.

- Normalize and tokenize each Chinese/other-script run independently. Punctuation cannot silently remove a constraint.
- Intersect keyword groups at document level, retaining ranked chunks for evidence locations.
- Expose kind/tags/date filters to knowledge_search. ASK prioritizes typed meetings/decisions while retaining lexical recall.
- Stream SQLite snapshots and restored entries. Producer/consumer share a 4GiB entry / 8GiB archive budget, with a separately bounded manifest and integrity checks.

Regression cases cover mixed-script order, Chinese punctuation, cross-chunk conjunction, structured meeting intent/filtering, and a 65MiB database backup/restore.

Validation: independent Maven output directory (the IDE also writes target/classes). The full 345-test run had one unrelated asynchronous chat-scroll failure; that case and all related retrieval/storage/controller tests passed when rerun together. No product change was made to suppress that failure.

Only Linux/Xvfb was executed locally; Windows/macOS validation is not implied.

## Batch 2: review items 5–10

Branch: `codex/kelsy-review-5-10`, based directly on batch 1 commit `70c778f`.

- (5) Full filesystem enumeration and file reads/hashes no longer hold the JDBC/search monitor. Per-file locks serialize competing snapshots; database mutations remain serialized. A blocked-reader test checks that search and status still complete.
- (6) Task sorting has matching expression indexes, added compatibly to existing databases. Page sources are fetched in one query. Reminders read only required columns, persist pages of at most 50 tasks, and drain subsequent pages after acknowledgement. Pre-upgrade oversized pending payloads are split without losing tasks.
- (7) Undated working-set overflow is archived intact with a short pointer. Archive names include a content hash. Partial completion can be retried without overwriting an unrelated article; combined archives update the working-set pointer.
- (8) Verified sources come from complete successful knowledge_get/todo_get/list envelopes, not arguments, directory listings, generated prose, or search candidates. Tool-call IDs separate interleaved streams. New turns clear old sources. Evidence keeps article UUID/path/hash or task UUID/version; reopening a changed source reports a conflict.
- (9) General requests continue when search is unavailable. A known path can retrieve/revalidate an article while the index is unavailable. Saved articles accurately report indexReady=false. Task operations remain available independently.
- (10) Article path updates distinguish same-file aliases/case-only renames from conflicting UUID copies. Watcher failure is visible as STALE, registration retries, and periodic reconciliation continues. Watch registration uses a set and is limited to managed directories. Added Linux/Windows/macOS CI and an opt-in platform performance probe.

Final Linux/Xvfb verification: **352 tests, 0 failures, 0 errors, 0 skipped**, using independent Maven output to avoid IDE-generated classes. Asynchronous image tests now wait for toolkit startup, and the scroll-send test waits for the JavaFX callback as well as disk completion.

### Local performance sample

Same probe and JVM heap limit (512MiB), Linux/JDK 21.0.13, 3,000 approximately 2KB articles and 3,000 tasks. Baseline loads KnowledgeIndex and TodoRepository compiled from batch 1, with the same probe/dependencies. Measurements are individual local runs, not a statistical or cross-platform guarantee.

| Measurement | Batch 1 | Batch 2 |
|---|---:|---:|
| Initial index | 2924ms | 3197ms |
| Hot search P95 (50 samples) | 57.57ms | 38.46ms |
| Hot search P99 | 58.68ms | 40.42ms |
| Maximum query during reconciliation | 392.15ms | 38.79ms |
| Task list P95 | 3.09ms | 1.94ms |

Cold indexing increased approximately 9% in this single sample; repeated cold runs and larger corpora are required before declaring a cold-start regression or a no-regression guarantee. Hot-query and reconciliation contention improved in this sample. Windows/macOS tests have not been executed locally, and the new CI workflow has not been dispatched here.

To reproduce on a normal checkout:

```bash
mvn test-compile exec:java \
  -Dexec.mainClass=com.mordor.kelly.kelsy.service.KnowledgePerformanceProbe \
  -Dexec.classpathScope=test \
  -Dkelly.benchmark.documents=3000 \
  -Dkelly.benchmark.todos=3000 \
  -Dkelly.benchmark.report=target/kelsy-benchmark.json
```

The platform workflow runs correctness tests on pushes/PRs and records the probe on manual workflow_dispatch. It does not replace packaged-app testing, a labelled answer-quality evaluation set, or equivalent-hardware performance acceptance. No historical Markdown-task import was added in these two batches. The independent media-path, receive-size, and password-derivation risks remain outside items 1–10 of this review.
