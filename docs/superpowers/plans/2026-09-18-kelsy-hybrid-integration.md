# Kelsy Hybrid Retrieval Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fuse original BM25, controlled-synonym BM25, and local vectors into one precision-first retrieval service, then migrate ASK, `/find`, `knowledge_search`, lifecycle, and JavaFX status UI to it.

**Architecture:** `HybridRetrievalService` runs lexical and vector retrieval concurrently under a 200ms deadline, applies three-way weighted RRF, probes deterministic evidence, and classifies hits. Product entry points consume one `SearchResponse` contract with request IDs and explicit degraded states. SQLite reads and writes are isolated to owned executors and no search path performs reconciliation.

**Tech Stack:** Java 21, JavaFX 21.0.2, SQLite WAL, `CompletableFuture`, Reactor `Mono`, JUnit 5.10.2

**Spec:** `docs/superpowers/specs/2026-09-18-kelsy-hybrid-retrieval-design.md`

## Global Constraints

- Complete both lexical and local-vector plans before enabling `HYBRID_READY`.
- Use weighted RRF: original lexical `1.0`, controlled synonym `0.7`, vector `1.0`, with `k=60`.
- ASK attaches only `STRONG`, at most five; pure vector and pure synonym hits cannot independently become `STRONG`.
- `/find` and `knowledge_search` may show `WEAK`, clearly separated from `STRONG`.
- A degraded or unavailable search is not a successful empty search and cannot be phrased as “not archived.”
- Query deadline is 200ms; vector failure/timeout falls back to lexical results.
- JavaFX updates happen through `Platform.runLater` or an injected FX dispatcher after futures complete.
- Search hot paths never call `reconcile`, `reconcileIfStale`, `Files.walk`, ONNX initialization, or full BLOB loading.
- User switches and newer requests invalidate older UI callbacks.
- Do not log full queries, card content, tokenizer input, or vectors.

---

### Task 1: Implement three-way RRF and final relevance policy

**Files:**
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/fusion/RrfWeights.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/fusion/HybridCandidate.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/fusion/HybridReranker.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/retrieval/fusion/RelevancePolicy.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/fusion/HybridRerankerTest.java`
- Modify: `src/test/java/com/mordor/kelly/kelsy/retrieval/fusion/RelevancePolicyTest.java`

**Interfaces:**
- Consumes: original `LexicalCandidate`, synonym `LexicalCandidate`, `VectorHit`, `LexicalEvidence`
- Produces: `List<SearchHit> merge(..., int limit)`
- Produces: `Relevance classify(HybridCandidate candidate)`

- [ ] **Step 1: Write failing weighted-RRF tests**

```java
@Test
void appliesThreeIndependentRanks() {
    double score = HybridReranker.rrf(1, 1, 1, new RrfWeights(1.0, 0.7, 1.0, 60));
    assertEquals(2.7 / 61.0, score, 1e-12);
}

@Test
void synonymOnlyDoesNotBecomeStrong() {
    HybridCandidate candidate = synonymOnlyCandidate(1);
    assertEquals(Relevance.WEAK, policy.classify(candidate));
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=HybridRerankerTest,RelevancePolicyTest test`

Expected: compilation FAIL because hybrid types do not exist.

- [ ] **Step 3: Implement candidate union and preliminary top 100**

Union at most 50 original, 50 synonym, and 50 vector paths. Compute preliminary RRF, keep top 100, then attach detailed lexical evidence.

- [ ] **Step 4: Implement all STRONG, WEAK, and REJECTED rules**

Use spec §14 exactly. Add stable reason enums such as `ORIGINAL_EXACT_ALIAS`, `ENTITY_ANCHORS_MATCHED`, `SYNONYM_VECTOR_CORROBORATED`, `VECTOR_ONLY`, and `DATE_REJECTED`.

- [ ] **Step 5: Implement deterministic final ordering**

Sort by relevance, entity coverage, original high-value-field evidence, original coverage, synonym coverage, RRF, original BM25, then path.

- [ ] **Step 6: Run tests**

Run: `mvn -q -Dtest=HybridRerankerTest,RelevancePolicyTest test`

Expected: PASS.

- [ ] **Step 7: Commit fusion**

```bash
git add src/main/java/com/mordor/kelly/kelsy/retrieval/fusion \
        src/test/java/com/mordor/kelly/kelsy/retrieval/fusion
git commit -m "feat: fuse kelsy lexical and vector rankings"
```

---

### Task 2: Isolate SQLite reads and writes

**Files:**
- Modify: `src/main/java/com/mordor/kelly/kelsy/retrieval/RetrievalExecutors.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/KnowledgeIndex.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/KnowledgeStore.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/RetrievalConcurrencyTest.java`
- Modify: `src/test/java/com/mordor/kelly/kelsy/service/KnowledgeIndexTest.java`

**Interfaces:**
- One reader connection is owned by one named search executor
- One writer connection is owned by one named writer executor
- Produces: `CompletableFuture<T> submitRead(Callable<T>)`
- Produces: `CompletableFuture<T> submitWrite(Callable<T>)`
- Produces: `CompletableFuture<Void> KnowledgeStore.upsertAsync(String relativePath)`

- [ ] **Step 1: Write failing ownership and concurrent search/upsert tests**

```java
@Test
void concurrentSearchAndUpsertUseDifferentOwnedConnections() {
    CompletableFuture<SearchResponse> search = store.searchAsync(request("张三"));
    CompletableFuture<Void> write = store.upsertAsync("knowledge/people/zhang.md");
    CompletableFuture.allOf(search, write).join();
    assertEquals(0, diagnostics.crossThreadConnectionUses());
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=RetrievalConcurrencyTest test`

Expected: FAIL because current `KnowledgeIndex` shares one connection.

- [ ] **Step 3: Open reader and writer connections with WAL**

Set WAL and busy timeout on both. Ensure each connection is only accessed by its owning executor and closed after its queue drains.

- [ ] **Step 4: Move reconcile to the writer queue**

Startup/background reconciliation may run only on the writer executor. Delete reconcile calls from `searchAsync`, ASK, `/find`, and `knowledge_search`.

- [ ] **Step 5: Ensure embedding persistence uses the same writer queue**

Do not allow `EmbeddingIndexer` to hold or call the writer JDBC connection directly.

- [ ] **Step 6: Run concurrency and index tests**

Run: `mvn -q -Dtest=RetrievalConcurrencyTest,KnowledgeIndexTest,KnowledgeStoreSearchTest test`

Expected: PASS.

- [ ] **Step 7: Commit connection isolation**

```bash
git add src/main/java/com/mordor/kelly/kelsy/retrieval/RetrievalExecutors.java \
        src/main/java/com/mordor/kelly/kelsy/service/KnowledgeIndex.java \
        src/main/java/com/mordor/kelly/kelsy/service/KnowledgeStore.java \
        src/test/java/com/mordor/kelly/kelsy/retrieval/RetrievalConcurrencyTest.java \
        src/test/java/com/mordor/kelly/kelsy/service/KnowledgeIndexTest.java
git commit -m "fix: isolate kelsy sqlite reader and writer"
```

---

### Task 3: Orchestrate concurrent hybrid search and degradation

**Files:**
- Modify: `src/main/java/com/mordor/kelly/kelsy/retrieval/HybridRetrievalService.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/retrieval/RetrievalTimeouts.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/HybridRetrievalIntegrationTest.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/RetrievalFailureTest.java`

**Interfaces:**
- Consumes: planner, lexical retriever, embedding runtime, exact vector index, reranker
- Produces: `CompletableFuture<SearchResponse> searchAsync(SearchRequest request)`
- Produces explicit `HYBRID_READY`, `LEXICAL_ONLY_BUILDING`, `LEXICAL_ONLY_DEGRADED`, `VECTOR_ONLY_DEGRADED`, `UNAVAILABLE`

- [ ] **Step 1: Write failing ready, timeout, and failure-matrix tests**

```java
@Test
void vectorTimeoutReturnsStrongLexicalHits() {
    runtime.neverCompleteQuery();
    SearchResponse response = service.searchAsync(request("张三邮箱")).join();
    assertEquals(RetrievalState.LEXICAL_ONLY_DEGRADED, response.state());
    assertTrue(response.hits().stream().anyMatch(h -> h.relevance() == Relevance.STRONG));
}

@Test
void ftsFailureMakesVectorHitsWeak() {
    lexical.fail();
    SearchResponse response = service.searchAsync(request("张三邮箱")).join();
    assertTrue(response.hits().stream().allMatch(h -> h.relevance() == Relevance.WEAK));
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=HybridRetrievalIntegrationTest,RetrievalFailureTest test`

Expected: FAIL.

- [ ] **Step 3: Run lexical and vector branches concurrently**

Plan the query once. Start FTS and query embedding/vector search independently. Do not start vector work if lifecycle is BUILDING, DEGRADED, disabled, or empty.

- [ ] **Step 4: Enforce the deadline**

Use stage-specific budgets and an overall 200ms timeout. A vector timeout preserves completed lexical results. Both branches failing returns `UNAVAILABLE`, never a successful empty list.

- [ ] **Step 5: Add request cancellation checks**

Cancellation may not interrupt native inference, but the completed result must be discarded if the request ID is no longer active.

- [ ] **Step 6: Run integration tests**

Run: `mvn -q -Dtest=HybridRetrievalIntegrationTest,RetrievalFailureTest test`

Expected: PASS.

- [ ] **Step 7: Commit orchestration**

```bash
git add src/main/java/com/mordor/kelly/kelsy/retrieval/HybridRetrievalService.java \
        src/main/java/com/mordor/kelly/kelsy/retrieval/RetrievalTimeouts.java \
        src/test/java/com/mordor/kelly/kelsy/retrieval/HybridRetrievalIntegrationTest.java \
        src/test/java/com/mordor/kelly/kelsy/retrieval/RetrievalFailureTest.java
git commit -m "feat: orchestrate kelsy hybrid retrieval"
```

---

### Task 4: Migrate ASK and knowledge_search

**Files:**
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/AskGrounding.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/KnowledgeSearchTool.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/LocalAssistantService.java`
- Modify: `src/main/java/com/mordor/kelly/ui/chat/ChatController.java`
- Modify: `src/test/java/com/mordor/kelly/kelsy/service/AskGroundingTest.java`
- Modify: `src/test/java/com/mordor/kelly/kelsy/service/KnowledgeSearchToolTest.java`
- Modify: `src/test/java/com/mordor/kelly/ui/chat/ChatControllerKelsyTest.java`

**Interfaces:**
- Produces: `CompletableFuture<AskGrounding> prepareAsync(...)`
- Knowledge tool uses `Mono.fromFuture`
- ASK forwards at most five STRONG paths to `CitationTurn`

- [ ] **Step 1: Write failing async ASK tests**

```java
@Test
void attachesOnlyFiveStrongHits() {
    AskGrounding grounding = AskGrounding.prepareAsync(store, QUERY, TODAY).join();
    assertEquals(5, grounding.attached().size());
    assertTrue(grounding.attached().stream()
            .allMatch(h -> h.relevance() == Relevance.STRONG));
}

@Test
void degradedEmptySearchDoesNotSayNoMatches() {
    ToolResultBlock block = tool.callAsync(param("未知事实")).block();
    assertTrue(block.toString().contains("检索不可用"));
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=AskGroundingTest,KnowledgeSearchToolTest test`

Expected: FAIL because current methods are synchronous and score-based.

- [ ] **Step 3: Remove score thresholds from AskGrounding**

Delete `DEFAULT_MIN_SCORE` and `RECALL_MIN_SCORE`. Build the assistant candidate message only from STRONG hits.

- [ ] **Step 4: Make ChatController continue after grounding future completion**

Show the user message immediately, keep `kelsyBusy` correct across success/failure/cancellation, add citation paths after a current request completes, and invoke `assistant.chat` only then.

- [ ] **Step 5: Format tool results by relevance and state**

Return STRONG first, then a “弱相关候选” section. Include degradation warning. Keep model-required `read_file` behavior unchanged.

- [ ] **Step 6: Run ASK/tool/controller tests**

Run: `mvn -q -Dtest=AskGroundingTest,KnowledgeSearchToolTest,ChatControllerKelsyTest,CitationTurnTest test`

Expected: PASS.

- [ ] **Step 7: Commit ASK and tool migration**

```bash
git add src/main/java/com/mordor/kelly/kelsy/service/AskGrounding.java \
        src/main/java/com/mordor/kelly/kelsy/service/KnowledgeSearchTool.java \
        src/main/java/com/mordor/kelly/kelsy/service/LocalAssistantService.java \
        src/main/java/com/mordor/kelly/ui/chat/ChatController.java \
        src/test/java/com/mordor/kelly/kelsy/service/AskGroundingTest.java \
        src/test/java/com/mordor/kelly/kelsy/service/KnowledgeSearchToolTest.java \
        src/test/java/com/mordor/kelly/ui/chat/ChatControllerKelsyTest.java
git commit -m "feat: use hybrid retrieval for kelsy asks"
```

---

### Task 5: Migrate /find and add knowledge-index status UI

**Files:**
- Modify: `src/main/java/com/mordor/kelly/ui/chat/ChatController.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/ui/knowledge/KnowledgePane.java`
- Create: `src/main/java/com/mordor/kelly/kelsy/ui/knowledge/RetrievalStatusViewModel.java`
- Modify: `src/main/resources/com/mordor/kelly/ui/chat/chat.css`
- Create: `src/test/java/com/mordor/kelly/kelsy/ui/knowledge/KnowledgePaneTest.java`
- Modify: `src/test/java/com/mordor/kelly/ui/chat/ChatControllerKelsyTest.java`

**Interfaces:**
- Produces: `RetrievalStatusViewModel.building(int completed,int total)`
- Produces: `RetrievalStatusViewModel.ready(int chunks)`
- Produces: `RetrievalStatusViewModel.degraded(String detail,boolean retryable)`
- Produces: `RetrievalStatusViewModel.overCapacity(int chunks,int supportedChunks)`
- Produces: `KnowledgePane.setRetrievalStatus(RetrievalStatusViewModel)`
- `/find` displays STRONG and WEAK separately
- UI callbacks compare request ID and user ID before applying

- [ ] **Step 1: Write failing status-view tests**

```java
@Test
void buildingStatusExplainsLexicalFallback() {
    pane.setRetrievalStatus(RetrievalStatusViewModel.building(1240, 8320));
    assertEquals("正在构建本地语义索引：1,240 / 8,320", pane.statusText());
    assertTrue(pane.statusDetail().contains("当前使用关键词检索"));
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=KnowledgePaneTest test`

Expected: compilation FAIL.

- [ ] **Step 3: Add nonblocking BUILDING, READY, DEGRADED, and OVER_CAPACITY UI**

Put status above the source list. Keep normal READY status compact. Add “查看详情/重试” only for a real degraded state.

- [ ] **Step 4: Replace synchronous `runFind`**

Immediately render “正在搜索,” launch `searchAsync(MANUAL_FIND)`, then show STRONG and WEAK sections. A newer `/find` invalidates the old request.

- [ ] **Step 5: Prevent stale user/request updates**

Before every FX update, compare request ID and current username. A callback from a previous user must be discarded.

- [ ] **Step 6: Run UI/controller tests**

Run: `mvn -q -Dtest=KnowledgePaneTest,ChatControllerKelsyTest test`

Expected: PASS.

- [ ] **Step 7: Commit UI integration**

```bash
git add src/main/java/com/mordor/kelly/ui/chat/ChatController.java \
        src/main/java/com/mordor/kelly/kelsy/ui/knowledge/KnowledgePane.java \
        src/main/java/com/mordor/kelly/kelsy/ui/knowledge/RetrievalStatusViewModel.java \
        src/main/resources/com/mordor/kelly/ui/chat/chat.css \
        src/test/java/com/mordor/kelly/kelsy/ui/knowledge/KnowledgePaneTest.java \
        src/test/java/com/mordor/kelly/ui/chat/ChatControllerKelsyTest.java
git commit -m "feat: show kelsy retrieval state in chat"
```

---

### Task 6: Fix runtime user ownership and lifecycle shutdown

**Files:**
- Modify: `src/main/java/com/mordor/kelly/kelsy/KelsyRuntime.java`
- Modify: `src/main/java/com/mordor/kelly/kelsy/service/LocalAssistantService.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/KelsyRuntimeUserSwitchTest.java`
- Modify: `src/test/java/com/mordor/kelly/kelsy/KnowledgeUpgradeTest.java`

**Interfaces:**
- Runtime identity is `(workspacePath, normalizedUserId)`
- Switching user closes old assistant/store/indexer before creating new state
- Close drains committed writer work and cancels unstarted embedding jobs

- [ ] **Step 1: Write failing user-switch test**

```java
@Test
void switchingUserCannotReuseOldAssistantOrStore() {
    KelsyRuntime alice = runtime("alice");
    KnowledgeStore aliceStore = alice.store("alice");
    KelsyRuntime bob = switchTo("bob");
    assertNotSame(alice, bob);
    assertTrue(aliceStore.isClosed());
    assertEquals("bob", bob.userId());
}
```

- [ ] **Step 2: Run and verify failure**

Run: `mvn -q -Dtest=KelsyRuntimeUserSwitchTest test`

Expected: FAIL because current singleton replacement compares workspace but not username.

- [ ] **Step 3: Add normalized user identity to runtime**

Store `userId` in `KelsyRuntime`; rebuild when workspace or normalized user changes. Never allow `store(otherUser)` on an existing user-bound runtime.

- [ ] **Step 4: Define shutdown order**

Stop accepting retrieval requests, cancel unstarted embedding jobs, drain the SQLite writer queue, close assistant, close reader/writer connections, then release shared process-level ONNX runtime at application shutdown.

- [ ] **Step 5: Keep startup reconciliation asynchronous**

Do not execute full FTS reconcile in `upgradeKnowledge` on the JavaFX construction path. Return a lexical store first and schedule reconciliation/lifecycle work.

- [ ] **Step 6: Run lifecycle tests**

Run: `mvn -q -Dtest=KelsyRuntimeUserSwitchTest,KnowledgeUpgradeTest test`

Expected: PASS.

- [ ] **Step 7: Commit lifecycle ownership**

```bash
git add src/main/java/com/mordor/kelly/kelsy/KelsyRuntime.java \
        src/main/java/com/mordor/kelly/kelsy/service/LocalAssistantService.java \
        src/test/java/com/mordor/kelly/kelsy/KelsyRuntimeUserSwitchTest.java \
        src/test/java/com/mordor/kelly/kelsy/KnowledgeUpgradeTest.java
git commit -m "fix: isolate kelsy retrieval by user"
```

---

### Task 7: Enforce quality, latency, and package gates

**Files:**
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/golden/GoldenHybridRetrievalTest.java`
- Create: `src/test/java/com/mordor/kelly/kelsy/retrieval/perf/HybridRetrievalPerfTest.java`
- Modify: `pom.xml`
- Modify: platform release CI configuration used by this repository
- Create: `docs/superpowers/research/2026-09-18-kelsy-hybrid-retrieval-results.md`

**Interfaces:**
- Consumes: frozen 200-query golden corpus and selected model
- Produces: release-blocking quality and P95 results

- [ ] **Step 1: Write quality assertions**

```java
assertTrue(metrics.requiredRecallAt5() >= 0.95);
assertTrue(metrics.askPrecisionAt5() >= 0.90);
assertEquals(0, metrics.askForbiddenHits());
assertEquals(0, metrics.noAnswerStrongHits());
assertTrue(metrics.semanticRecallGainPoints() >= 15.0);
```

- [ ] **Step 2: Write percentile assertions with warmup**

Measure planner, FTS, query embedding, 20K scan, fusion, and end-to-end separately. Fail when:

```text
planner P95 >= 5ms
FTS P95 >= 30ms
query embedding P95 >= 50ms
20K scan P95 >= 30ms
fusion P95 >= 10ms
end-to-end P95 >= 100ms
hard request time >= 200ms
```

- [ ] **Step 3: Run default regression tests**

Run: `mvn -q test`

Expected: PASS with `model`, `perf`, and `slow` excluded.

- [ ] **Step 4: Run model and performance gates**

Run:

```bash
mvn -q -Dgroups=model -Dtest=GoldenHybridRetrievalTest test
mvn -q -Dgroups=perf -Dtest=HybridRetrievalPerfTest test
```

Expected: PASS on designated target machines.

- [ ] **Step 5: Build and smoke-test platform packages offline**

Build macOS x64/ARM64, Windows x64, and supported Linux packages. Verify native loading, model paths containing spaces/Chinese, SHA validation, and fully disconnected query/indexing.

- [ ] **Step 6: Record measured release evidence**

Write exact model fingerprint, target hardware, quality metrics, percentile metrics, package size increase, and RSS increase. Do not include user queries or real knowledge.

- [ ] **Step 7: Commit gates and evidence**

```bash
git add src/test/java/com/mordor/kelly/kelsy/retrieval/golden/GoldenHybridRetrievalTest.java \
        src/test/java/com/mordor/kelly/kelsy/retrieval/perf/HybridRetrievalPerfTest.java \
        pom.xml docs/superpowers/research/2026-09-18-kelsy-hybrid-retrieval-results.md
git commit -m "test: enforce kelsy hybrid retrieval gates"
```

## Plan Completion Gate

- Original, synonym, and vector ranks remain independently visible in diagnostics.
- ASK never attaches a pure synonym-only or pure vector-only candidate.
- Every entry point consumes the same classification and base ordering.
- FTS/vector timeout and failure states are explicit and never masquerade as no matches.
- No JDBC connection is used concurrently across threads.
- No full reconciliation occurs on a search hot path.
- User switches cannot reuse old retrieval state.
- JavaFX remains responsive during search and initial embedding build.
- Frozen quality and 20K performance gates pass.
- All target packages perform local inference while fully offline.
