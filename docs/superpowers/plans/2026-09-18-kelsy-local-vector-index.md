# Kelsy Local Vector Index Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add fully local, packaged ONNX document/query embeddings, resumable SQLite persistence, and an exact in-memory vector index for at most 20,000 active chunks.

**Architecture:** A process-wide `OnnxEmbeddingRuntime` loads one bundled multilingual model lazily. Per-user `EmbeddingIndexer` jobs create normalized vectors in SQLite; `ExactVectorIndex` loads them into an immutable contiguous snapshot plus a small delta. FTS remains immediately usable and every vector failure degrades to lexical-only retrieval.

**Tech Stack:** Java 21, ONNX Runtime Java 1.28.0, DJL Hugging Face tokenizers 0.36.0 when selected model compatibility is proven, SQLite BLOB, Jackson 2.21.1, JUnit 5.10.2

**Spec:** `docs/superpowers/specs/2026-09-18-kelsy-hybrid-retrieval-design.md`

## Global Constraints

- Complete `docs/superpowers/plans/2026-09-18-kelsy-lexical-retrieval.md` first.
- Use only local ONNX inference; do not add remote embedding interfaces, URLs, API keys, downloads at runtime, or network calls.
- The model and tokenizer ship with each platform package and work while fully offline.
- The model-selection gate must choose exactly one logical model before runtime code is merged.
- Query and document vectors use the same fingerprint, tokenizer, pooling, prefix, and L2 normalization.
- Existing Markdown is never rewritten by embedding migration or failure handling.
- Old vectors are invalidated immediately after a card changes.
- Initial hybrid use waits for 100% coverage; during build, search remains lexical-only.
- Support is guaranteed through 20,000 active chunks; do not silently truncate files above the bound.
- JavaFX threads never initialize ONNX, tokenize text, infer vectors, or scan a vector matrix.

---

### Task 1: Run and record the local model-selection gate

**Files:**
- Create: `tools/embedding-bench/README.md`
- Create: `tools/embedding-bench/evaluate.py`
- Create: `docs/superpowers/research/2026-09-18-kelsy-embedding-model-selection.md`
- Consume: `src/test/resources/com/mordor/kelly/kelsy/retrieval/golden/`

**Interfaces:**
- Produces: one selected logical model ID, license, dimension, max tokens, prefixes, pooling, model artifact, tokenizer artifact, and per-platform SHA-256
- Blocks: every later task that embeds a real model

- [ ] **Step 1: Add a deterministic benchmark harness**

The script reads the frozen golden corpus and evaluates these two verified candidates:

```python
CANDIDATES = [
    "intfloat/multilingual-e5-small",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
]
```

Record Recall@5 for Chinese, mixed Chinese/English, synonym, and semantic-only partitions. Record model size, max tokens, dimensions, license, query P50/P95/P99, and batch throughput.

- [ ] **Step 2: Add a failing output-contract test**

The script exits nonzero unless its JSON report contains:

```json
{
  "winner": {
    "modelId": "non-empty",
    "dimensions": 384,
    "license": "non-empty",
    "queryP95Ms": 49.0,
    "semanticRecallGainPoints": 15.0
  }
}
```

Values shown are acceptance boundaries, not preselected measurements.

- [ ] **Step 3: Export or obtain ONNX candidates and run on target hardware**

Use model-authorized artifacts or export with Optimum outside application runtime. Evaluate fp32 and supported int8 variants. Do not accept an artifact whose license or conversion source cannot be recorded.

- [ ] **Step 4: Apply hard gates**

Reject a candidate if:

- redistribution is not permitted;
- dimensions are not the declared fixed value;
- tokenizer cannot run offline on all target platforms;
- semantic-only Recall@5 gain is below 15 percentage points;
- ASK Precision@5 is below 90%;
- any forbidden path is ASK-attachable;
- query embedding P95 is at least 50ms on the agreed reference device.

If no candidate passes, stop this plan and retain the lexical-only release. Do not weaken the frozen gate without user approval.

- [ ] **Step 5: Write the selection record**

The research document must contain candidate versions, source URLs, licenses, conversion commands, SHA-256 values, measured metrics, target CPU, and the winner rationale.

- [ ] **Step 6: Commit the reproducible selection tooling and record**

```bash
git add tools/embedding-bench \
        docs/superpowers/research/2026-09-18-kelsy-embedding-model-selection.md
git commit -m "docs: select kelsy local embedding model"
```

---

### Task 2: Package and validate the selected model

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/java/module-info.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/ModelManifest.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/ModelFingerprint.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/ModelPackage.java`
- Create: `src/main/resources/com/mordor/kelly/kelsy/retrieval/models/embedding/manifest.json`
- Add through the approved binary distribution mechanism: `model.onnx`, `tokenizer.json`, `LICENSE`
- Test: `src/test/java/com/mordor/kelly/kelsy/retrieval/embedding/ModelPackageTest.java`

**Interfaces:**
- Produces: `ModelPackage.open(Path installRoot)`
- Produces: `ModelManifest.validate()`
- Produces: `ModelFingerprint value`

- [ ] **Step 1: Write failing manifest and checksum tests**

```java
@Test
void rejectsModelWithWrongSha() {
    ModelPackage pkg = copyFixturePackage();
    Files.writeString(pkg.modelPath(), "corrupt");
    assertThrows(ModelPackageException.class, pkg::validate);
}

@Test
void fingerprintChangesWhenTextSchemaChanges() {
    assertNotEquals(manifest(1).fingerprint(), manifest(2).fingerprint());
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=ModelPackageTest test`

Expected: compilation FAIL because model package types do not exist.

- [ ] **Step 3: Add runtime dependencies**

Add:

```xml
<dependency>
  <groupId>com.microsoft.onnxruntime</groupId>
  <artifactId>onnxruntime</artifactId>
  <version>1.28.0</version>
</dependency>
<dependency>
  <groupId>ai.djl.huggingface</groupId>
  <artifactId>tokenizers</artifactId>
  <version>0.36.0</version>
</dependency>
```

Retain the tokenizer dependency only if Task 1 proves compatibility with the winning model and all platform packages. Confirm automatic module names using `jar --describe-module` before editing `module-info.java`.

- [ ] **Step 4: Stage large model resources outside the main application jar**

Extend the existing `jpackage.input` staging so the installed layout contains `models/embedding`. The runtime resolves this filesystem path; it must not depend on a development checkout or writable classpath resource.

- [ ] **Step 5: Implement manifest parsing, SHA validation, and fingerprinting**

Use Jackson with an explicitly opened package. Validate dimension, maxTokens, prefixes, pooling, artifact names, and checksum before loading native runtime.

- [ ] **Step 6: Run tests and package smoke check**

Run:

```bash
mvn -q -Dtest=ModelPackageTest test
mvn -q -DskipTests package
```

Expected: PASS and staged model files present under `target/jpackage-input`.

- [ ] **Step 7: Commit packaging**

```bash
git add pom.xml src/main/java/module-info.java \
        src/main/java/com/mordor/kelly/kelsy/retrieval/embedding \
        src/main/resources/com/mordor/kelly/kelsy/retrieval/models
git commit -m "build: package kelsy local embedding model"
```

---

### Task 3: Implement tokenizer and ONNX inference

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingRuntime.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingHealth.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingTokenizer.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/OnnxEmbeddingRuntime.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/embedding/FakeEmbeddingRuntime.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/embedding/OnnxEmbeddingRuntimeTest.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/embedding/OnnxEmbeddingRuntimeModelTest.java`

**Interfaces:**
- Produces: spec §8.3 `EmbeddingRuntime`
- `embedQuery` applies query prefix; `embedDocuments` applies passage prefix
- Output is finite, fixed dimension, and L2-normalized

- [ ] **Step 1: Write fake-runtime contract and invalid-output tests**

```java
@Test
void rejectsWrongDimensionAndNan() {
    assertThrows(EmbeddingException.class,
            () -> OnnxEmbeddingRuntime.validate(new float[]{1, Float.NaN}, 384));
    assertThrows(EmbeddingException.class,
            () -> OnnxEmbeddingRuntime.validate(new float[383], 384));
}
```

- [ ] **Step 2: Run the unit test and verify failure**

Run: `mvn -q -Dtest=OnnxEmbeddingRuntimeTest test`

Expected: compilation FAIL.

- [ ] **Step 3: Implement tokenizer tensors and model-specific pooling**

Create `input_ids` and `attention_mask` exactly as the selected model requires. Implement only the pooling declared in the frozen manifest. Apply attention-mask-aware pooling where selected, then L2 normalize.

- [ ] **Step 4: Implement lazy process-wide session lifecycle**

State transitions are `UNINITIALIZED → VALIDATING → LOADING → READY`, with terminal `DEGRADED` until explicit retry or application restart. Never retry on every query.

- [ ] **Step 5: Add real-model parity tests**

Use fixed text, token IDs, and reference vectors recorded by Task 1. Assert token IDs exactly and cosine with reference vectors within the recorded numeric tolerance.

- [ ] **Step 6: Run unit and model-tagged tests**

Run:

```bash
mvn -q -Dtest=OnnxEmbeddingRuntimeTest test
mvn -q -Dgroups=model -Dtest=OnnxEmbeddingRuntimeModelTest test
```

Expected: PASS on every target platform.

- [ ] **Step 7: Commit inference runtime**

```bash
git add src/main/java/com/mordor/kelly/kelsy/retrieval/embedding \
        src/test/java/com/mordor/kelly/kelsy/retrieval/embedding
git commit -m "feat: run kelsy embeddings with local onnx"
```

---

### Task 4: Build canonical text and token-aware chunks

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingTextBuilder.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingChunk.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingChunker.java`
- Test: `src/test/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingChunkerTest.java`

**Interfaces:**
- Consumes: `CardFields`, Markdown body, tokenizer, manifest
- Produces: `List<EmbeddingChunk> chunks(String path,String markdown)`
- Every chunk hash includes model fingerprint, text schema version, and canonical text

- [ ] **Step 1: Write failing canonical-text and overlap tests**

```java
@Test
void repeatsMetadataAndRespectsTokenBudget() {
    List<EmbeddingChunk> chunks = chunker.chunks("knowledge/people/zhang.md", longCard());
    assertTrue(chunks.size() > 1);
    assertTrue(chunks.stream().allMatch(c -> c.canonicalText().contains("title: 张三")));
    assertTrue(chunks.stream().allMatch(c -> tokenizer.count(c.canonicalText()) <= 512));
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=EmbeddingChunkerTest test`

Expected: compilation FAIL.

- [ ] **Step 3: Implement canonical fields and deterministic Markdown boundaries**

Include title, aliases, type, who, status, and body. Exclude absolute path and mtime. Split by heading, paragraph, list/table row, sentence, then token hard limit.

- [ ] **Step 4: Implement 320-token body budget and 48-token overlap**

Reserve up to 96 tokens for metadata and the remainder for prefix/special-token safety. Reject a manifest whose maxTokens cannot accommodate the fixed header and at least one body token.

- [ ] **Step 5: Run tests**

Run: `mvn -q -Dtest=EmbeddingChunkerTest,CardFieldsTest test`

Expected: PASS with deterministic chunk numbers and hashes.

- [ ] **Step 6: Commit chunking**

```bash
git add src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingTextBuilder.java \
        src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingChunk.java \
        src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingChunker.java \
        src/test/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingChunkerTest.java
git commit -m "feat: chunk kelsy cards for local embeddings"
```

---

### Task 5: Persist vectors and resumable jobs

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/VectorBlobCodec.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingSchema.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingStore.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingJob.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingJobState.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/embedding/VectorBlobCodecTest.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingStoreTest.java`

**Interfaces:**
- Produces: `EmbeddingStore.replacePathVectors(...)` as one transaction
- Produces: `EmbeddingStore.enqueueOrReplace(path,hash,fingerprint,priority)`
- Produces: `EmbeddingStore.recoverRunningJobs()`

- [ ] **Step 1: Write failing BLOB validation and atomic replacement tests**

```java
@Test
void roundTripsLittleEndianNormalizedVector() {
    float[] expected = normalizedVector(384);
    assertArrayEquals(expected, codec.decode(codec.encode(expected), 384), 1e-6f);
}

@Test
void replacementNeverExposesHalfACard() {
    store.replacePathVectors(PATH, FP, threeChunks());
    assertEquals(3, store.loadPath(PATH, FP).size());
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=VectorBlobCodecTest,EmbeddingStoreTest test`

Expected: compilation FAIL.

- [ ] **Step 3: Create embedding tables independently from FTS schema version**

Use the exact schema from spec §10. Keep `embedding_schema_version` separate so embedding rebuilds do not destroy a healthy FTS index.

- [ ] **Step 4: Implement job recovery and retry metadata**

On open, convert RUNNING to PENDING. Preserve attempts and next-retry time. Corrupt BLOBs are deleted and re-enqueued.

- [ ] **Step 5: Run tests**

Run: `mvn -q -Dtest=VectorBlobCodecTest,EmbeddingStoreTest test`

Expected: PASS.

- [ ] **Step 6: Commit storage**

```bash
git add src/main/java/com/mordor/kelly/kelsy/retrieval/embedding \
        src/test/java/com/mordor/kelly/kelsy/retrieval/embedding
git commit -m "feat: persist kelsy embeddings in sqlite"
```

---

### Task 6: Implement background indexing and stale-result protection

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingJobQueue.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingIndexer.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/KnowledgeStore.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/IndexingAgentTool.java`
- Test: `src/test/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingIndexerTest.java`
- Modify: `src/test/java/com/mordor/kelly/kelsy/service/IndexingAgentToolTest.java`

**Interfaces:**
- Produces: `EmbeddingIndexer.enqueue(path,priority)`
- Produces: `EmbeddingIndexer.invalidateAndEnqueue(path)`
- Query priority exceeds recent writes, initial migration, and model rebuild

- [ ] **Step 1: Write failing stale-hash and restart tests**

```java
@Test
void discardsInferenceWhenFileChangesBeforeCommit() {
    indexer.enqueue(PATH, RECENT_WRITE);
    runtime.blockNextDocumentBatch();
    rewriteCard(PATH);
    runtime.release();
    assertNotEquals(oldHash(), store.activeHash(PATH));
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=EmbeddingIndexerTest test`

Expected: compilation FAIL.

- [ ] **Step 3: Implement one low-priority document batch at a time**

Use batches of 8–16 chunks. Query embedding work is scheduled ahead of the next document batch. Do not block successful file writes on inference.

- [ ] **Step 4: Invalidate old vectors before enqueue**

`KnowledgeStore.upsert` first commits FTS, then removes the path from the active vector view, then creates/replaces the job. A failed embedding leaves the card searchable by FTS only.

- [ ] **Step 5: Add three-attempt retry and explicit FAILED**

Model-global errors transition runtime to DEGRADED and pause all jobs; per-file errors use bounded retry.

- [ ] **Step 6: Run tests**

Run: `mvn -q -Dtest=EmbeddingIndexerTest,IndexingAgentToolTest test`

Expected: PASS.

- [ ] **Step 7: Commit the indexer**

```bash
git add src/main/java/com/mordor/kelly/kelsy/retrieval/embedding \
        src/main/java/com/mordor/kelly/kelsy/service/KnowledgeStore.java \
        src/main/java/com/mordor/kelly/kelsy/service/IndexingAgentTool.java \
        src/test/java/com/mordor/kelly/kelsy/retrieval/embedding/EmbeddingIndexerTest.java \
        src/test/java/com/mordor/kelly/kelsy/service/IndexingAgentToolTest.java
git commit -m "feat: index kelsy embeddings after card writes"
```

---

### Task 7: Build the exact in-memory vector index

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/vector/VectorRef.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/vector/VectorHit.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/vector/VectorSnapshot.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/vector/VectorDelta.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/vector/ExactVectorIndex.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/vector/VectorIndexBuilder.java`
- Test: `src/test/java/com/mordor/kelly/kelsy/retrieval/vector/ExactVectorIndexTest.java`

**Interfaces:**
- Produces: `List<VectorHit> search(float[] normalizedQuery,int limit)`
- Produces: `void applyDelta(VectorDelta)`
- Produces: `VectorSnapshot snapshot()`
- One path appears once using its best chunk

- [ ] **Step 1: Write failing exact-search and deduplication tests**

```java
@Test
void returnsBestChunkOncePerPath() {
    index.load(snapshotWithTwoChunksForSamePath());
    List<VectorHit> hits = index.search(query(), 10);
    assertEquals(1, hits.stream().filter(h -> h.path().equals(PATH)).count());
    assertEquals(1, hits.get(0).chunkNo());
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=ExactVectorIndexTest test`

Expected: compilation FAIL.

- [ ] **Step 3: Implement contiguous row-major float storage**

Compute dot products without allocating per-vector arrays. Maintain a bounded top-k heap and stable path tie-break.

- [ ] **Step 4: Implement immutable base plus delta and tombstones**

Queries read the base snapshot lock-free, skip tombstoned paths, and scan delta. Rebuild off-thread at 5% of base or 1,000 chunks, then publish through `AtomicReference`.

- [ ] **Step 5: Add over-capacity state**

At more than 20,000 active chunks, continue exact search but expose `OVER_CAPACITY` diagnostics. Do not truncate vectors.

- [ ] **Step 6: Run tests**

Run: `mvn -q -Dtest=ExactVectorIndexTest test`

Expected: PASS.

- [ ] **Step 7: Commit vector search**

```bash
git add src/main/java/com/mordor/kelly/kelsy/retrieval/vector \
        src/test/java/com/mordor/kelly/kelsy/retrieval/vector
git commit -m "feat: add exact local vector search"
```

---

### Task 8: Add lifecycle, model upgrade, and performance gates

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/RetrievalLifecycle.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/SemanticSearchSwitch.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/KelsyRuntime.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/RetrievalLifecycleTest.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/perf/ExactVectorIndexPerfTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Produces: lifecycle state `LEXICAL_ONLY_BUILDING`, `HYBRID_READY`, `LEXICAL_ONLY_DEGRADED`
- Produces: `-Dkelly.semanticSearch=false`
- Produces: dual-fingerprint atomic activation

- [ ] **Step 1: Write failing initial-build and dual-version tests**

```java
@Test
void doesNotActivatePartialInitialBuild() {
    lifecycle.reportCoverage(FP, 99, 100);
    assertEquals(RetrievalState.LEXICAL_ONLY_BUILDING, lifecycle.state());
    lifecycle.reportCoverage(FP, 100, 100);
    assertEquals(RetrievalState.HYBRID_READY, lifecycle.state());
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=RetrievalLifecycleTest test`

Expected: compilation FAIL.

- [ ] **Step 3: Integrate startup without blocking JavaFX**

`KelsyRuntime.open` creates the lexical store immediately, then starts manifest validation, job recovery, and initial vector build on retrieval executors. Remove synchronous vector work from constructors.

- [ ] **Step 4: Implement semantic safe mode and dual fingerprint**

When the property is false, do not load ONNX or execute jobs. During model upgrade, keep the old READY fingerprint active until the new one reaches 100%, then atomically replace the vector snapshot.

- [ ] **Step 5: Add model and performance Maven groups**

Default tests exclude `slow`, `model`, and `perf`. Release CI explicitly runs model tests by target platform and performance tests on the reference machine.

- [ ] **Step 6: Run the 20K performance gate**

Run:

```bash
mvn -q -Dgroups=perf -Dtest=ExactVectorIndexPerfTest test
```

Expected: warmed 20K exact scan P95 below 30ms; test fails on percentile breach rather than printing only.

- [ ] **Step 7: Run all vector tests**

Run:

```bash
mvn -q -Dtest='com.mordor.kelly.kelsy.retrieval.embedding.**.*Test,com.mordor.kelly.kelsy.retrieval.vector.**.*Test,RetrievalLifecycleTest' test
```

Expected: PASS.

- [ ] **Step 8: Commit lifecycle and gates**

```bash
git add src/main/java/com/mordor/kelly/kelsy/retrieval \
        src/main/java/com/mordor/kelly/kelsy/KelsyRuntime.java \
        src/test/java/com/mordor/kelly/kelsy/retrieval \
        pom.xml
git commit -m "feat: manage kelsy local vector lifecycle"
```

## Plan Completion Gate

- One model is selected through recorded quality, license, tokenizer, and platform evidence.
- Model loading and inference are fully local and offline.
- Card writes invalidate stale vectors before background replacement.
- SQLite jobs recover after restart and never expose partial-card vectors.
- Exact search deduplicates paths and meets the warmed 20K P95 target.
- Initial partial coverage remains lexical-only.
- Model upgrades switch fingerprints atomically.
- `-Dkelly.semanticSearch=false` avoids every ONNX and embedding action.
