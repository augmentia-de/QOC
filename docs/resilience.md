# resilience.md — Robust Agent Infrastructure for QOC

## 1. Objective

Deterministic infrastructure layer ("wrapper") around the probabilistic LLM agent.
Absorbs failures (network, permissions, timeouts, infinite loops), persists
state durably, and escalates automatically when errors cannot be resolved.

## 2. Architecture Overview

Six phases, sequential or monitored:

1. **Pre-Flight Checks** — proactive validation before task start
2. **State & Checkpoint Initialization** — persistence setup
3. **Execution Loop with Timeouts & Watchdogs** — runtime monitoring
4. **Error Classification & Smart Retry** — transient vs. permanent errors
5. **LLM Error Analysis & Self-Healing** — triage loop for solvable errors
6. **Escalation, Alerting & HITL** — on final failure

## 3. Phases in Detail

### Phase 0: Housekeeping (Bug Fixes)

#### 0.1 Set idle_outcome
- **Problem:** No session has `idle_outcome` set (always NULL).
- **Fix:** In `Engine.runPrompt()`, after TurnOutcome computation, call `sessions.put()` with idle_outcome.
- **Files:** `Engine.java` (runPrompt, ~line 1925)
- **Effort:** 0.5 day

#### 0.2 Subagent Deduplication
- **Problem:** 7× Architecture, 4× Execution subagents for one step.
- **Fix:** Before `runner.run()`, check whether a session for the agent exists; add a prompt-level rule in the orchestrator.
- **Files:** `SubagentTool.java`, `SubagentRunner.java`, Orchestrator system prompt
- **Effort:** 1 day

#### 0.3 Auto-delete empty sessions
- **Problem:** Sessions with 0 messages remain in the DB.
- **Fix:** At the end of `runPrompt()`: if `messageCount == 0`, call `sessionRetention.delete(id)`.
- **Files:** `Engine.java`, `SessionRetention.java`
- **Effort:** 0.5 day

#### 0.4 Fix Question-Resolution bug
- **Problem:** REST API response to a question does not resume the blocked turn.
- **Root Cause:** `DurableQuestionSource.ask()` blocks on `webQuestionSource.ask()` (CompletableFuture). The API calls `resolve()`, but the blocking thread is not woken up.
- **Fix:** Callback-based resolution instead of polling; `WebQuestionSource.ask()` without timeout; API resolution completes the future correctly.
- **Files:** `DurableQuestionSource.java`, `WebQuestionSource.java`, `HttpApi.java`
- **Effort:** 1–2 days

#### 0.5 Clean up Approval Cache
- **Problem:** 180 `pending_approval` entries (all DECIDED), no cleanup.
- **Fix:** Periodically (daily): `DELETE FROM pending_approval WHERE state='DECIDED' AND created_at < NOW() - INTERVAL '7 days'`.
- **Files:** `SessionRetention.java` or new `ApprovalRetention.java`
- **Effort:** 0.5 day

#### 0.6 Tool-Result-Cache Eviction
- **Problem:** 1541 entries, no eviction.
- **Fix:** TTL or LRU in `ToolResultCache`.
- **Files:** `SqliteToolResultCache.java`, `SqlToolResultCache.java`
- **Effort:** 0.5 day

#### 0.7 Activate snapshots
- **Problem:** 0 snapshots in DB (feature disabled).
- **Fix:** Set `ENGINE_REVERT_SNAPSHOT=true` in `.env`.
- **Effort:** 0.5 day

### Phase 1: Core Resilience (Pre-Flight + Retry)

#### 1.1 PreflightChecker
- **New class:** `engine.runtime.resilience.PreflightChecker`
- **Checks:** DNS lookup, HTTP health, API key validation, DB connectivity, workspace writability
- **Config:** `ENGINE_PREFLIGHT_ENABLED` (default: true)

#### 1.2 ErrorClassifier
- **New class:** `engine.runtime.resilience.ErrorClassifier`
- **Taxonomy:** TRANSIENT (timeout, 429, 5xx), PERMANENT (403, 400), CORRECTABLE (KeyError, IllegalArgumentException), CRITICAL (loop)
- **Config:** none new (internal)

#### 1.3 RetryPolicy (Exponential Backoff + Jitter)
- **New class:** `engine.runtime.resilience.RetryPolicy`
- **Formula:** `t = min(Tmax, Base × 2^attempt) + Jitter(0..JitterMax)`
- **Config:** `ENGINE_RETRY_BASE_DELAY_MS` (1000), `ENGINE_RETRY_MAX_DELAY_MS` (60000), `ENGINE_RETRY_JITTER_MS` (5000)

#### 1.4 Engine Integration
- Preflight before run loop
- ErrorClassifier + RetryPolicy in retry logic
- Config: `ENGINE_RETRY_MAX_ATTEMPTS` (3)

### Phase 2: Safety (Timeouts + Loop Detection)

#### 2.1 Tool-Level Hard Timeouts
- **Change:** `ToolRuntime.execute()` wraps tool execution in `Future.get(timeout)`
- **Config:** `ENGINE_STEP_TIMEOUT_SECONDS` (30)

#### 2.2 LoopDetector
- **New class:** `engine.runtime.resilience.LoopDetector`
- **Detects:** same tool name + same input parameters N× in a row
- **Config:** `ENGINE_LOOP_DETECTION_THRESHOLD` (5)

### Phase 3: Self-Healing (Triage)

#### 3.1 TriageService
- **New class:** `engine.runtime.resilience.TriageService`
- **Prompt:** Stack trace + tool output + state → JSON correction suggestion
- **Config:** `ENGINE_TRIAGE_ENABLED` (false), `ENGINE_TRIAGE_MAX_ATTEMPTS` (3), `ENGINE_TRIAGE_MODEL`

### Phase 4: Escalation (Webhook + HITL)

#### 4.1 WebhookAlerter
- **New class:** `engine.adapter.alerting.WebhookAlerter`
- **Formats:** Slack, Telegram, Generic JSON
- **Config:** `ENGINE_WEBHOOK_ENABLED` (false), `ENGINE_WEBHOOK_URL`, `ENGINE_WEBHOOK_FORMAT`

#### 4.2 HITL Action Links
- "Grant permission temporarily" → `/api/approval/{token}/grant`
- "Continue task" → `/api/session/{id}/resume`
- "Cancel task" → `/api/session/{id}/interrupt`

## 4. New Config Properties

```properties
# Phase 0
ENGINE_REVERT_SNAPSHOT=true

# Phase 1
ENGINE_PREFLIGHT_ENABLED=true
ENGINE_RETRY_MAX_ATTEMPTS=3
ENGINE_RETRY_BASE_DELAY_MS=1000
ENGINE_RETRY_MAX_DELAY_MS=60000
ENGINE_RETRY_JITTER_MS=5000

# Phase 2
ENGINE_STEP_TIMEOUT_SECONDS=30
ENGINE_LOOP_DETECTION_THRESHOLD=5

# Phase 3
ENGINE_TRIAGE_ENABLED=false
ENGINE_TRIAGE_MAX_ATTEMPTS=3
ENGINE_TRIAGE_MODEL=

# Phase 4
ENGINE_WEBHOOK_ENABLED=false
ENGINE_WEBHOOK_URL=
ENGINE_WEBHOOK_FORMAT=generic
```

## 5. New Components

| Component | Package | Description |
|---|---|---|
| `PreflightChecker` | `engine.runtime.resilience` | DNS, health, auth validation |
| `ErrorClassifier` | `engine.runtime.resilience` | Error taxonomy |
| `RetryPolicy` | `engine.runtime.resilience` | Exponential backoff + jitter |
| `LoopDetector` | `engine.runtime.resilience` | Detects repeated tool calls |
| `TriageService` | `engine.runtime.resilience` | LLM-based error analysis |
| `WebhookAlerter` | `engine.adapter.alerting` | Slack/Telegram/webhook |
| `ApprovalRetention` | `engine.runtime.resilience` | Cleanup of decided approvals |

## 6. Effort & Order

| Phase | Effort | Priority |
|---|---|---|
| Phase 0 (Housekeeping) | 4–5 days | **High** |
| Phase 1 (Core Resilience) | 3–5 days | **High** |
| Phase 2 (Safety) | 2 days | **Medium** |
| Phase 4 (Escalation) | 2–3 days | **Medium** |
| Phase 3 (Self-Healing) | 3–5 days | **Low** |

**Total: ~15–20 days**

### Recommended order
1. Phase 0 (Bug fixes, immediate value)
2. Phase 1 (Core Resilience)
3. Phase 2 (Safety)
4. Phase 4 (Escalation, simple, high value)
5. Phase 3 (Self-Healing, complex, high value)

## 7. Open Decisions

1. Should Phase 5 (Checkpoint/Resume) be dropped? QOC already has session persistence.
2. Should the triage service use a separate, faster model?
3. Which webhook format has priority (Slack/Telegram/Generic)?
4. Should question resolution run over WebSocket instead of REST polling?
5. Should `ENGINE_REVERT_SNAPSHOT` default to `true`?

## 8. Verification

After each phase:
- `mvn test` green
- JaCoCo Coverage ≥ 80%
- ArchUnit rules for new packages
- Integration test: `qoc run --resilient "test prompt"` with simulated error
- DB check: `idle_outcome` set, no empty sessions, no orphaned approvals
`, "path">
/home/torsten/dev/my-projects/2oc/qoc/docs/resilience.md