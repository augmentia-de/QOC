# Java-Impl – implementation template

> **This file** is the operational implementation template for a **new, independent**
> implementation of the Session Engine in **Java 21 + Quarkus**.
>
> It contains: the **corrected spec sections** (S-1…S-6 from
> [`11-java-portability.md`](./11-java-portability.md)), the **open decisions
> with proposal** (L-A…L-E), the module structure, the data models
> as Java code, the core algorithm skeletons, the SQL schema, the
> test strategy and the acceptance checklist.
>
> **Basis:** [`01-types.md`](./01-types.md) … [`07-invariants.md`](./07-invariants.md)
> (111 invariants), checked in [`09-invariants-audit.md`](./09-invariants-audit.md).
>
> **Not included:** complete source code, provider adapters,
> surfaces, deployment. **No port** – the implementation is freely
> designed; where the spec makes no prescription, a **proposal** stands, marked
> with ⚠️.
>
> **Status:** 2026-10-22 · Java 21 (virtual threads final, pattern-matching switch
> final) · Quarkus 3.x · PostgreSQL 16 + Flyway

---

## Contents

- [1. Scope](#1-scope)
- [2. The six spec corrections – already incorporated here](#2-the-six-spec-corrections-already-incorporated-here)
- [3. The open decisions – with proposal](#3-the-open-decisions-with-proposal)
- [4. Module structure](#4-module-structure)
- [5. Data model in Java 21](#5-data-model-in-java-21)
- [6. Persistence](#6-persistence)
- [7. Permissions](#7-permissions)
- [8. Tool chain](#8-tool-chain)
- [9. Execution, claim, recovery](#9-execution-claim-recovery)
- [10. Provider interface](#10-provider-interface)
- [11. Live feed (SSE)](#11-live-feed-sse)
- [12. Test strategy](#12-test-strategy)
- [13. Build plan](#13-build-plan)
- [14. Acceptance checklist](#14-acceptance-checklist)
- [15. The five most common errors in this implementation](#15-the-five-most-common-errors-in-this-implementation)
- [16. Open for the domain decision](#16-open-for-the-domain-decision)
- [17. SessionError taxonomy (from the reference checkout)](#17-sessionerror-taxonomy-from-the-reference-checkout)
- [18. Debugging checklist and pitfalls](#18-debugging-checklist-and-pitfalls)

## 1. Scope

| Content | Content |
|---|---|
| **Yes** | event log, two projection layers, inbox with delivery modes, execution claim, recovery, tool chain, permissions, compaction, live feed, provider interface |
| **No** | HTTP transport (spec "non-goal", but needed for a product → module `http`), surfaces, billing, tenancy refinements |

---

## 2. The six spec corrections – already incorporated here

| # | Corrected wording | Where it takes effect in code |
|---|---|---|
| **S-1/S-4** | **Errors are typed, declared and closed.** The concrete representation (exception or value) is left to the implementation. What matters: the three channels stay separate. | `SessionError` as `sealed interface`; tool boundary catches **only** `ToolFailure` |
| **S-2** | **An abort is deferred, not cancelled, during publication of a terminal event.** | Publish terminal event before every interrupt check; `ReentrantLock.lock()`; restore interrupt flag in `finally` |
| **S-3** | **An error ends the gathering operation.** Sibling tasks keep running; their results are, if present, still added to the event sequence. | `CompletableFuture.allOf` + own aggregation; sequencer (section 8) |
| **S-5** | **Every event carries a version.** Upgrade of old forms happens at **one** place. | Column `version` in `event`; `EventCodec.decode(type, version, data)` |
| **S-6** | **At the tool boundary only the declared error family is caught.** `catch (Exception)` is forbidden because it turns defects into errors. | `catch (ToolFailure f)` – nothing else |

---

## 3. The open decisions – with proposal

| # | Question | ⚠️ Proposal |
|---|---|---|
| **L-A** | **Location semantics** | **Location = a directory context with its own service set.** Rules: (1) Every session belongs to exactly one location. (2) A process can serve several locations; assignment happens **via the session**, not via a global context. (3) A location context is **created on demand** and unloaded after `location_idle_timeout` (default 60 min). (4) A **move** (session → other location) is a control element like compaction: delivery boundary, own events, state is transferred. (5) Tools, permissions, provider resolution and filesystem are **location-scoped** – a tool from location A may not run in location B without being registered there. → This becomes its own section in `03-states.md`. |
| **L-B** | **Concurrency quota** | **At most 8 concurrently running tool calls per step**, FIFO over a semaphore; beyond that waiting. Rationale: the model can request dozens of calls; 8 covers realistic parallelism and limits load. As configuration per agent (`tools.maxParallel`). |
| **L-C** | **Sequencer for ordered publication** | Tool calls of a step run **in parallel**, their **publication follows provider order**. Implementation: the provider delivers calls in order; the sequencer keeps an `ArrayDeque` of expected call IDs and releases a result only when order allows. **Failure case:** if a call fails definitively (abort), the ID is marked "skipped" and the sequence advances – **no gap in the event sequence**. |
| **L-D** | **Retention of the inbox** | No TTL. Waiting inputs are **user intent** and must not expire. But: an inbox row that was delivered is deleted; a cancelled one too. There is no retention of *delivery history* (the message is the proof). |
| **L-E** | **Complete the live feed** | (a) Encoding error → all affected connections are ended with an encoding error, the event is skipped, the channel stays usable. (b) Frame order = event order per aggregate. (c) Heartbeat every 15 s, **outside** the budget. (d) Reconnection: the client passes `after=<seq>`; the server replays from there and afterwards sends a sync marker (`log.synced`). |
| **O-01** | Retry count | **4** total (first attempt + 3 retries), configurable 1–10, timeouts limited to 3, wait time capped at 10 s per gap, `retryAfter` capped at 15 min, jitter. |
| **O-07** | `external_directory` | **Global working directory + allow list (Read/Write)** – accesses outside are denied. Replaces the previous "ask"/"allow" model. |
| — | **Error channel** | **Exception.** `sealed` hierarchy, `unchecked` (no `throws` clauses), but **strictly separated** into three roots: `SessionError` (error), `Aborted` (abort), `Defect` (defect). `catch (SessionError \| Aborted \| Defect)` is forbidden. |
| — | **Transaction × thread** | **Three transactions:** (1) write event in transaction A and commit → (2) run tool in task T without open transaction → (3) write result in transaction B. |

---

## 4. Module structure

> **Authoritative is [`../java-packages.md`](../java-packages.md).** One artifact,
> root `de.augmentia.oc.engine`, nine stages `S0…S8` plus `adapter.*`,
> enforced by ArchUnit. The earlier sketch "Maven multi-module
> `engine-core`/`-runtime`/…" is thereby **superseded**; it appears below only
> as optional later deployment split.

| Stage | Packages (Option B) | Content | Earlier name |
|---|---|---|---|
| S0–S2 | `core` | identifiers, event payloads, messages, ports, error channels, contracts (`sealed`), pure evaluators | `engine-core` |
| S3–S7 | `runtime` | registry, permission engine, provider client, context, tool families, step, compaction, session, delivery, revert, execution | `engine-runtime` |
| S8 | `http` | REST/SSE, composition root, wiring | `engine-http` + `engine-app` |
| – | `adapter.*` | implementations of ports | `engine-persistence` (+ foreign modules) |

**Dependency direction** (fully in
[`../java-packages.md`](../java-packages.md) §7):

```
S0 ← S1 ← S2 ← S3 ← S4 ← S5 ← S6 ← S7 ← S8
                    S3 ──────────────────► S6     (read-only, no edge back)
adapter.*  ── implements S0-ports, used only by S8
```

**Rule:** No package knows a package of the **same or higher** stage.
In particular `provider.*` knows **neither** `session.*` nor `tool.*` nor
`permission.*`, and `session.core` knows **neither** `step` nor
`provider.contract` – the trigger runs through the port `SessionWake`.

> **Maven split (decided 2026-10-22):** two modules – `engine` (pure Java,
> no Quarkus/CDI/HTTP) and `quarkus` (outer skin). The stage order remains an
> architecture rule enforced by ArchUnit *inside* `engine`; Maven modules only
> separate pure Java from the Quarkus runtime. `engine` has no Quarkus
> dependency; `quarkus` depends on `engine`. If build time later forces finer
> splits, the **same** package boundaries apply.

**Ownership (owns vs. calls):**

| Thing | Who owns it | Who calls it |
|---|---|---|
| Session state, inbox, log, claim | `session.core` (rules) + `adapter.sql` (storage) | – |
| Tool **implementation** (leaf logic) | `runtime` (tool families) | `runtime` (tool.runtime, at execution) |
| Tool **offer** (snapshot) and **call** (order, context, bounding) | `runtime` (tool.registry + tool.runtime) | `runtime` (tool.runtime) |
| Permission **rules** and stored approvals | `runtime` (permission.rule + permission.engine) + `adapter.sql` | `core` (permission.contract.PermissionGate, in leaf) |
| Model call, error classification, stream | `runtime` (provider.client + provider.classify) | `core` (provider.contract.ProviderAdapter, in `step`) |

> The engine **does not decide what is allowed** (that comes from
> configuration) and **implements no tools** (that comes from tool authors). It
> decides **when** a tool runs, **whether** it may run, **in what order** and
> **what the result means**.

---

## 5. Data model in Java 21

### 5.1 Identifiers (opaque, not convertible)

```java
public final class SessionId {
    private static final Pattern P = Pattern.compile("ses_[0-9a-f]{64}");
    private final String value;

    private SessionId(String value) { this.value = value; }

    public static SessionId create() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return new SessionId("ses_" + HexFormat.of().formatHex(b));
    }

    public static SessionId parse(String raw) {
        if (!P.matcher(raw).matches()) throw new InvalidRequestException("Invalid session id");
        return new SessionId(raw);
    }

    @Override public boolean equals(Object o) { return o instanceof SessionId s && s.value.equals(value); }
    @Override public int hashCode() { return value.hashCode(); }
    @Override public String toString() { return value; }
}
```

> `MessageId`, `InboxId`, `CallId`, `AgentId` analogously. **No**
> `SessionId`→`MessageId`.

### 5.2 Events (`sealed` → compiler enforces completeness)

```java
public sealed interface SessionEvent {

    record InboxEnqueued(String sessionId, String inboxId, InboxItem item, long enqueuedSeq) implements SessionEvent {}
    record InboxDelivered(String sessionId, String inboxId) implements SessionEvent {}
    record InboxCancelled(String sessionId, String inboxId) implements SessionEvent {}
    record InboxDeliveryChanged(String sessionId, String inboxId, Delivery delivery) implements SessionEvent {}

    record StepStarted(String sessionId, String assistantMessageId, String agentId,
                       ModelRef model, Instant started) implements SessionEvent {}
    record ToolCalled(String sessionId, String assistantMessageId, String callId,
                      String toolName, JsonNode input) implements SessionEvent {}
    record ToolSucceeded(String sessionId, String assistantMessageId, String callId,
                         List<ToolContent> content, Map<String, Object> metadata) implements SessionEvent {}
    record ToolFailed(String sessionId, String assistantMessageId, String callId,
                      SessionError error) implements SessionEvent {}
    record StepEnded(String sessionId, String assistantMessageId, String finish,
                     Money cost, TokenCount tokens) implements SessionEvent {}
    record StepFailed(String sessionId, String assistantMessageId, SessionError error) implements SessionEvent {}

    record ExecutionStarted(String sessionId) implements SessionEvent {}
    record ExecutionSucceeded(String sessionId) implements SessionEvent {}
    record ExecutionFailed(String sessionId, SessionError error) implements SessionEvent {}
    record ExecutionInterrupted(String sessionId, InterruptReason reason) implements SessionEvent {}

    record InstructionsUpdated(String sessionId, Map<String, String> delta, String text) implements SessionEvent {}
    record CompactionStarted(String sessionId, String reason) implements SessionEvent {}
    record CompactionEnded(String sessionId, String reason, String summary) implements SessionEvent {}
    record CompactionFailed(String sessionId, String reason, SessionError error) implements SessionEvent {}
    record RevertCommitted(String sessionId, String boundaryMessageId) implements SessionEvent {}
}
```

**The decisive effect:** Every projection is a `switch` over this
`sealed` hierarchy. A new event variant → the compiler reports every
forgotten projection. **Thus I-07-02 ("both projection layers") is
statically secured instead of only convention.**

### 5.3 The three error channels

```java
/** Channel 1: expected, classified failure. */
public sealed interface SessionError {
    String type();
    String message();

    record ProviderError(String type, String message, Integer status, String responseBody,
                         Duration retryAfter) implements SessionError {}
    record PermissionRejected(String type, String message) implements SessionError {}
    record ToolExecutionError(String type, String message) implements SessionError {}
    record CompactionError(String type, String message) implements SessionError {}
    record Unknown(String type, String message) implements SessionError {}
}

/** Channel 2: deliberate stop. Never convert into an error. */
public final class Aborted extends RuntimeException {
    public Aborted(String reason) { super(reason); }
}

/** Channel 3: violation of an invariant. Never convert into an error. */
public final class Defect extends RuntimeException {
    public Defect(String message) { super(message); }
}
```

**Rules:**

* `Aborted` and `Defect` are **unchecked** (no `throws` clauses) – but
  **separate** from `SessionError`.
* A `catch` that mixes `Aborted` or `Defect` with `SessionError` is
  forbidden.
* `InterruptedException` is **not** turned into `SessionError`; it is converted
  into `Aborted` **and** the interrupt flag restored:

```java
try {
    ...
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // restore flag
    throw new Aborted("interrupted");
}
```

### 5.4 Option instead of null pointer

```java
public sealed interface Option<T> {
    record Some<T>(T value) implements Option<T> {}
    record None<T>() implements Option<T> {}

    static <T> Option<T> of(T value) { return value == null ? new None<>() : new Some<>(value); }
    default T orElse(T fallback) { return this instanceof Some<T> s ? s.value() : fallback; }
    default Option<T> map(Function<T, T> f) { ... }
}
```

> `java.util.Optional` is **not** used as field or parameter type: it cannot
> express a "present but empty" value and is unusual as parameter type.

---

## 6. Persistence

### 6.1 Schema (Flyway, PostgreSQL)

```sql
create table event (
  id            bigserial primary key,
  aggregate_id  text        not null,
  seq           bigint      not null,
  type          text        not null,
  version       int         not null,
  data          jsonb       not null,
  created_at    timestamptz not null default now(),
  unique (aggregate_id, seq)
);

create table event_sequence (
  aggregate_id  text primary key,
  seq           bigint not null
);

create table session (
  id             text primary key,
  project_id     text        not null,
  location_id    text        not null,
  agent_id       text        not null,
  model          jsonb,
  permissions    jsonb       not null default '[]',
  cost           numeric(12,4) not null default 0,
  tokens         jsonb       not null default '{}',
  time_created   timestamptz not null default now(),
  time_updated   timestamptz not null default now(),
  time_idle      timestamptz,
  idle_outcome   text,
  time_archived  timestamptz
);

-- Write-ahead claim: exactly one row per claimed session
create table session_claim (
  session_id     text primary key references session(id) on delete cascade,
  claimed_at     timestamptz not null default now(),
  resume_attempts int       not null default 0
);

create table session_message (
  id         text primary key,
  session_id text   not null references session(id) on delete cascade,
  seq        bigint not null,
  type       text   not null,
  data       jsonb  not null,
  unique (session_id, seq)
);

create table session_inbox (
  id            text primary key,
  session_id    text   not null references session(id) on delete cascade,
  type          text   not null,
  payload       jsonb  not null,
  delivery      text   not null check (delivery in ('steer','queue')),
  enqueued_seq  bigint not null
);
create index on session_inbox (session_id, delivery, enqueued_seq);
```

> **Deliberately absent:** `session_pending` (legacy), `project_directory`,
> `account*`, `control_account` – five dead tables in the actual code.

### 6.2 `publish` – one transaction, all projections

```java
public final class EventStore {

    private final DataSource dataSource;
    private final List<Projection> projections;
    private final EventCodec codec;

    public void publish(SessionEvent event) { publishAll(List.of(event)); }

    public void publishAll(List<SessionEvent> events) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (SessionEvent e : events) {
                    long seq = nextSeq(c, aggregateOf(e));
                    insertEvent(c, e, seq);
                    for (Projection p : projections) p.apply(c, e, seq);   // both layers
                    upsertSequence(c, aggregateOf(e), seq);
                }
                c.commit();                       // ← only here is it durable
            } catch (SQLException ex) {
                c.rollback();
                throw new StoreFailure("publish failed", ex);   // do NOT swallow
            }
        } catch (SQLException e) {
            throw new StoreFailure("publish failed", e);
        }
    }
}
```

**Three rules that follow from this:**

1. **No `catch` that swallows the error.** In the actual code
   (`SessionStore.java:79-81`) a failed `recordEvent` is skipped via
   `log.warnf` → silent data loss. Here: the error leaves the method.
2. **Projections have no external side effects.** No HTTP, no cache, no
   logger metric inside the transaction.
3. **The claim change is a `commit` hook of the same transaction** – like
   `store.claim(sessionID)` in the actual code.

### 6.3 The two projections

```java
public interface Projection {
    void apply(Connection c, SessionEvent event, long seq) throws SQLException;
}
```

```java
public final class TableProjection implements Projection {
    @Override public void apply(Connection c, SessionEvent e, long seq) throws SQLException {
        switch (e) {
            case SessionEvent.InboxEnqueued ev -> { /* INSERT session_inbox */ }
            case SessionEvent.InboxDelivered ev -> {
                /* DELETE session_inbox + INSERT session_message – in the same transaction */
            }
            case SessionEvent.ExecutionStarted ev -> { /* INSERT session_claim (write-ahead) */ }
            case SessionEvent.ExecutionSucceeded ev -> { /* UPDATE session: time_idle, idle_outcome */
                                                       /* + DELETE session_claim, resume_attempts = 0 */ }
            case SessionEvent.ExecutionInterrupted ev -> {
                if (ev.reason() == InterruptReason.SHUTDOWN) { /* claim stays */ }
                else { /* DELETE session_claim, resume_attempts = 0 */ }
            }
            // … further cases; the compiler forces completeness
            default -> { }
        }
    }
}

public final class ConversationProjection implements Projection {
    @Override public void apply(Connection c, SessionEvent e, long seq) throws SQLException {
        switch (e) {
            case SessionEvent.ToolCalled ev -> { /* state → RUNNING */ }
            case SessionEvent.ToolSucceeded ev -> { /* state → COMPLETED */ }
            case SessionEvent.ToolFailed ev -> { /* state → ERROR */ }
            case SessionEvent.StepEnded ev -> { /* completion, cost, tokens */ }
            // …
            default -> { }
        }
    }
}
```

> **The `default` branch is deliberately allowed here** – it stands for
> "irrelevant for this layer". The **exhaustiveness** pressure comes from
> `sealed`: without `default` the compiler reports every new variant.
> Recommendation: `default` only with a comment "not affected by this layer".

---

## 7. Permissions

```java
public enum Effect { ALLOW, DENY, ASK }

public record Rule(String action, String resource, Effect effect) {}

/** The permission engine. */
public final class PermissionEngine {

    /** The last matching rule wins; with no hit ASK applies. */
    public Effect evaluate(String action, String resource, List<Rule> rules) {
        Rule hit = null;
        for (Rule r : rules) {
            if (Wildcard.match(action, r.action()) && Wildcard.match(resource, r.resource())) hit = r;
        }
        return hit == null ? Effect.ASK : hit.effect();
    }
}
```

**Evaluation order** (binding, matches I-06-03):

```java
public void assert(PermissionRequest request) {
    List<Rule> rules = concat(agentRules(request), sessionRules(request));

    // 1. Deny first – beats any stored approval
    if (anyResourceEvaluatesTo(rules, request, Effect.DENY)) {
        throw new PermissionRejectedException(request);   // → SessionError.permission.rejected
    }

    // 2. Add stored approvals (per project)
    List<Rule> all = concat(rules, savedRules(request.projectId()));

    // 3. Ask dominates allow
    Effect effect = anyResourceEvaluatesTo(all, request, Effect.ASK) ? Effect.ASK : Effect.ALLOW;

    // 4. Extension hook may override effect and message
    Effect finalEffect = hooks.evaluatePermission(request, effect);

    switch (finalEffect) {
        case ALLOW -> { }
        case DENY  -> throw new PermissionRejectedException(request);
        case ASK   -> {
            // 5. Inquiry: publish request and block waiting
            Pending p = pending.create(request);
            publishPermissionAsked(request);          // ephemeral, no log
            Decision d = p.await();                   // interrupts on abort
            switch (d.reply()) {
                case ONCE   -> { }
                case ALWAYS -> saved.add(request.projectId(), request.action(), request.savePattern());
                case REJECT -> {
                    // IMPORTANT: rejection without feedback is the channel "Aborted",
                    // not an error → the model does NOT see it.
                    if (d.feedback() == null) throw new Aborted("declined");
                    throw new PermissionRejectedException(request, d.feedback());
                }
            }
        }
    }
}
```

> **The line `throw new Aborted("declined")` is the most important difference
> from the TypeScript reference.** A rejection that goes to the model as text
> leads to endless retry attempts.

**Wildcard semantics** (from I-06-01/I-06-13):

```java
public final class Wildcard {
    public static boolean match(String input, String pattern) {
        String normalizedInput  = input.replace('\\', '/');
        String normalizedPattern = pattern.replace('\\', '/');

        StringBuilder regex = new StringBuilder("^");
        List<String> literal = new ArrayList<>();
        for (int i = 0; i < normalizedPattern.length(); i++) {
            char c = normalizedPattern.charAt(i);
            if (c == '*') { flush(literal, regex); regex.append(".*"); }
            else if (c == '?') { flush(literal, regex); regex.append('.'); }
            else literal.add(String.valueOf(c));
        }
        flush(literal, regex);

        // " *" at the end: also matches the command WITHOUT arguments
        if (normalizedPattern.endsWith(" *")) {
            regex.setLength(regex.length() - 2);      // remove ".*"
            regex.append("( .*)?");
        }
        regex.append('$');
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(normalizedInput).matches();
    }
}
```

---

## 8. Tool chain

```java
public record ToolContext(String sessionId, String agentId, String messageId, String callId) {}

public sealed interface ToolOutcome {
    record Ok(JsonNode output, List<ToolContent> content, Map<String, Object> metadata) implements ToolOutcome {}
    record Failed(SessionError error) implements ToolOutcome {}
}

public final class ToolExecutor {

    /** Execution snapshot: exactly the tools advertised by this request. */
    public ToolOutcome execute(ToolCall call, ToolContext ctx) {
        ToolDefinition def;
        JsonNode input;
        ToolResult raw;

        try {
            def = snapshot.require(call.name());                 // throws ToolUnavailable → Failed
            input = decodeInput(def, call.input());              // throws ToolFailure → Failed
            // permissions BEFORE the side effect; a rejection leaves the method as Aborted/error
            permission.assert(ctx, def.action(), call.resource(), def.savePattern());
            raw = def.execute(input, ctx);                       // declared: ToolFailure
        } catch (ToolFailure failure) {
            // ONLY this family. Aborted and Defect pass through untouched.
            return new ToolOutcome.Failed(toSessionError(failure));
        }

        JsonNode output = def.outputSchema() == null ? null : encodeOutput(def, raw.output());
        ToolResult after = hooks.afterToolCall(ctx, raw);       // must never fail
        List<ToolContent> content = normalize(after.content(), output);
        return new ToolOutcome.Ok(output, content, after.metadata());
    }
}
```

**Order (binding):**

1. Name resolution **in the frozen snapshot** (not in the live registry)
2. Input decoding → on error **no** execution
3. Permission check
4. Execution (side effect)
5. Output decoding
6. `after` hook
7. Content normalization
8. **Bounding** (in the runner, after execution) → `metadata.truncated`, `metadata.outputPath`

> **Error separation:** `ToolFailure` (expected) → `ToolOutcome.Failed` (model-visible).
> `Aborted` (user rejection) → leaves the method → the step ends, the model sees
> **nothing**. `Defect` → abort of the run, loud log.

---

## 9. Execution, claim, recovery

```java
public final class SessionExecution {

    private final Map<String, Execution> executions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** Doorbell: idle → start, busy → remember for one more pass. */
    public void wake(String sessionId, Promotable scope) {
        Execution existing = executions.get(sessionId);
        if (existing != null) { existing.pendingWake = merge(existing.pendingWake, scope); return; }
        start(sessionId, scope);
    }

    private void start(String sessionId, Promotable scope) {
        Execution exec = new Execution(sessionId, scope);
        executions.put(sessionId, exec);
        executor.submit(() -> {
            try {
                store.publish(new SessionEvent.ExecutionStarted(sessionId));  // + claim in same transaction
                DrainResult result;
                do {
                    result = runner.drain(sessionId, false, exec.scope);
                    exec.scope = exec.pendingWake;
                    exec.pendingWake = null;
                } while (exec.pendingWake != null && result.isComplete());

                // EXACTLY ONE terminal event per execution period
                switch (result.outcome()) {
                    case SUCCEEDED -> store.publish(new SessionEvent.ExecutionSucceeded(sessionId));
                    case FAILED    -> store.publish(new SessionEvent.ExecutionFailed(sessionId, result.error()));
                    case INTERRUPTED -> store.publish(
                            new SessionEvent.ExecutionInterrupted(sessionId, result.reason()));
                }
            } catch (Aborted a) {
                store.publish(new SessionEvent.ExecutionInterrupted(sessionId, InterruptReason.USER));
            } catch (Defect d) {
                // Defect: log loudly, abort run, deliberately do NOT release claim
                log.error("defect in session execution", d);
            } finally {
                executions.remove(sessionId, exec);
            }
        });
    }
}
```

> **S-2 (abort deferred):** The terminal event is published **before**
> the interrupt is checked. In `finally` the interrupt flag is restored.

**Recovery on start:**

```java
public void resumeSuspendedSessions() {
    for (String sessionId : store.listSuspended()) {          // session_claim exists
        int attempts = store.countResume(sessionId);           // durable, BEFORE the run
        if (attempts > 10) {
            store.publish(new SessionEvent.ExecutionFailed(sessionId,
                    new SessionError.Unknown("aborted", "Execution was interrupted repeatedly")));
            continue;                                          // releases claim + counts back
        }
        store.publish(new SessionEvent.CompactionEnded(sessionId, "restart", CONTINUE_AFTER_RESTART));
        wake(sessionId, Promotable.INPUT);
    }
}
```

**Before** the first model request of a continued session: mark orphaned
tool calls as failed (they still stand as `RUNNING` in the message table).

---

## 10. Provider interface

```java
public sealed interface ModelEvent {
    record TextStarted(String blockId) implements ModelEvent {}
    record TextDelta(String blockId, String text) implements ModelEvent {}
    record TextEnded(String blockId, String text) implements ModelEvent {}
    record ReasoningStarted(String blockId) implements ModelEvent {}
    record ReasoningDelta(String blockId, String text) implements ModelEvent {}
    record ToolCallRequested(String callId, String toolName, JsonNode input) implements ModelEvent {}
    record ToolResultProvided(String callId, String toolResult) implements ModelEvent {}
    record Finished(FinishReason reason, TokenCount usage) implements ModelEvent {}
    record ProviderFailure(ProviderFailureKind kind, String message, Integer status,
                            Duration retryAfter) implements ModelEvent {}
}

public interface ModelProvider {
    /** Exactly one model call. Read stream completely. */
    Flow.Publisher<ModelEvent> stream(ModelRequest request);
}
```

**Error classification – typed, not by text pattern:**

```java
public enum ProviderFailureKind {
    RATE_LIMITED, PROVIDER_INTERNAL, TRANSPORT, INVALID_OUTPUT, INVALID_REQUEST,
    AUTHENTICATION, QUOTA_EXCEEDED, CONTENT_POLICY, UNSUPPORTED_OPERATION, TIMEOUT
}

/** Only this classification decides retry – never the message text. */
public boolean isRetryable(ProviderFailureKind kind) {
    return switch (kind) {
        case RATE_LIMITED, PROVIDER_INTERNAL, TIMEOUT -> true;
        case TRANSPORT -> true;                        // check send delivery
        case INVALID_OUTPUT -> true;                  // only "incomplete stream"
        case INVALID_REQUEST -> false;                // context overflow handled separately
        case AUTHENTICATION, QUOTA_EXCEEDED, CONTENT_POLICY,
             UNSUPPORTED_OPERATION -> false;
    };
}
```

---

## 11. Live feed (SSE)

```java
@Path("/api/event")
public class EventFeedResource {

    private static final int CAPACITY = 4096;      // per connection

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@Context HttpHeaders headers,
                       @QueryParam("after") Long after,
                       StreamingOutput out) throws IOException {
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(CAPACITY);
        // 1. Subscribe BEFORE replay (I-07-07)
        Subscription sub = bus.listen(event -> {
            if (!queue.offer(encodeOnce(event))) {   // "encode once"
                sub.fail(new SubscriberOverflowException(CAPACITY));   // only THIS connection dies
            }
        });
        try {
            // 2. Replay history, then sync marker
            for (SessionEvent e : store.replay(sessionId, after)) out.write(encodeOnce(e));
            out.write(syncMarker());
            out.flush();
            // 3. Continue live
            for (String frame : queue) { out.write(frame); out.flush(); }
        } finally {
            sub.close();
        }
    }
}
```

* Heartbeat every 15 s **outside** the queue (own thread or `ScheduledExecutor`).
* Encoding error → end all affected connections, skip the event.

---

## 12. Test strategy

> **The event channel is the test surface.** A test reads the event sequence
> and checks the invariants against it. This makes 111 rules testable without
> testing the engine from the inside.

```java
@Testcontainers
class SessionInvariantIT {

    @Container
    static PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");

    @Test
    void admission_creates_exactly_one_inbox_row_before_execution() {
        // 1. create session
        // 2. admit prompt
        // 3. read event sequence and check:
        //    InboxEnqueued.seq < ExecutionStarted.seq
        //    InboxDelivered deletes the row and creates the message
        List<SessionEvent> log = store.replay(sessionId, null);
        assertThat(indexOf(log, new SessionEvent.InboxEnqueued(...)))
            .isLessThan(indexOf(log, new SessionEvent.ExecutionStarted(...)));
    }

    @Test
    void tool_call_is_durable_before_the_side_effect() {
        // tool that writes a file and checks at start that the event is already committed
    }
}
```

**Tools:** JUnit 5, AssertJ, Testcontainers (PostgreSQL), WireMock or an own
provider stub for the model side, `jqwik` for invariant properties.

**Order of test layers:**

| Layer | Tests | Tool |
|---|---|---|
| Unit | tool chain, permissions, wildcard, state machines | JUnit 5, no DB |
| Integration | event log, projections, delivery, recovery | Testcontainers + PostgreSQL |
| Invariants | the 111 rules as properties over the event sequence | as above |
| Provider | classification, overflow, retry | stub provider, cassettes |

---

## 13. Build plan

| Phase | Content | Done when |
|---|---|---|
| **1** | `core` (model, error, json, event, ports) + `adapter.sql`: types, events, three error channels, schema + Flyway, `Journal.append` | An event lies in `event` **and** in both projections; the transaction is atomic |
| **2** | Inbox: admission, delivery, modes, idempotency | I-01-01…I-03, I-02-01…I-11 tested as properties |
| **3** | Execution: claim, doorbell, abort, recovery | A crash mid-run is continued on next start (I-03, I-10-01) |
| **4** | Step: context, ModelRequest, tool chain, sequencer, bounding | I-04, I-05 tested as properties |
| **5** | Permissions: rule model, inquiry, stored approvals, rejection as abort | I-06 complete; rejection produces **no** model-side text |
| **6** | Compaction, live feed, provider adapter | I-07, I-09, I-10 |

**Do not parallelize:** Phase 1 is the basis for everything. Without log and
projections no test is meaningful, because state is not observable.

> **Finer order:** The six phases above are the domain milestone chain. The
> **individually testable** build sequence in 15 steps (M0–M14, each with
> check point) is in [`../module-plan.md`](../module-plan.md) §8.

---

## 14. Acceptance checklist

Before "done":

- [ ] `bun` equivalent: `mvn -q verify` green (unit + integration + container)
- [ ] All 111 invariants are referenced as a test or justified as "not applicable"
- [ ] The `sealed` event type enforces completeness of both projections (I-07-02)
- [ ] A rejection case produces **no** model-side tool text (I-06-09)
- [ ] A `catch (Exception)` exists **nowhere** at a tool boundary (S-6)
- [ ] No error path swallows a persistence error (I-07-03)
- [ ] The claim is released only by a terminal event; shutdown leaves it (I-03-03, I-03-04)
- [ ] A crash with an open claim continues the run on next start, with durable counter (I-10-01)
- [ ] `session_claim` is never deleted by a shutdown
- [ ] The live feed encodes **once** per event and kills only the overflowing connection (I-07-09)
- [ ] Tool calls run in parallel, their publication is ordered (L-C)
- [ ] Compaction **replaces** nothing – the transcript is fully retained (I-09-01)

---

## 15. The five most common errors in this implementation

| # | Error | Avoided by |
|---|---|---|
| 1 | Rejection as text to the model → the agent tries endlessly | `throw new Aborted("declined")` in `PermissionEngine.assert` |
| 2 | Persistence error logged and skipped → silent data loss | Let `StoreFailure` propagate; never `log.warn` + continue |
| 3 | Transaction across the thread boundary of the tool call | Three transactions: Event A → tool in task T → result B |
| 4 | `catch (Exception)` at the tool boundary → defects become errors | Only `catch (ToolFailure)` |
| 5 | Registry read at call time → a different tool runs than advertised | Freeze snapshot: definitions **and** executors together |

---

## 16. Open for the domain decision

| Question | Status |
|---|---|
| Concrete tool concurrency quota (proposal 8) | decided (L-B): 8 |
| `external_directory` | decided (O-07): global working directory + allow list |
| Location semantics | decided (L-A): one location per process (base environment) |
| Provider selection, credentials, catalog, fallback | decided (L-7, P-1, PRM-A): snapshot, env only, templates dropped |
| Retention of `session_events` (PII) | decided (P-2): unlimited |
| Content filter behavior | decided (P-3): secure partial content, truncated, no retry |
| Agent catalog | decided (L-1): file-based |
| Configuration schema | decided (L-2): file, strict JSON |
| Commands | decided (L-6/PRM-C): text macro in engine |

---

## 17. SessionError taxonomy (from the reference checkout)

> Source: `flow-db-service/04-stufe-4-fehler.md` §1. The `type` is a
> **free string**; the following values are produced. In Java as a
> `sealed` hierarchy under `SessionError` (plus `Aborted`, `Defect`).

| `type` | Source |
|---|---|
| `provider.rate-limit` | RateLimit |
| `provider.auth` | Authentication |
| `provider.quota` | QuotaExceeded |
| `provider.free-limit` | QuotaExceeded + FreeUsageLimit |
| `provider.content-filter` | ContentPolicy |
| `provider.transport` | Transport |
| `provider.internal` | ProviderInternal |
| `provider.invalid-output` | InvalidProviderOutput |
| `provider.invalid-request` | InvalidRequest |
| `provider.unsupported-operation` | UnsupportedOperation |
| `provider.no-route` | NoRoute, model/variant errors |
| `provider.unknown-provider` | UnknownProvider |
| `provider.timeout` | Timeout |
| `permission.rejected` | Permission.BlockedError / CorrectedError |
| `tool.execution` | ToolFailure / Tool.Error |
| `tool.unknown` | "Unknown tool: …" |
| `unknown` | everything else |
| `aborted` | Interrupt |
| `compaction.failed` / `compaction.interrupted` | Compaction |
| `output-limit` | output limit exhausted |

Special case "ChatGPT token sharing": nine specific codes are translated
into understandable texts.

---

## 18. Debugging checklist and pitfalls

> Source: `flow-db-service/04-stufe-4-fehler.md` §8–§9, `05-szenario.md`.

### 18.1 Pitfalls

1. `catchCause` / `catch` around `execute` swallows interruptions and defects.
2. Editing client files by hand – they are generated.
3. `packages/core` imports `packages/client` – violates layer direction.
4. Creating a `.changeset` – this repo uses no changesets.
5. Reusing V1 names (`write`/`patch`→`edit`, `task`→`subagent`, `bash`→`shell`).
6. Using `experimental.policies` – deprecated; top-level `policies` is the place.
7. Starting tests from repo root – guard `do-not-run-tests-from-root`.
8. Using `session_pending` – legacy; active is `session_inbox`.
9. Expecting `CLAUDE.md` as instruction file – V2 knows only `AGENTS.md`.
10. Expecting `todowrite` – only when `experimental.todo === true`.
11. Expecting agent `request` overlays – the runner receives them, but does not send them.
12. Injecting instruction files via `Session.synthetic` – layer cycle; use `SessionEvent.Synthetic`.
13. A tool with `output` but no output in the result – produces a defect (`runtime.ts:47-48`).
14. Expecting file mutations to be durable – the bytes are not logged.
15. Expecting `permission.asked` as audit log – ephemeral; no permission audit log.
16. Expecting snapshots in the DB – they lie in their own git repo.

### 18.2 Symptom → suspicion

| Symptom | First suspicion |
|---|---|
| Prompt appears, agent does not react | inbox item not promoted (`SELECT * FROM session_inbox`) |
| "Tool is not available for this request" | tool deregistered between snapshot and call |
| Permission asks constantly | `save` pattern missing or `deny` hits (`SELECT * FROM permission`) |
| Model answers "Unknown tool" | namespace/definition missing in catalog |
| Step ends without tool result | `RESULT_MISSING` – parser returned call without result |
| Session hangs after restart | open claim without recovery (`session_v2.time_suspended`, `resume_attempts`) |
| Tool output truncated | `ToolOutput.truncate` (`metadata.outputPath`) |
| Costs missing in session | `session.usage.updated` is projection, not event |

### 18.3 Retry mechanism (reference: 10, spec: 4)

- Delay: exponential from 2 s, capped at 10 s per gap, jittered.
- Count: `Schedule.recurs(10)` → up to 10 retries (reference); **spec decides 4** (O-01).
- `retryAfter` from provider respected, capped at 15 min.
- Timeouts: at most 3 attempts.
- Unknown errors are retried (classification records only affirmative evidence).

### 18.4 Transaction boundaries of a round

| TX | Content | Event | Effect on |
|---|---|---|---|
| 1 | Admission | `session.inbox.enqueued` | `session_inbox` |
| 2 | Execution start | `session.execution.started` | `session_v2.time_suspended` (claim) |
| 3 | Delivery | `session.inbox.delivered` | `session_message` (user) |
| 4 | Tool start | `session.tool.called` | assistant message (RUNNING) |
| 5 | Tool end | `session.tool.success` / `.failed` | assistant message (COMPLETED/ERROR) |
| 6 | Step end | `session.step.ended` | assistant message, cost, tokens |

### 18.5 Verification queries (PostgreSQL)

```sql
SELECT type, seq, created FROM event
 WHERE aggregate_id = '<sessionID>' ORDER BY seq;

SELECT id, type, delivery, enqueued_seq FROM session_inbox
 WHERE session_id = '<sessionID>';

SELECT type, seq FROM session_message
 WHERE session_id = '<sessionID>' ORDER BY seq;

SELECT time_idle, idle_outcome, cost, tokens_input, tokens_output,
       time_suspended, resume_attempts
  FROM session_v2 WHERE id = '<sessionID>';
```

Expected pattern: `session.inbox.enqueued` **before** `session.execution.started`,
`session.inbox.delivered` **before** the first `session.step.started`,
`session.execution.succeeded` **after** the last `session.step.ended`.