# Execution model – one execution per session, one virtual thread

> **Purpose:** The missing link between "one execution per session"
> ([`03-states.md`](./03-states.md)) and the Java code
> ([`Java-Impl.md`](./Java-Impl.md) §9): **how** an execution runs, where
> its limits are and what happens on shutdown.
>
> **Status:** 2026-10-22

## 1. The model in four sentences

1. Per session at most **one** execution runs.
2. Each execution occupies **one** virtual thread.
3. Tool calls within a step run **in parallel** in their own
   virtual threads, limited by a quota.
4. Waiting sessions occupy **no** thread – they stand in the inbox.

```
Call (HTTP) ──► Admission (TX) ──► doorbell ──► execution
                                       │              │
                                  idle: start    virtual thread
                                  busy: remember       │
                                                       ▼
                                             step → model → tools
                                                       │
                                                  terminal event
```

## 2. Why virtual threads

A model call is **blocking**: network, stream, wait time. A tool call is too:
process, file, HTTP. With platform threads every running session would be a
thread mostly waiting – and the number of concurrent sessions would be limited
by the thread pool, not by the machine.

Virtual threads solve this because the carrier thread is released while
waiting. The limit is then no longer "how many threads fit in memory" but
"how many sessions do we want to serve concurrently" – a product decision,
not a runtime limit.

**The price:** Virtual threads do not forgive blocking under a lock. Anyone
waiting on I/O inside a `synchronized` block pins the carrier thread and
defeats the benefit. Hence the rules in §4.

## 3. Lifecycle

```
                  wake(session)
    idle ─────────────────────────────► claimed
      ▲                                    │
      │                                    │ drain
      │                                    ▼
      │                                 draining ──┐
      │                                    │       │ pendingWake
      │                                    │       │ (coalesced)
      │                                    ▼       │
      └──────── terminal ◄─────────────────┴───────┘
                 │
                 ├─ SUCCEEDED
                 ├─ FAILED
                 └─ INTERRUPTED
```

| State | Meaning | Durable? |
|---|---|---|
| `idle` | no execution active | yes (derived) |
| `claimed` | execution claimed, claim written | yes (`session_claim`) |
| `draining` | steps running | no |
| `terminal` | one terminal event written, claim free | yes (event) |
| `pendingWake` | doorbell during `draining` | no (coalesced) |

**Rule:** `pendingWake` is **one** value, not a list. Two doorbells during
a run produce one further pass, not two.

## 4. Rules

**X-1** Per session at most one active execution. A second `wake` does not
start a second execution but sets `pendingWake`.

**X-2** Per execution exactly one virtual thread. No thread pool for
executions, no queue of threads.

**X-3** Tool calls of a step run in parallel, limited by a quota (suggestion
`L-B`: 8 per step). The quota is configuration, not a constant.

**X-4** No blocking call under a lock. Instead of `synchronized` use
`ReentrantLock`; the critical section contains only state change, no I/O.

**X-5** No `ThreadLocal` for session context. The session ID is **passed**,
not inherited – otherwise the context is wrong after a thread switch.

**X-6** Abort is an interrupt **plus** channel `Aborted`
([`06-errors.md`](./06-errors.md) §1). An abort is never reinterpreted as
an error.

**X-7** On orderly shutdown executions are interrupted, but the **claim
stays**. Only the restart continues. A claim deleted on shutdown makes
recovery impossible.

**X-8** Waiting sessions occupy no thread. The inbox is the waiting place,
the doorbell is the wake-up call.

## 5. Pinning

| Cause | Avoided by |
|---|---|
| `synchronized` around I/O | `ReentrantLock`, lock only around state change |
| native method that blocks | not in the core; only in adapters |
| `ThreadLocal` in hot path | pass value (X-5) |
| long `synchronized` block without I/O | still lock: more even carrier load |

**Check:** `-Djdk.tracePinnedThreads=full` in the test run. If pinning
occurs, the stack trace is in the log – and the test fails if treated as an
error. In production the observation belongs in metrics, not in hope.

## 6. Structured concurrency

`StructuredTaskScope` is **preview** in Java 21 (JEP 453). As long as the
runtime is built without `--enable-preview`, it is unavailable.

Therefore for X-3:

```java
try (var scope = Executors.newVirtualThreadPerTaskExecutor()) {
    var futures = aufrufe.stream().map(a -> scope.submit(() -> ausfuehren(a))).toList();
    // collect results, separate errors by channel
}
```

An executor in `try`-with-resources is the portable form: `close()` waits
for all tasks. If structured concurrency becomes final later, it replaces
this pattern without changing the rules.

**Rule:** No `Future.get()` without a deadline. A model call without a
timeout is a hanging thread – virtual or not.

## 7. Limits and backpressure

| Limit | Default | Effect on exceedance |
|---|---|---|
| concurrent executions | configuration | admission stays in the inbox |
| tool calls per step | 8 (`L-B`) | wait for the quota, FIFO |
| model call | timeout per provider | error, retry plan |
| tool | timeout per tool | tool error to the model |
| execution overall | no hard limit | abort by user or shutdown |

**Backpressure arises in the inbox, not in threads.** Whoever governs
backpressure through threads has moved it into memory.

## 8. Shutdown

| Kind | Executions | Claim | Recovery |
|---|---|---|---|
| **orderly** (SIGTERM) | interrupted, terminal event attempted | **stays** | on next start |
| **hard** (SIGKILL, crash) | gone | stays | on next start |
| **user abort** | interrupted | **free** | none |
| **terminal** | finished | free | none |

The distinction is the only place where "abort" and "shutdown" have
different consequences – and it is the reason the claim exists.

**Order on orderly shutdown:**

1. accept no new admissions (HTTP 503)
2. interrupt running executions
3. wait for terminal events, with deadline
4. close executor
5. close data source

## 9. Observability

| Metric | Question |
|---|---|
| active executions | How many sessions are working? |
| virtual threads | How many live, how many blocked? |
| wait time until `claimed` | How long does an admission sit in the inbox? |
| pinning events | Does a lock block the carrier? |
| aborts by cause | user, shutdown, defect |
| terminal rate | How many runs end successfully? |

The session ID belongs in every line – without it a log with virtual
threads is unreadable.

## 10. Invariants

| # | Invariant | Check |
|---|---|---|
| X-1 | at most one execution per session | module test: two `wake`, one claim |
| X-2 | one virtual thread per execution | `Thread.currentThread().isVirtual()` in test |
| X-3 | tool quota never exceeded | counter in test, semaphore in code |
| X-4 | no I/O under lock | review + pinning run |
| X-5 | no `ThreadLocal` for session context | grep in build (foreign to ArchUnit) |
| X-6 | abort stays abort | test: `Aborted` never reaches the model |
| X-7 | shutdown leaves claim standing | test: restart continues |
| X-8 | waiting session occupies no thread | load test: 1,000 waiting admissions |

> **Practical value:** X-2 to X-5 are the rules one notices only when load
> hits the system. They stand here so they are decided beforehand – and not
> as an obituary in an incident report.