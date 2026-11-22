# QOC Architecture & Code Review (`review.md`)

**As of:** 2026-11-09  
**Basis:** Working tree `qoc/` (HEAD `f631a7b` plus uncommitted changes)  
**Scope:** `qoc/engine`, `qoc/quarkus`, `qoc/cli`, `qoc/tui`, `qoc/deploy` (~597 Java files, ~55,700 LOC)

> **Verification note:** The original version of this document was checked against the code. In particular §3.5.4 (Subagent Registry), §3.7.2 (Session Isolation), §3.7.3 (Preflight) and §3.8 (Coverage/Gates) were corrected. The recommendations in §4 are detailed with file references, implementation steps, tests and acceptance criteria. Line numbers refer to the working tree as of 2026-11-09 and may shift after refactoring.

---

## 1. Executive Summary

QOC has a **very clean hexagonal architecture** at its core (`engine.core`, `engine.runtime`), mechanically secured by ArchUnit rules (gates G-1…G-9). `engine.core` is free of Quarkus, CDI, Jackson and HTTP dependencies.

Opposed to this is a **massively grown adapter/outer-skin layer (`quarkus`)**, in which orchestration, persistence side-paths, security, tool wiring and UI logic are concentrated in a few monolithic classes. In addition, several newer features (LangChain4j adapter, triage LLM, parts of the resilience wrapper) exist only as skeletons or are unwired.

**Most important risks in brief:**

1. **Correctness:** PostgreSQL-specific SQL in `Engine.java` causes retention of approvals and the tool cache to fail in SQLite mode (the error is only logged).
2. **Security:** Session isolation is only partially implemented in the HTTP layer; Messages, Questions and Permissions are reachable by foreign users. An `owner_id` is missing in the DB.
3. **Maintainability:** `Engine.java` (3,443 LOC) and `HttpApi.java` (1,587 LOC) are God-classes; the SQL and SQLite adapters are almost completely duplicated.
4. **Concurrency:** ThreadLocal bridges and per-tool-call/step executor creation are fragile and/or inefficient.
5. **Unfinished features:** LangChain4j, `SessionWake`, LLM triage and subagent deduplication are stubs or not integrated.

---

## 2. Strengths (What is done well)

1. **Strict layer separation in `engine`:** Stages S0 (`model`, `port`, `json`) → S1 (`contract`, `event`) → S2 (`eval`) → `runtime` → `adapter` are cycle-free and enforced by ArchUnit.
2. **Explicit domain modeling:** Dedicated value types (`SessionId`, `AgentId`, `CallId`, `TokenCount`, `Money`, `Option<T>`, `Result<T>`) instead of primitive types or `null` sentinels.
3. **Multi-stage permission model:** Combination of least-privilege baseline, agent frontmatter, session rules, `AskMode` (`deny`/`allow`/`interactive`), path allow-lists (`FileAccess`) and `PortableScanner` for shell chains.
4. **Idempotency & traceability:** Append-only event journal, `ToolResultCache` (idempotency key), dedicated rotating LLM trace.
5. **Test base:** 763 engine tests and 122 Quarkus tests currently pass (as of 2026-11-08); the core domain is well covered.

---

## 3. Architectural Weaknesses & Technical Debt

### 3.1 God-classes in `quarkus` (Critical)

| File | Lines | Problem |
|---|---:|---|
| `quarkus/.../http/Engine.java` | **3,443** | Combines DI wiring, session lifecycle, prompt loop, subagent orchestration, MCP discovery, A2A setup, retention scheduler, workspace scoping, file API and direct JDBC queries. |
| `quarkus/.../http/HttpApi.java` | **1,587** | All REST/SSE endpoints, DTO mapping, auth/role checks, file up/download and HTML rendering for email questions in one class. |
| `quarkus/.../http/EngineConfig.java` | **832** | Mixes MicroProfile config injection, JSON/JSONC file merging, markdown agent loading, skill filtering and default agent creation. |
| `quarkus/.../META-INF/resources/index.html` | **2,370** | Complete single-page UI (HTML + CSS + JS, SSE client, markdown renderer, modals) in a single static file without build/test pipeline. |

**Impact:** High change risk, hard testability (unit tests for `Engine` require complex setups or Quarkus boot), violation of single responsibility.

### 3.2 Architecture bypass in `quarkus` (Direct SQL bypassing the port)

While `engine` strictly checks that `java.sql.*` only appears in `adapter.sql` and `adapter.sqlite` (`engine/src/test/.../arch/ArchitectureTest.java:61`), the Quarkus test has `noImplSuffix`, `noStandardStreams` **and** a `javaSqlOnlyInSqlAdapter` rule — but the latter analyzes only `de.augmentia.oc.engine.http`, so it does not cover the engine adapters, and the direct JDBC usage in `Engine.java` is apparently not caught (the test is green).

As a result, direct SQL access has moved into the HTTP/wiring layer:

- `Engine.java` runs its own `DELETE FROM pending_approval ...`, `DELETE FROM tool_result_cache ...`, `DELETE FROM tool_output ...` and `DELETE FROM snapshot ...` directly via `dataSource.getConnection()` (`Engine.java:845,852,1092-1107`). Further direct JDBC usage outside the adapters: readiness check (`1540`) and preflight DB check (`1835`).
- **Dialect bug:** `purgeDecidedApprovals()` and `purgeToolResultCache()` use PostgreSQL syntax `now() - interval '...'` (`Engine.java:1095,1107`). The SQLite migrations define `pending_approval.created_at` as `integer` and `tool_result_cache.created_at` as `text` (`db/migration-sqlite/V3__pending_approvals.sql`, `V7__tool_result_cache.sql`). In SQLite mode the query fails; the `SQLException` is caught and only logged as a warning. Retention effectively does not run there — the app does not crash, but data grows unbounded.
- `DurableQuestionSource.java` and `DurableApprovalSource.java` contain complete own JDBC SQL in the middle of the package `de.augmentia.oc.engine.http` (7 and 8 `getConnection()` sites respectively).
- `purgeAuditLog()` is portable (`?` parameters) but remains an architecture bypass.

### 3.3 Code duplication between `adapter.sql` and `adapter.sqlite`

Almost every store class exists twice with nearly identical logic:

- `SqlSessions` (329 LOC) vs. `SqliteSessions` (331 LOC)
- `SqlJournal` vs. `SqliteJournal`, `SqlConversation` vs. `SqliteConversation`, `SqlApprovals` vs. `SqliteApprovals`, `SqlClaims` vs. `SqliteClaims`, `SqlToolResultCache` vs. `SqliteToolResultCache`, `SqlSnapshots` vs. `SqliteSnapshots`, `SqlCredentials` vs. `SqliteCredentials`, `SqlInstructions` vs. `SqliteInstructions`, `SqlToolOutput` vs. `SqliteToolOutput`.

Every schema or interface change (such as the recent `setIdleOutcome`) must be manually propagated to two places.

### 3.4 Threading & concurrency weaknesses

Java 21 is set (including Virtual Threads), but barely used; instead problematic thread patterns exist:

1. **Executor creation per tool call (`ToolRuntime.java:100`):**  
   For **every single tool invocation**, when a step timeout is active, a new `Executors.newSingleThreadExecutor()` + thread is created and shut down again in the `finally` (`exec.shutdownNow()`). With parallel tool calls in `Step.java` (`Executors.newFixedThreadPool` per step, `Step.java:132`, `shutdown()` in the `finally`) this produces unnecessary thread churn — no leak, but high allocation and scheduling overhead.
2. **ThreadLocal coupling between Step and provider (`Engine.java` / `HttpProviderAdapter.java`):**  
   `Engine.LLM_EVENT_SINK` and `Engine.LLM_CALL_INDEX` (`Engine.java:160-163`) are set per prompt (`Engine.java:1801-1804`) and additionally copied into the static ThreadLocals of `HttpProviderAdapter` (`EVENT_SINK`, `CALL_INDEX`, `LAST_RESPONSE_SUMMARY`, `HttpProviderAdapter.java:49-51`). When execution moves to a worker thread (timeout wrapper, subagent pools, reactive pipelines), live events, call indices or correlation IDs are lost. `CorrelationContext` also uses static ThreadLocals (`CorrelationContext.java:11-12`).
3. **Unbounded raw threads:**  
   `new Thread(...)` in `Engine.java:514` (`SessionWake`), `Engine.java:756` (retention), `DurableQuestionSource.java:99` (`email-poller`) and `HttpApi.java:1048`.

### 3.5 Incomplete / dead integrations

1. **LangChain4j unused:**  
   `LangChain4jProviderAdapter` (453 LOC) and `Assistant.java` are in the code and `quarkus-langchain4j-openai` is in the POM, but `ProviderProducer` instantiates only `HttpProviderAdapter` or the scripted mock (`ProviderProducer.java:49-66`). There is no `engine.provider.type`.
2. **`SessionWake` is a stub (`Engine.java:514-523`):**  
   The wake callback starts a thread, logs `"SessionWake: processing background results..."`, but calls **no** `runPrompt` and no resume. The comment describes the intent; the code does not implement it.
3. **`NoOpTriageService`:**  
   Phase 3 of the resilience wrapper is wired as an interface + NoOp stub (`Engine.java:739`), but does not yet call a real analysis LLM.
4. **Subagent deduplication missing:**  
   `SubagentSessionRegistry` / `InMemorySubagentSessionRegistry` are **untracked**, but already wired in the working tree: instantiation `Engine.java:466-467`, `registerChild` `2368`, `children`/`descendants` `2475,2484`, `remove` `885`. They serve parent/child tracking (cascade cancel), **not** deduplication. Real dedup logic (same parent + agent + prompt) exists only as a prompt hint.
5. **GraalVM Native Image:**  
   Works only with a workaround (`-Dquarkus.arc.exclude-types=org.a2aproject.sdk.**`, `deploy/Dockerfile.native:8`), because the A2A SDK starts a thread in the static/bean initializer (`MainEventBusProcessor` in `a2a-java-sdk-server-common-1.4.0.Final.jar`).

### 3.6 Error handling & silent failures (`catch (Exception ignored)`)

In production code there are **47 sites** with `catch (RuntimeException ignored)` or `catch (Exception ignored)` (`engine/src/main/java` + `quarkus/src/main/java`). Including critical paths:

- Workspace status updates: `Engine.java:1706-1707, 1998-1999, 2187, 2196`
- Idle-outcome persistence: `Engine.java:2165-2166`
- Empty-session cleanup: `Engine.java:2180-2182`
- SSE/subagent event publishing (e.g. in the uncommitted `Engine` diff)

If a DB transaction fails there, the error is swallowed completely. This is especially critical because these paths determine session state and UI visibility.

### 3.7 Security & multi-tenancy boundaries

1. **Shell scanner (`PortableScanner`) is not a hard security boundary:**  
   It detects simple path tokens (`PortableScanner.looksLikePath`), but does not expand shell variables (`$HOME`, `$(...)`, backticks). `splitChains` is explicitly not quote-aware per its comment. Since `bwrap` must be disabled in unprivileged Docker containers (`deploy/docker-compose.yml:80`), protection in the container relies solely on Unix user rights and mount namespaces.
2. **Session isolation is only partially implemented:**  
   The DB has **no** `owner_id` in the `session` table. The HTTP layer protects some endpoints via `isOwner()`/`requireSessionAccess()`:
   - List: `GET /api/session` filtered (`HttpApi.java:181`)
   - Single fetch: `GET /session/{id}` → 403 (`HttpApi.java:195`)
   - Delete: `DELETE /session/{id}` → 403 (`HttpApi.java:160`)
   - `requireSessionAccess()` for `resume`, `prompt`, `title`, `revert`, `compact`, `fork`, `interrupt`, `running` (`HttpApi.java:241,268,370,426,447,552,574,586`)
   - Admin bypass: `HttpApi.java:1522` (`hasRole("admin")`)

   **Not protected** are:
   - `GET /session/{id}/messages` and `/message` (`HttpApi.java:337-365`)
   - `GET /session/{id}/question` and `POST .../question/{questionId}/reply` (`391,406`)
   - `GET /session/{id}/processes`, `POST .../processes/{id}/kill`, `GET/POST .../tasks`, `.../tree`, `.../move` (`475-531`)
   - `GET /session/{id}/permission` and `POST .../permission/{requestId}/reply` (`608,620`)
   - `GET /api/questions` lists all pending questions unfiltered (`1185`)

   An authenticated user can thus read others' conversations, answer others' questions or grant others' tool approvals if they know the session ID. The `isOwner()` check is also path-based (workspace directory) and not a DB foreign key.
3. **Synchronous blocking check in `PreflightChecker`:**  
   `DefaultPreflightChecker` performs a synchronous DNS lookup + HTTP `HEAD` request to the provider host on **every** `runPrompt()` (`Engine.java:1829-1855`), default timeout 5 s, without caching/TTL. 404/405 are treated as reachable (`< 500`), so no error arises — the overhead is pure latency. With slow gateways every turn can be delayed unnecessarily.

### 3.8 Test coverage & build gates

- **Test status:** 763 engine tests (0 failures, 4 skipped) and 122 Quarkus tests (0 failures, 23 skipped) per Surefire reports from 2026-11-08.
- **Coverage:** The current JaCoCo report shows `engine` 42.74 % (24,349/56,973 instructions) and `quarkus` 10.74 % (3,681/34,287). However, the artifacts are **not from a single run**: `jacoco.exec` is from 2026-11-08 19:36, the Surefire reports from 21:58, the CSV report was generated on 2026-11-09 08:31. The numbers are therefore only limitedly reliable and must be re-measured before any commit decision.
- **No mechanical 80 % gate:** In `engine/pom.xml` JaCoCo is configured only with `prepare-agent` + `report`, without the `check` goal. The 80 % rule is an AGENTS.md commit rule, not a build gate.
- **`PSQLException: This connection has been closed`:** Not present in the current Surefire reports (0 hits). The observation is therefore not reproducibly supported and may stem from older runs.
- **Retention in tests:** There are no `%test` overrides for `engine.session.retention-days` or `engine.audit.retention-days` (`application.properties` has only test port, revert snapshot and logging). Since `startRetention()` starts immediately when `sessionRetentionDays > 0`, background jobs also run in `@QuarkusTest` contexts.

### 3.9 Further observations

- **Dirty working tree:** 5 modified files (`Engine.java`, `HttpApi.java`, `ToolRuntime.java`, `TaskTool.java`, `index.html`) and 2 untracked registry files. The review therefore describes a moving target; line numbers may shift.
- **Duplicated comment:** `Engine.java:2168-2170` contains the same sentence three times ("Generate title after the first successful prompt…").
- **Misplaced Javadoc:** The Javadoc block before `purgeDecidedApprovals()` (`Engine.java:1081-1089`) describes `captureSnapshot`, not the purge method.
- **Inconsistent retention defaults:** `engine.audit.retention-days` defaults to **2 days** (`application.properties:182`, `deploy/.env:104`), while `settings.md` cites **30 days** in the secure example. `SESSION_RETENTION_DAYS` is 7 (code) vs. 14 (`deploy/.env`).
- **`review.md` itself is untracked** — documentation change not yet committed.

---

## 4. Recommendations (detailed)

The measures are sorted by priority, dependency and risk. Recommended order: **complete P1 first**, then P2 (architecture/security), then P3. Each measure contains goal, affected files, implementation, tests/acceptance and effort.

### 4.1 Priority 1 — Correctness & stability

#### H1 — Fix the SQL dialect bug in the retention jobs

**Goal:** Retention of decided approvals and the tool result cache works in PostgreSQL **and** SQLite; no direct persistence SQL in `Engine.java` anymore (readiness/preflight checks excepted).

**Findings:** `Engine.java:1090-1111` uses `now() - interval '7 days'` and `'1 day'`. SQLite knows neither `now()` nor `interval`; the query fails and is only logged. `ToolResultCache` already has `delete`/`deleteBySession` methods but no retention method (`engine/core/port/store/ToolResultCache.java`). `ApprovalStore` manages **saved_approval**, not `pending_approval` — extending `ApprovalStore` as originally suggested in the review would therefore be wrong. Pending approvals are managed by `DurableApprovalSource` (implementing `ApprovalSource`).

**Implementation:**
1. Extend `ToolResultCache` with `int deleteCreatedBefore(Instant cutoff)`; implement in `SqlToolResultCache` and `SqliteToolResultCache` with `PreparedStatement` + `Timestamp`/`setLong` (SQLite column is `text`/`integer`; observe the storage type used by the adapter).
2. Introduce a new port `PendingApprovalStore` in `engine/core/port/store/` (or extend `ApprovalSource` with a default method `purgeDecidedBefore(Instant)` if the refactor is too large) and switch `DurableApprovalSource` to it. Methods: `deleteDecidedBefore(Instant cutoff)`.
3. Replace `Engine.purgeDecidedApprovals()` and `Engine.purgeToolResultCache()` with port calls; remove the direct SQL blocks.
4. Route `purgeAuditLog()` through ports as well (`Journal.deleteOlderThan`, new methods for `tool_output`/`snapshot`) or document it as a time-limited exception.
5. Check migrations: `pending_approval.created_at` is `integer` in SQLite, `timestamptz` in PG; the cutoff parameterization must handle both types correctly.

**Tests/acceptance:**
- Unit tests for `SqlToolResultCache`/`SqliteToolResultCache` with a fixed `Instant`: entries older than the cutoff are deleted, younger ones remain.
- Integration test in the SQLite profile: `purgeDecidedApprovals` and `purgeToolResultCache` run without warning and delete the expected rows.
- `grep -R "now() - interval" quarkus/src/main/java` returns no hit.
- ArchUnit rule `javaSqlOnlyInSqlAdapter` extended to the Quarkus module (see H8) and stays green.

**Affected files:** `engine/core/port/store/ToolResultCache.java`, `engine/adapter/sql/SqlToolResultCache.java`, `engine/adapter/sqlite/SqliteToolResultCache.java`, `quarkus/.../http/DurableApprovalSource.java`, `quarkus/.../http/Engine.java`, possibly new port/adapter classes.

**Effort:** 0.5–1 day · **Risk:** low

---

#### H2 — Cache and make the preflight check configurable

**Goal:** No synchronous DNS/HTTP check on every turn; preflight remains as protection but causes latency only once per TTL.

**Findings:** `DefaultPreflightChecker.check()` performs a DNS lookup and HTTP `HEAD` with a 5 s timeout; `Engine.runPrompt()` calls it on every turn (`Engine.java:1829`). There is no configuration for TTL or for disabling the HTTP check.

**Implementation:**
1. New properties: `engine.preflight.ttl-seconds` (default 60), `engine.preflight.http-check.enabled` (default true), `engine.preflight.timeout-seconds` (default 5).
2. Introduce a small cache per provider URL in `DefaultPreflightChecker` (`ConcurrentHashMap<String, CachedResult>` with timestamp); reuse the result for the TTL.
3. Make DNS and HTTP checks separately configurable (`engine.preflight.dns.enabled`, `engine.preflight.http-check.enabled`).
4. Check whether workspace/database checks can also be cached (DB health is cheap, workspace writability too).
5. Add metrics: `qoc_preflight_cache_hits_total`, `qoc_preflight_cache_misses_total`, `qoc_preflight_duration_seconds`.
6. Add config docs in `docs/settings.md` and `deploy/.env.example`.

**Tests/acceptance:**
- Test with fake `HttpClient`: first check calls DNS/HTTP, second check within the TTL does not; after TTL expiry again.
- Test: `http-check.enabled=false` skips the HTTP call, DNS/workspace remain active.
- Test: HTTP 404/405 are treated as reachable (already implemented, regression guard).
- Acceptance: `runPrompt` overhead on cache hit < 5 ms; no preflight warning in normal operation.

**Affected files:** `engine/runtime/resilience/DefaultPreflightChecker.java`, `quarkus/.../http/Engine.java`, `quarkus/src/main/resources/application.properties`, `deploy/.env.example`, `docs/settings.md`.

**Effort:** 0.5–1 day · **Risk:** low

---

#### H3 — Unify executors in `ToolRuntime` and `Step`

**Goal:** No executor churn per tool call/step; timeout behavior remains unchanged.

**Findings:** `ToolRuntime.java:100` creates a `newSingleThreadExecutor` per tool invocation and shuts it down in the `finally`; `Step.java:132` creates a `newFixedThreadPool(MAX_PARALLEL_TOOLS, …)` per step and shuts it down in the `finally`. Both patterns are functionally correct (no leak), but inefficient.

**Implementation:**
1. Inject a shared, long-lived `ExecutorService` into `ToolRuntime` and `Step` (constructor/factory) or use an `Executors.newVirtualThreadPerTaskExecutor()` that lives for the engine lifetime and is closed on shutdown.
2. If no shared executor is introduced: at least use `Thread.ofVirtual().factory()` / `newVirtualThreadPerTaskExecutor()` instead of platform thread pools.
3. Keep timeout semantics: `Future.get(stepTimeoutSeconds, SECONDS)` + `cancel(true)`; with virtual threads, verify that tool implementations handle `InterruptedException` correctly.
4. Lifecycle: close the executor cleanly in `Engine` shutdown (`@PreDestroy`); no `shutdownNow()` in the middle of running tool calls.
5. Optionally keep the semaphore for parallel tool calls (`MAX_PARALLEL_TOOLS`) to limit resources.

**Tests/acceptance:**
- Unit test: a tool timeout still results in `ReadTool.failure(... "step timeout")` and interrupts the task.
- Load/integration test with 10 parallel tool calls: no thread explosion, no lost results.
- Acceptance: `grep -R "newSingleThreadExecutor" engine/src/main/java` returns no hit (or only at one central place).

**Affected files:** `engine/runtime/tool/runtime/ToolRuntime.java`, `engine/runtime/step/Step.java`, possibly `quarkus/.../http/Engine.java` (wiring/shutdown).

**Effort:** 0.5–1 day · **Risk:** medium (timeout/interrupt semantics)

---

#### H4 — Disable retention jobs in tests

**Goal:** Deterministic `@QuarkusTest` runs without background timers that close DB connections or alter state.

**Findings:** `startRetention()` starts several `scheduleWithFixedDelay` jobs immediately when `sessionRetentionDays > 0` (`Engine.java:753-771`). There are no `%test` overrides; the jobs therefore also run in tests. Since all purge jobs share the same executor, setting `session.retention-days=0` also disables approval/cache purges — a coupling effect that should be documented consciously.

**Implementation:**
1. Add to `application.properties`:
   - `%test.engine.session.retention-days=0`
   - `%test.engine.audit.retention-days=0`
2. Optionally introduce `engine.retention.enabled` as a separate flag and make the purge jobs switchable independently of `sessionRetentionDays` (decouples H1/H4).
3. In tests that explicitly check retention, call the purge methods via the service API rather than via the scheduler.

**Tests/acceptance:**
- `@QuarkusTest` suite runs without `purge*` log lines and without DB connection errors.
- A dedicated retention test checks the purge methods directly (without scheduler).

**Affected files:** `quarkus/src/main/resources/application.properties`, `quarkus/.../http/Engine.java`.

**Effort:** 0.25 day · **Risk:** low

---

#### H5 — Make critical `catch (ignored)` paths visible

**Goal:** Errors in session state, idle outcome and event publishing are at least logged, not swallowed.

**Findings:** 47 silent catch blocks; especially critical are `Engine.java:1706-1707, 1998-1999, 2165-2166, 2180-2182, 2187, 2196`. A DB error when setting the idle outcome or during empty-session cleanup remains invisible.

**Implementation:**
1. In the named paths use `catch (RuntimeException e)` and log with `LOG.log(Level.FINE, "...", e)`; for critical state changes use `LOG.warn(...)`.
2. For purely optional UI events (SSE publish) `FINE` suffices; for persistence updates `WARNING`.
3. Add the existing script `check-no-catch-exception.sh` to CI (or as a Maven exec phase) and extend it as an ArchUnit rule: no catch blocks with empty bodies in `quarkus`/`engine`.
4. Check whether `catch (RuntimeException ignored)` is deliberately best-effort in some places; justify those with a comment.

**Tests/acceptance:**
- `grep -R "catch (.* ignored)"` on critical packages yields no hits or every hit has a justifying comment.
- Log test: on a simulated DB error a warning appears, the turn does not abort.

**Affected files:** `quarkus/.../http/Engine.java`, `quarkus/.../http/HttpApi.java`, `engine/.../runtime/**`, `check-no-catch-exception.sh`.

**Effort:** 1 day · **Risk:** low

---

### 4.2 Priority 2 — Architecture & security

#### H6 — Decompose `Engine.java` into focused services

**Goal:** `Engine` becomes a thin wiring/facade layer (< 800 lines); business logic moves into testable services.

**Findings:** `Engine.java` (3,443 LOC) combines at least ten responsibilities. Existing building blocks (`SessionService`, `SessionRetention`, `PermissionEngine`, `SubagentTool`) can serve as models.

**Implementation (proposal):**
1. `PromptExecutionService` — `runPrompt` loop, preflight, retry, triage, compaction, budgets, idle outcome. Dependencies: provider, `Step`, `ToolRuntime`, `TurnPolicy`, `SessionStore`.
2. `WorkspaceManager` — `user-ws`/ephemeral workspaces, `allowedBaseFor`, file API (`listFiles`, `readFile`, `writeFile`), snapshot handling.
3. `RetentionService` — `purgeExpiredSessions`, `purgeAuditLog`, `purgeDecidedApprovals`, `purgeToolResultCache`, `sweepEphemeralOrphans`, `sweepPausedSessions`; own executor (see H4).
4. `SubagentOrchestrator` — depth tracking, slot semaphore, `SubagentSessionRegistry`, background tasks, `SessionWake`.
5. `McpRegistry`/`A2AService` — move discovery/setup out of `Engine`.
6. `SessionFacade` — public API for `HttpApi`; delegates to the services.

**Approach:** Strangler pattern: first pure extraction without behavior change, then add tests per service with fakes. No big-bang.

**Tests/acceptance:**
- `Engine.java` < 800 lines; every new class < 500 lines.
- Existing 763 engine tests + Quarkus tests stay green.
- New unit tests for `RetentionService` and `WorkspaceManager` without Quarkus boot.
- No cycles between new services (ArchUnit).

**Affected files:** `quarkus/.../http/Engine.java` and new service classes in the same package (or `quarkus/.../http/service/`).

**Effort:** 3–5 days · **Risk:** medium (behavior risk from large extraction)

---

#### H7 — Split `HttpApi.java` by resource

**Goal:** REST/SSE endpoints are separated by domain, auth checks centralized and testable.

**Findings:** `HttpApi.java` (1,587 LOC) contains all endpoints, DTO mapping, HTML rendering and auth checks. `requireSessionAccess` is used at only 8 of ~25 session endpoints (see §3.7.2).

**Implementation (proposal):**
1. `SessionResource` — CRUD, list, resume, prompt, command, messages, title, todo, tree, move, fork, interrupt, running.
2. `QuestionResource` — pending question, reply, `/api/questions`, email HTML.
3. `PermissionResource` — pending permissions, reply.
4. `WorkspaceFileResource` — `/files`, `/file`, `/file/download`, `/dir/download`.
5. `AgentResource` / `McpResource` / `AdminResource` — agents, MCP, config, health, metrics, audit.
6. Shared helpers: `SessionAccessGuard` (uniform `requireSessionAccess`), `PrincipalContext`, DTO mappers.
7. Protect **all** session endpoints with `SessionAccessGuard` (see H10).

**Tests/acceptance:**
- `HttpApi.java` < 300 lines; each resource < 400 lines.
- RestAssured tests per resource; in particular 403 tests for foreign sessions.
- OpenAPI/endpoint list unchanged (regression test).

**Affected files:** `quarkus/.../http/HttpApi.java`, new resource classes.

**Effort:** 2–3 days · **Risk:** medium (many endpoints, auth behavior)

---

#### H8 — Move persistence out of `quarkus` into the adapter layer

**Goal:** `DurableQuestionSource` and `DurableApprovalSource` contain no JDBC; the Quarkus layer uses ports. ArchUnit enforces the rule in the Quarkus module too.

**Findings:** Both classes live in `de.augmentia.oc.engine.http` and contain direct SQL. A move into `engine.adapter.*` is not 1:1 possible because they use CDI annotations (`@ApplicationScoped`, `@Inject`) and MicroProfile config, and the engine module must remain free of `jakarta.*` per ArchUnit.

**Implementation:**
1. Define new ports:
   - `PendingApprovalStore` (create/find/resolve/deleteDecidedBefore)
   - `QuestionStore` (createPending/listPending/findByToken/resolve)
2. Pure JDBC implementations in `engine/adapter/sql/` and `engine/adapter/sqlite/` (without CDI).
3. `DurableApprovalSource`/`DurableQuestionSource` remain as thin Quarkus services in `quarkus/.../http`, inject the ports and contain only orchestration (token, notification, polling).
4. Add an ArchUnit rule in the Quarkus module: `noClasses().that().resideOutsideOfPackages("..adapter..")...should().accessClassesThat().resideInAPackage("java.sql..")` — after the refactor it must be green.
5. Alternatively, if the refactor is too large: move SQL into dedicated `*SqlSupport` classes in the engine adapter and have the Quarkus classes only delegate.

**Tests/acceptance:**
- `grep -R "java.sql" quarkus/src/main/java` returns no hits outside clearly documented wiring/health-check exceptions (readiness/preflight).
- Adapter tests for both dialects (PG/SQLite) with token resolution and purge.
- Quarkus ArchUnit test runs in `mvn test`.

**Affected files:** `quarkus/.../http/DurableApprovalSource.java`, `DurableQuestionSource.java`, `engine/core/port/store/*`, `engine/adapter/sql/*`, `engine/adapter/sqlite/*`, `quarkus/src/test/.../arch/ArchitectureTest.java`.

**Effort:** 1–2 days · **Risk:** medium (CDI/config boundaries)

---

#### H9 — Reduce SQL/SQLite duplication

**Goal:** Common JDBC base classes + narrow dialect interface; ~1,500 LOC of duplicates removed.

**Findings:** 11 store pairs with nearly identical logic (see §3.3). Differences mainly concern types (`jsonb` vs. `text`), `ON CONFLICT` syntax (both support it), timestamps (`timestamptz` vs. `integer`/`text`) and `now()` vs. `datetime('now')`.

**Implementation:**
1. `SqlDialect` interface in `engine/adapter/sql/` with methods like `jsonType()`, `timestampType()`, `nowExpression()`, `bindInstant(PreparedStatement, int, Instant)`, `readInstant(ResultSet, String)`.
2. Abstract base classes `AbstractJdbcSessions`, `AbstractJdbcJournal`, `AbstractJdbcApprovals`, `AbstractJdbcToolResultCache`, etc.; SQL strings maintained once, dialect differences encapsulated via the interface.
3. `Sql*` and `Sqlite*` classes become thin subclasses with a dialect instance.
4. Migration parity as a test: both schemas must yield the same tables/columns (PG vs. SQLite).
5. Proceed stepwise: start with `ToolResultCache` and `Approvals`, then `Sessions`/`Journal`.

**Tests/acceptance:**
- Existing `SqlAdaptersTest` and `SqliteAdaptersTest` stay green.
- New parameterized tests run against both dialects.
- Measurable LOC reduction (target: −1,000 to −1,500 lines).
- No behavior change in error cases (SQLException → `StoreFailure`).

**Affected files:** `engine/adapter/sql/*`, `engine/adapter/sqlite/*`, new `SqlDialect`/`AbstractJdbc*` classes.

**Effort:** 2–3 days · **Risk:** medium (many stores, schema differences)

---

#### H10 — Complete session isolation per user (OIDC)

**Goal:** No user can read/change foreign sessions, messages, questions or permissions; admins retain global access.

**Findings:** DB has no `owner_id`; HTTP protects only part of the endpoints (see §3.7.2). `isOwner()` is path-based and therefore depends on correct workspace directories.

**Implementation:**
1. Migration `V9__session_owner.sql` (PG + SQLite): `alter table session add column owner_id text;` plus index `(owner_id, time_updated)`.
2. Backfill: derive existing sessions from the workspace path (`user-ws/{id}` → `owner_id=id`), otherwise `legacy`.
3. Extend `SessionStore.create`/`SessionInfo` with `ownerId`; `createSession` writes `principal.id()`.
4. `SessionStore.list(ownerId)` or service filter; `Engine.listSessions(user, isAdmin)` returns only own sessions for non-admins.
5. Use `SessionAccessGuard` on **all** session endpoints: messages, question, question reply, processes, tasks, tree, move, permission, permission reply. Missing session → 404, foreign session → 403 (no information leak).
6. Filter `/api/questions` by owner; also `pendingQuestion`/`pendingPermissions`.
7. Admin bypass explicitly via `hasRole("admin")` (as today in `HttpApi.isOwner`).
8. Audit: log access to foreign sessions as a warning (without contents).

**Tests/acceptance:**
- Two-user test: user B receives 403/404 for all endpoints of user A (list, get, messages, question, permission, processes, tasks, tree, move).
- Admin test: admin sees and operates all sessions.
- Migration test: existing sessions are assigned correctly.
- No endpoint without a guard in the new resource structure (H7).

**Affected files:** `engine/core/port/store/SessionStore.java`, `engine/adapter/sql/SqlSessions.java`, `engine/adapter/sqlite/SqliteSessions.java`, `quarkus/.../http/Engine.java`, `HttpApi.java`/new resources, migrations.

**Effort:** 2–4 days · **Risk:** high (schema migration, auth behavior, backward compatibility)

---

#### H11 — Replace ThreadLocal bridges with explicit contexts

**Goal:** Live events, call index and correlation IDs remain correct even across thread switches (virtual threads, subagents, timeouts).

**Findings:** `Engine.LLM_EVENT_SINK`/`LLM_CALL_INDEX` are copied into `HttpProviderAdapter.EVENT_SINK`/`CALL_INDEX`; `LAST_RESPONSE_SUMMARY` and `CorrelationContext` also use ThreadLocals. On worker-thread switches values are lost or misassigned.

**Implementation:**
1. Extend the `ProviderAdapter` API: `ModelRequest` (or a new `ProviderCallContext`) carries `Consumer<ModelEvent> eventSink`, `int callIndex`, `String correlationId`, `String sessionId`.
2. `HttpProviderAdapter` reads these values from the request context instead of static ThreadLocals; move `LAST_RESPONSE_SUMMARY` into the return value/`ModelResponse` field.
3. `Engine` sets the context at call time; no more `ThreadLocal.set`.
4. Propagate `CorrelationContext` explicitly across thread boundaries (executor wrapper, MDC copy) or replace it with a `RequestContext` object.
5. Move SSE publishing out of the provider into the context handler; close cleanly on timeout/interrupt.

**Tests/acceptance:**
- Parallel prompts in two sessions: events land exclusively in the correct session; call indices are correct.
- Subagent in its own thread: correlation ID and session ID remain set.
- Timeout test: no orphaned events after `future.cancel`.
- `grep -R "static final ThreadLocal" engine/src/main/java quarkus/src/main/java` returns only justified exceptions (e.g. `CorrelationContext` as boundary).

**Affected files:** `engine/runtime/provider/client/HttpProviderAdapter.java`, `engine/core/contract/provider/*`, `engine/runtime/step/Step.java`, `engine/runtime/observability/CorrelationContext.java`, `quarkus/.../http/Engine.java`.

**Effort:** 2–3 days · **Risk:** medium (streaming/event behavior)

---

#### H12 — Decide and finish incomplete features

**Goal:** No skeleton code in the production path; a clear decision per feature.

**Findings:** LangChain4j adapter, `SessionWake`, `NoOpTriageService`, subagent dedup.

**Implementation (decision matrix):**

| Feature | Option A: integrate | Option B: remove | Recommendation |
|---|---|---|---|
| LangChain4j | `engine.provider.type=langchain4j` in `ProviderProducer`, test tool/streaming mapping, integration test | Remove adapter + `Assistant` + dependency | **A** if multi-provider/tool-calling is strategically wanted; otherwise **B** |
| `SessionWake` | Execute a synthetic resume prompt for an idle session with pending background results; lock against double runs | Remove callback, document explicit polling | **A** if background subagents are used in production; otherwise **B** |
| Triage | `LlmTriageService` with a small model, timeout, fallback to NoOp, metrics | Keep NoOp and document the feature flag | **A** only with clear operator benefit; otherwise **B** |
| Subagent dedup | Key `(parent, agent, promptHash, TTL)` in `InMemorySubagentSessionRegistry`; `TaskTool` reuses a running child | Keep prompt hint, registry only for tracking | **A** if duplicate subagents are observed |

**Tests/acceptance:**
- For each option: either integration test + config docs or removal including POM/import cleanup.
- No unused class in the `quarkus` module without `@Deprecated`/docs.
- `mvn dependency:analyze` no longer reports an unused LangChain4j dependency.

**Affected files:** `quarkus/.../http/ProviderProducer.java`, `LangChain4jProviderAdapter.java`, `quarkus/.../quarkus/Assistant.java`, `quarkus/pom.xml`; `Engine.java` (SessionWake), `engine/runtime/resilience/*`, `engine/runtime/tool/subagent/*`.

**Effort:** 2–4 days (depending on option) · **Risk:** medium

---

### 4.3 Priority 3 — Quality, UI & documentation

#### H13 — Make the coverage target reliable and introduce a gate

**Goal:** Reproducible coverage measurement; `mvn verify` fails below 80 % (instructions) for `engine`.

**Findings:** Current report 42.74 % (engine) / 10.74 % (quarkus), but artifacts from different runs; no `check` goal in the POM; many packages without tests (`runtime.resilience` 0 %, `runtime.observability` 0 %, `runtime.step` 15.4 %, `adapter.sql` 3.3 %, `adapter.sqlite` 4.6 %).

**Implementation:**
1. Add JaCoCo `check` goal in `engine/pom.xml`: `COVEREDRATIO` ≥ 0.80 for instructions, `haltOnFailure=true`.
2. Define a clean measurement run: `mvn clean test` (no `-DskipTests`, no IDE runs); version/timestamp the report artifacts.
3. Close test gaps, prioritized:
   - `DefaultPreflightChecker`, `DefaultErrorClassifier`, `RetryPolicy`, `LoopDetector`
   - `CorrelationContext`, `ResilienceMetrics`
   - `ToolRuntime` timeout, `Step` parallelism
   - `Sql*`/`Sqlite*` adapters (retention, tokens, idle outcome)
4. Stabilize Quarkus integration tests: retention off in tests (H4), serial execution for DB tests or Testcontainers with a separate DB per class.
5. Optional: publish coverage badge/report in CI.

**Tests/acceptance:**
- `mvn verify` in `qoc/` is green and enforces the 80 % instructions gate for `engine`.
- Fresh report shows ≥ 80 %; the number is reproducible from a single run.
- No flakes across 3 consecutive runs.

**Affected files:** `engine/pom.xml`, `quarkus/pom.xml`, new tests under `engine/src/test/java`, `quarkus/src/test/java`, `application.properties`.

**Effort:** 3–5 days · **Risk:** medium (test flakes, time)

---

#### H14 — Extract the UI from `index.html`

**Goal:** Maintainable UI structure instead of a 2,370-line file; minimal test/lint pipeline.

**Findings:** `index.html` contains HTML, CSS, JS, SSE client, markdown renderer and modals in one file, with no build or test step.

**Implementation:**
1. Split into `index.html`, `styles.css`, `app.js` and modules (`sse.js`, `markdown.js`, `modals.js`, `api.js`).
2. Static delivery via Quarkus (`META-INF/resources/`), no framework introduction needed.
3. Minimal checks: `node --check` for JS modules, optional ESLint/Prettier; a Playwright smoke test (create session, send prompt, receive event) against `quarkus:dev`.
4. Reduce `index.html` to < 300 lines.

**Tests/acceptance:**
- UI functions (session list, prompt, SSE, modals) work unchanged.
- `index.html` < 300 lines; JS modules have no syntax errors.
- Smoke test runs in CI (optional, if Playwright available).

**Affected files:** `quarkus/src/main/resources/META-INF/resources/index.html`, new `*.js`/`*.css` files, possibly `package.json`/CI.

**Effort:** 2–3 days · **Risk:** low–medium (UI regressions)

---

#### H15 — Make documentation and operational values consistent

**Goal:** `settings.md`, `application.properties` and `deploy/.env*` describe the same defaults.

**Findings:** Audit retention 2 days (code/`.env`) vs. 30 days (settings.md secure example); session retention 7 (code) vs. 14 (`deploy/.env`). Preflight/triage/webhook properties are not fully documented.

**Implementation:**
1. Set defaults centrally and keep them in sync across `settings.md`, `application.properties`, `deploy/.env.example`.
2. Document new properties from H2/H12 (`engine.preflight.*`, `engine.triage.*`, `engine.webhook.*`).
3. Add a "Operational values" section with a table (property, default, dev/secure recommendation, effect).
4. Document the retention interval and cleanup strategy (H1/H4).

**Tests/acceptance:**
- Doc review: every property used in `application.properties` has an entry in `settings.md`.
- No contradictory defaults between code, `.env.example` and docs.

**Affected files:** `docs/settings.md`, `docs/resilience.md`, `quarkus/src/main/resources/application.properties`, `deploy/.env.example`.

**Effort:** 0.5 day · **Risk:** low

---

### 4.4 Order, effort and definition of done

| ID | Measure | Priority | Effort | Dependency |
|---|---|---|---:|---|
| H1 | SQL dialect/retention | P1 | 0.5–1 d | — |
| H2 | Preflight cache | P1 | 0.5–1 d | — |
| H3 | Unify executors | P1 | 0.5–1 d | — |
| H4 | Retention off in tests | P1 | 0.25 d | — |
| H5 | Make silent catches visible | P1 | 1 d | — |
| H6 | Engine split | P2 | 3–5 d | H1, H3, H4 |
| H7 | HttpApi split | P2 | 2–3 d | H10 |
| H8 | Persistence into adapter | P2 | 1–2 d | H1 |
| H9 | SQL/SQLite dedup | P2 | 2–3 d | H1, H8 |
| H10 | Session isolation | P2 | 2–4 d | — |
| H11 | Remove ThreadLocals | P2 | 2–3 d | H3 |
| H12 | Unfinished features | P2 | 2–4 d | Decision |
| H13 | Coverage gate | P3 | 3–5 d | H4, H5, H6–H11 |
| H14 | UI split | P3 | 2–3 d | — |
| H15 | Doc consistency | P3 | 0.5 d | H1, H2, H12 |

**Recommended sprints:**

1. **Sprint 1 (P1, ~3–4 days):** H1, H2, H3, H4, H5 — correctness and deterministic tests.
2. **Sprint 2 (P2 Security/Architecture, ~7–12 days):** H8, H10, H11, H12 — persistence, isolation, concurrency, feature decisions.
3. **Sprint 3 (P2/P3 structure, ~12–20 days):** H6, H7, H9, H13, H14, H15 — God-classes, dedup, coverage, UI, docs.

**Definition of done for closing the review:**

- `mvn test` in `qoc/` green; 3 runs without flakes.
- `mvn verify` enforces the 80 % instructions gate for `engine`.
- No `now() - interval` and no persistence SQL outside the adapters (readiness/preflight health checks as a documented exception); ArchUnit in both modules.
- Two-user isolation test green (user B → 403/404, admin → access).
- No `catch (ignored)` blocks in critical paths without justification.
- `Engine.java` < 800 lines, `HttpApi.java` < 300 lines, `index.html` < 300 lines.
- `docs/settings.md`, `application.properties` and `deploy/.env.example` are consistent.

---

## 5. Verification protocol (as of 2026-11-09)

| Checkpoint | Result | Evidence |
|---|---|---|
| LOC God-classes | confirmed | `wc -l` |
| Java files/LOC total | confirmed (597 / 55,717) | `find`/`wc` |
| ArchUnit rules engine | confirmed (13 rules, incl. `java.sql` rule) | `engine/.../arch/ArchitectureTest.java` |
| ArchUnit rules Quarkus | confirmed (only 2 rules, no SQL rule) | `quarkus/.../arch/ArchitectureTest.java:18-23` |
| Direct SQL in `Engine`/`Durable*` | confirmed | `Engine.java:845,852,1092-1107` (DELETE), `1540,1835` (readiness/preflight JDBC); `Durable*Source` |
| PostgreSQL `interval` in SQLite | confirmed (query fails, gets logged) | `Engine.java:1095,1107`; `migration-sqlite/V3,V7` |
| SQL/SQLite duplicates | confirmed (11 pairs) | `adapter/sql` vs. `adapter/sqlite` |
| Executor per tool call/step | confirmed | `ToolRuntime.java:100`, `Step.java:132` |
| ThreadLocal coupling | confirmed | `Engine.java:160-163,1801-1804`; `HttpProviderAdapter.java:49-51`; `CorrelationContext.java:11-12` |
| Raw threads | confirmed | `Engine.java:514,756`; `DurableQuestionSource.java:99`; `HttpApi.java:1048` |
| LangChain4j unused | confirmed | `ProviderProducer.java:49-66`; no `engine.provider.type` reference |
| `SessionWake` stub | confirmed | `Engine.java:514-523` |
| `NoOpTriageService` | confirmed | `Engine.java:739` |
| Subagent registry wired | **Correction:** yes, only untracked; dedup missing | `Engine.java:466-467,885,2368,2475,2484` |
| 47 silent catches | confirmed | `grep` in `engine`/`quarkus` |
| Session isolation | **Correction:** list/get/delete + 8 endpoints protected; messages/questions/permissions unprotected | `HttpApi.java:160,181,195,241,268,370,426,447,552,574,586,608,620,1185` |
| `isOwner`/admin bypass | confirmed | `HttpApi.java:1518-1531` |
| Preflight per turn | confirmed (DNS + HEAD, no TTL; 404/405 treated as reachable) | `DefaultPreflightChecker`; `Engine.java:1829` |
| PortableScanner | confirmed (no variable expansion, not quote-aware) | `PortableScanner.java` |
| Native workaround | confirmed | `deploy/Dockerfile.native:8`; `MainEventBusProcessor` in the A2A SDK |
| Test numbers | confirmed (763 engine / 122 Quarkus, 0 failures) | Surefire reports 2026-11-08 |
| Coverage 42.74 % / 10.74 % | confirmed, but artifacts from different runs | `jacoco.csv`, `jacoco.exec`, Surefire timestamps |
| 80 % gate in POM | **Correction:** not present; only AGENTS.md rule | `engine/pom.xml` (only `prepare-agent`/`report`) |
| `PSQLException` in reports | not reproduced (0 hits) | Surefire reports |
| Retention test overrides | confirmed: no `%test` overrides | `application.properties` |
| Dirty working tree | confirmed (5 modified, 2 untracked) | `git status` |

---

## 6. Addendum 2026-11-22

The review above is dated 2026-11-09; line numbers may have shifted. The repository has
since grown a workflow stack, RBAC/security, structured outputs, MCP-server persistence,
an ACP adapter, tool configuration and a saga/undo MVP. The following table records what
changed; it does **not** replace a fresh review of the original findings.

### 6.1 New since 2026-11-09

| Change | Effect |
|---|---|
| **Workflow stack** | `workflow-runner` (graph compiler, executor, stores, sub-workflows, node contracts, context agents, structured output, generator) and `reactflow` `/workflow/` UI (workflows, runs, agents, MCP servers, settings). `docs/engineering/Workflow.md`. |
| **RBAC & security** | Principal propagation, HMAC tool tokens, `SecureRest`, A2A/MCP token forwarding, `Principal.system()`, system-token auth, `ownerId`/`rolesAllowed`, `ExecutionGuards`/`SessionAccessGuard`, ownership integration tests. `docs/engineering/Workflow-OIDC.md`. |
| **Structured output** | Java/YAML/OpenAPI schema sources, `strict` flag, automatic `contextRefs`, configurable output limit. `docs/engineering/Structured-Output.md`. |
| **MCP server persistence** | V12 migration, `SqlMcpServerStore`, CRUD/reload API and UI page. `docs/engineering/MCP-UI-Integration.md`. |
| **ACP adapter** | JSON-RPC over stdio; `AcpHandler`, `AcpSessionManager`, `AcpServer`, `AcpTransport`; session/new, prompt, cancel, load, list, delete, close, config, mode, auth. `docs/plans/acp-adapter.md`. |
| **Tool configuration** | Dynamic tool list, presets (`readonly`, `retrieval`, `editor`, `full`, `context-agent`), permission-check endpoint, `rolesAllowed` vs `permissions`, least-privilege default. `docs/plans/tool-configuration.md`. |
| **Saga/undo MVP** | `UndoSpec`/`UndoRegistry`/`UndoToolFactory`/`UndoRuntime`, generated `undo_<tool>`, reference pairs `write → delete` (creations only) and `worktree_create → worktree_remove`. Complete plan: `docs/plans/saga-orchestration.md` (M5–M10, opt-in `SagaPolicy`). |
| **`ToolRuntime` correctness fix** | `Empty`/`InvalidArgument`/`Unavailable` are no longer cast to `Ok`; they pass through unchanged. Removes a latent `ClassCastException`. |
| **Permission action alignment** | `write`/`edit`/`patch`/`delete` use action `edit`; `ContextBuilder.filterDeniedTools` uses the permission action. `.env` edit protection is effective. |
| **Skill tool visibility** | `skill`/`skill_list` use dedicated actions (`skill_load`/`skill_list`); skill-ID rules still apply in `SkillTool.execute`/`list`. |
| **Deployment** | GCP native memory, AWS ECS, docker-dev/docker-dev-native, Keycloak OIDC; workspace points at the `qoc` folder; sandbox fallback when `bwrap` is unavailable. |
| **UI/mobile** | Chat workspace switcher, files/settings pages, i18n (DE/EN), mobile Send/Cancel fix (`uid()` in insecure contexts). |

### 6.2 Corrections / re-verify

- **Code size (2026-11-22):** 582 Java files / ~52k LOC in main sources of
  `engine`, `quarkus`, `cli` and `workflow-runner` (excludes tests and the React/TS UI).
  The original 597/55,717 figure had a different scope and module set.
- **Test base:** 1191 engine tests / 143 Quarkus tests, 0 failures (2026-11-22);
  engine instruction coverage is **80.26%**. The original 763/122 numbers are obsolete.
- **Session isolation (§3.7.2):** RBAC guards and ownership tests were added. The specific
  endpoints listed in the original finding should be re-verified against
  `SessionAccessGuard`/`ExecutionGuards`; do not treat the old “partially implemented”
  statement as current without a fresh check.
- **God-classes (§3.1):** the real God class is `quarkus/.../Engine.java` (3,558 lines);
  `QocEngine` is a 1,579-line facade, and `ContextBuilder` is 299 lines / 6 fields.
  The architecture review's "16k-line ContextBuilder" was a byte/line mix-up (see §7.4).
- **Direct SQL (§3.2):** `SagaStore` is planned as a port; the complete-saga plan explicitly
  keeps saga persistence out of the Quarkus outer skin. Existing direct SQL paths are
  unchanged.
- **Tool runtime completeness:** the non-`Ok` outcome bug is fixed; `Empty`/`InvalidArgument`/
  `Unavailable` are valid tool outcomes and should be handled by new tools.

The architectural recommendations in §4 remain open unless a later change explicitly
closed them. The workflow/RBAC/tooling work did not attempt the God-class split or the
SQL adapter de-duplication.

---

## 7. Architecture Review 2026-11-22 (engine)

> **Source:** `docs/tmp/architecture-review.md` (scratch, gitignored).
> This section is the **validated, durable** summary. The scratch file was re-checked
> against the code before incorporation; several quantitative claims were wrong
> (bytes vs. lines, stale line numbers) and the existing ArchUnit test was overlooked.
> The second-round self-corrections from the scratch file (§7) are folded in below.

### 7.1 Scope and method

| Module | Main Java files | Main LOC | Role |
|---|---:|---:|---|
| `engine` | 411 | 27,235 | Core + agent runtime + adapters |
| `quarkus` | 101 | 15,737 | Outer skin (HTTP/SSE, OIDC, UI, MCP/A2A/ACP) |
| `workflow-runner` | 60 | 8,423 | Workflow layer (graph compiler, executor, stores) |
| `cli` | 10 | 701 | HTTP client, no engine dependency |

Method: `grep` for import directions, `wc -l`/`wc -c` for sizes, inspection of the
existing ArchUnit tests, and a consumer check across `quarkus`/`workflow-runner`.
The engine test tree contains 188 Java files.

### 7.2 Strengths (validated)

- Hexagonal ports/adapters: `core.port.{store,world,human,runtime}` and `adapter.*`.
- Pure Java core: no Quarkus/Jakarta/Jackson imports in `engine/src/main/java`.
- Event sourcing with `SessionEvent` as the source of truth; sealed domain types and
  records; `Option<T>` instead of `null`.
- Claim-based concurrency, journal-before-side-effect, idempotency keys, tool-result
  cache, clock abstraction.
- Deny-by-default permissions, scoped tool registry with epochs, frozen tool snapshots,
  compaction with epoch, Flyway migrations, Testcontainers.
- The **engine** is consumed by **both** `quarkus` and `workflow-runner`; it imports
  neither. The dependency direction is clean except for one `core.wiring` leak (§7.3).

### 7.3 Validated findings

| # | Finding | Evidence | Severity |
|---|---|---|---|
| **L1** | `core.wiring.ToolDependencies` imported `runtime.tool.registry.ToolRegistry` | Fixed 2026-11-22 by A1 (`ToolRegistrar`); formerly `core/wiring/ToolDependencies.java:6` | Closed |
| **L2** | Task/subagent boundary is mixed | 6 classes in `runtime/tool/subagent`; used by `quarkus`, not by `workflow-runner`; `Layering.md` §7 targets `runtime/workflow` (wrong module model) | Medium |
| **L3** | ArchUnit existed, but the `core.wiring` slice was not covered | Fixed 2026-11-22 by A2; formerly S0–S2 omitted `core.wiring` | Closed |
| **L4** | `QocEngine` facade ergonomics | 1,579 lines, 29 `private final` fields, 23-param ctor, 4 `create` overloads, 43 public methods; `EngineConfig` 18 fields | Medium |
| **L5** | Silent no-ops in minimal mode | `NoOp*` adapters return empty results | Medium |
| **L6** | Error channels are `RuntimeException` | `PermissionDenied`, `Aborted`, `Defect`, `StoreFailure`, `PausedException` | Low (documented trade-off) |
| **L7** | No documented lock ordering | 37× `synchronized`, 22× `ReentrantLock`, 17× `volatile`, 31× `Atomic*` | Low |
| **L8** | Metrics are ad-hoc `AtomicLong` | 5 counters in `QocEngine` | Low–Medium |
| **L9** | `quarkus.Engine` is the real God class | 3,558 lines | High (already in §3.1) |
| **L10** | `workflow-runner` bypasses engine ports for SQL dialects | imports `engine.adapter.sql.SqlDialect`, `PostgresDialect`, `SqliteDialect`, `StorageFactory`, `SystemClock`, `adapter.memory.RuntimeState` | Low–Medium (trade-off) |

**L1 was the only internal import-direction violation.** After A1 (2026-11-22):
`adapter → runtime`: 0; `runtime → adapter`: 0; `core → adapter`: 0;
`core → runtime`: 0 (was 1, `ToolDependencies`); `engine → quarkus/workflow-runner/cli`: 0.

**L10 is a consumer-level trade-off, not an engine violation.** `workflow-runner` is an
outer consumer and may use `engine.adapter`, but sharing `SqlDialect` couples its stores
to engine internals. If the port boundary is the goal, the dialect helper should move to
a neutral utility or `workflow-runner` should use the engine's `Journal`/`SessionStore`
ports. The current choice is pragmatic because `workflow-runner` has its own schema.

### 7.4 Validation corrections (must not be lost)

The scratch review's quantitative table is **not** line counts. It mixed `wc -l` for
`QocEngine` with byte sizes for the other files:

| File | Claimed "lines" | Actual lines | Actual bytes |
|---|---:|---:|---:|
| `QocEngine.java` | 1,579 | **1,579** | 98,986 |
| `ContextBuilder.java` | 16,015 | **299** | 16,015 |
| `ContractCodec.java` | 16,172 | **408** | 16,172 |
| `Json.java` | 14,512 | **356** | 14,512 |
| `NioFileSystem.java` | 14,433 | **383** | 14,433 |
| `McpDiscoveryClient.java` | 14,512 | **292** | 14,512 |
| `GrepTool.java` | 10–13k | **279** | 13,526 |
| `EmailTool.java` | 10–13k | **264** | 13,175 |
| `WorktreeTool.java` | 10–13k | **207** | 10,883 |
| `UndoRuntime.java` | 10–13k | **278** | 13,001 |
| `PermissionEngine.java` | 11,909 | **251** | 12,010 |
| `CompactionService.java` | 11,901 | **234** | 11,909 |
| `SkillCatalog.java` | 11,561 | **283** | 11,561 |
| `ToolRegistry.java` | 12k | **133** | 4,819 |

Consequences:

- **`ContextBuilder` is not a 16k-line God class.** It has 299 lines and 6 fields
  (`conversation`, `skills`, `instructionLoader`, `instructionStore`, `skillFilter`,
  `defaultPermissions`). The decomposition recommendation (A8) is **withdrawn**.
- **`QocEngine` numbers:** 29 `private final` fields (23 injected + 5 counters + 1
  agent map), 23-param private constructor, 4 `create` overloads, 43 public methods —
  not "~40 fields / 25 params / 7 overloads / 28 methods".
- **Engine size:** 411 main Java files / 27,235 lines, not 833 files.
- **ArchUnit:** an engine test **does exist**
  (`engine/src/test/java/de/augmentia/oc/engine/arch/ArchitectureTest.java`) and covers
  S0–S2, runtime→adapter, adapter→runtime, adapter-only-by-wiring, package cycles,
  `java.sql` confinement, Quarkus/HTTP freedom, `Impl` suffix, standard streams, generic
  exceptions and port interfaces. The gap was narrower: **`core.wiring` was not in
  S0–S2**, so L1 passed. Fixed on 2026-11-22 with a dedicated `core.wiring` rule (not by
  adding the package to S0; see A2). The scratch claim "no ArchUnit tests" is wrong.
- **Workflow layer:** the workflow layer is the **`workflow-runner` module**, not
  `engine.runtime.workflow`. The engine's `runtime/workflow` contains only
  `WorkflowStepProvider` (a bridge). The scratch table's "Workflow-Orchestrierung |
  runtime/workflow/*" is a category error.
- **`MAX_CONCURRENT_SUBAGENTS`:** `quarkus/.../Engine.java:2229`, not `Engine.java:2249`
  (line drift). It is an outer-skin constant and a `Semaphore`; see A4.
- **Real God class:** `quarkus.Engine` (3,558 lines), already tracked in §3.1. The
  architecture review should not redirect that finding to `ContextBuilder`.

### 7.5 Second-round self-corrections (incorporated)

| # | Original claim | Validated correction |
|---|---|---|
| 1 | `QocEngine` is a God class | Facade/composition root; the issue is **constructor ergonomics**, not diffuse responsibility. |
| 2 | `ContextBuilder` mixes too many responsibilities | 299 lines / 6 fields; cohesive; **no decomposition needed**. |
| 3 | Error channels as `RuntimeException` are a problem | Deliberate, documented 3-channel trade-off; record as ADR, not a defect. |
| 4 | `file` mode with in-memory runtime is buggy | Documented behavior; operator-UX/data-loss risk, not a correctness bug. |
| 5 | Adapters have no transactions | Consistent with event sourcing; the obligation is recovery reconciliation. |
| 6 | Engine imports Quarkus/Jakarta | 0 imports; the "pure core" claim holds. |
| 7 | Adapters import runtime | 0 imports; the only violation was `core → runtime` in `ToolDependencies` (fixed 2026-11-22). |
| 8 | No ArchUnit tests | Wrong: engine and quarkus tests exist; the `core.wiring` slice gap was fixed 2026-11-22. |
| 9 | `ContextBuilder` is a God class | Withdrawn (see #2). |
| 10 | QOC has no vector/graph memory | Out of scope for this review; a missing feature, not an architecture defect. |

### 7.6 Revised, targeted improvements (order matters)

**Status 2026-11-22:** **A1 and A2 are implemented.** Engine: 1,191 tests, 0 failures,
4 skipped; workflow-runner: 233 tests, 0 failures, 1 skipped; quarkus: 143 tests,
0 failures, 23 skipped. A3–A8, A10 and A11 remain open; A9 is withdrawn. The original
order note still applies to anyone redoing the change: **do A1 before A2** — adding the
dedicated `core.wiring` rule while L1 still exists would make the build red. Fix the leak
first, then add the gate.

| # | Measure | Effort | Impact | Concrete change |
|---|---|---|---|---|
| **A1** | Decouple `ToolDependencies` from `ToolRegistry` | S | Core becomes runtime-free | Add `core.contract.tool.ToolRegistrar` (`void register(Consumer<ToolDefinition> sink)`); `ToolDependencies` holds it; `QocEngine.create` calls `toolRegistrar.register(registry::register)`; `quarkus.Engine` passes a sink lambda. `workflow-runner` is unaffected. |
| **A2** | Tighten the existing engine ArchUnit test | S | Prevents L1 regression | Add a **dedicated** rule: `..core.wiring..` must not depend on `..runtime..`/`..adapter..`. **Do not** add it to S0: S0 forbids `core.contract`, and `ToolDependencies` legitimately needs `ToolDefinition`/`ToolRegistrar` from `core.contract.tool`. Extend `engineStaysFreeOfQuarkusAndHttp` with `de.augmentia.qoc.workflowrunner..` and `de.augmentia.oc.engine.http..`. No new dependency needed (`archunit-junit5` is already in `engine/pom.xml`). |
| **A3** | Split task orchestration from tool adapters | M | Real boundary, both consumers keep working | New `runtime/task`: `BackgroundTasks`, `SubagentSessionRegistry`, `InMemorySubagentSessionRegistry`, `SubagentRunner`; keep `SubagentTool`/`TaskTool` in `runtime/tool/subagent` as adapters. Update `quarkus` imports and `NativeReflectionConfig`. **Do not** move to `runtime/workflow` or `workflow-runner`: `workflow-runner` does not use these classes, and `quarkus` agent chat does. |
| **A4** | Move the subagent quota into the engine | S–M | Removes policy from the outer skin | Replace `MAX_CONCURRENT_SUBAGENTS` + `Semaphore` in `quarkus.Engine` with a `runtime.task.TaskQuota` (configurable, default 4); `quarkus` passes the config. |
| **A5** | Capability flags for minimal mode | S | Operator UX | Add `capabilities()`/`isSupported()` or throw explicit `Unsupported` instead of silent `NoOp*`. |
| **A6** | Builder for `QocEngine`/`EngineConfig` | M | Ergonomics | Keep `create(...)` overloads as delegating factories; validate in one builder. |
| **A7** | Document lock ordering | S | Deadlock prevention | Add a short section to `Core.md`/`InMemory.md`: session lock → claim lock → journal; never reverse. |
| **A8** | `MetricsPort` | M | Embedded observability | Port + adapter for Micrometer/OTel; keep `AtomicLong` as the memory adapter. |
| **A9** | `ContextBuilder` decomposition | — | **Withdrawn** | 299 lines / 6 fields; no action. |
| **A10** | Correct `Layering.md` §7/§8 | S | Docs match code | State that the workflow layer is `workflow-runner`; `engine.runtime.workflow` is a bridge; task primitives stay in `engine.runtime.task`. |
| **A11** | Split `quarkus.Engine` | L | The real God class | Separate HTTP resources, wiring, MCP/A2A/ACP, task endpoints; preserve behavior. This is the existing §3.1 work, not a new finding. |

### 7.7 Module boundary matrix (validated)

| From → To | `engine.core` | `engine.runtime` | `engine.adapter` | `workflow-runner` | `quarkus` | `cli` |
|---|---|---|---|---|---|---|
| `engine.core` | — | **1 violation (L1)** | 0 | 0 | 0 | 0 |
| `engine.runtime` | allowed | — | 0 | 0 | 0 | 0 |
| `engine.adapter` | allowed | 0 | — | 0 | 0 | 0 |
| `workflow-runner` | allowed | allowed | allowed (L10) | — | 0 | 0 |
| `quarkus` | allowed | allowed | allowed | allowed | — | 0 |
| `cli` | 0 | 0 | 0 | 0 | HTTP only | — |

Rules to encode (A2): engine must not depend on `quarkus`, `workflow-runner` or `cli`;
`core.wiring` must not depend on `runtime`/`adapter` (dedicated rule — **not** part of S0,
which forbids `core.contract`); `adapter` must not depend on `runtime`; `runtime` must
not depend on `adapter`. The first two are the ones that matter for the dual-consumer
setup (`quarkus` and `workflow-runner` both consume engine, never the reverse).

**Consumer-side enforcement is separate.** The engine ArchUnit test only sees
**outgoing** imports from `de.augmentia.oc.engine..`; it cannot check what
`workflow-runner` or `quarkus` import. The existing `quarkus` ArchUnit test analyzes only
`de.augmentia.oc.engine.http`, not `de.augmentia.qoc.workflowrunner`. To make the
consumer boundary mechanical, add small tests in the consumer modules:

- `workflow-runner`: allowed `engine.core`, `engine.runtime`, `engine.adapter`; forbidden
  `quarkus`, `cli`.
- `quarkus`: allowed `engine`, `workflow-runner`; forbidden `cli`.
- `cli`: no `engine`, no `workflow-runner` (HTTP only).

If the goal is also "`workflow-runner` uses ports, not adapters", L10 must be resolved
first; otherwise the rule would fail on `SqlDialect`/`StorageFactory`. That is a
deliberate follow-up decision, not part of A1/A2.

### 7.8 A1/A2 implementation (done 2026-11-22)

**A1 — core-owned registrar**

```java
// engine/src/main/java/de/augmentia/oc/engine/core/contract/tool/ToolRegistrar.java
package de.augmentia.oc.engine.core.contract.tool;

import java.util.function.Consumer;

/** Core-owned callback: the runtime supplies a sink; the host emits definitions. */
@FunctionalInterface
public interface ToolRegistrar {
    void register(Consumer<ToolDefinition> sink);
}
```

```java
// core/wiring/ToolDependencies.java
import de.augmentia.oc.engine.core.contract.tool.ToolRegistrar;
// ...
ToolRegistrar toolRegistrar
// compact ctor: if (toolRegistrar == null) toolRegistrar = sink -> {};
// legacy ctor/none(): this(..., sink -> {});
```

```java
// runtime/QocEngine.java (create)
if (toolDeps != null && toolDeps.toolRegistrar() != null) {
    toolDeps.toolRegistrar().register(registry::register); // Registration is discarded
}
```

```java
// quarkus/.../Engine.java
new ToolDependencies(..., sink -> {
    for (var tool : mgr.discovered(serverName)) {
        sink.accept(McpTool.definition(serverName, tool.name(), tool.description(),
                tool.inputSchema(), client));
    }
});
```

**A2 — ArchUnit**

```java
@ArchTest
static final ArchRule wiringDoesNotDependOnRuntimeOrAdapter = noClasses()
        .that().resideInAPackage("..core.wiring..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("de.augmentia.oc.engine.runtime..", "..adapter..");
```

Extend `engineStaysFreeOfQuarkusAndHttp` with
`de.augmentia.qoc.workflowrunner..` and `de.augmentia.oc.engine.http..`.

**Verification**

```bash
mvn -pl engine test -Dtest=ArchitectureTest
mvn -pl engine test
mvn -pl quarkus test -Dtest=ArchitectureTest
```

`workflow-runner` does not use `ToolDependencies`, so it needed no change; its build is
green (`mvn -pl workflow-runner test`). If A2 is added before A1, the engine ArchUnit
test will fail on the existing `ToolDependencies` import — hence the order.

**Result (2026-11-22):** `engine` 1,191 tests green (4 skipped), `workflow-runner` 233
green (1 skipped), `quarkus` 143 green (23 skipped). The new rule
`wiringDoesNotDependOnRuntimeOrAdapter` is part of
`engine/.../arch/ArchitectureTest.java`; `ToolDependencies` no longer imports
`runtime.tool.registry.ToolRegistry`. `engineStaysFreeOfQuarkusAndHttp` now also guards
against `de.augmentia.qoc.workflowrunner..` and `de.augmentia.oc.engine.http..`.
