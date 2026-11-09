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
