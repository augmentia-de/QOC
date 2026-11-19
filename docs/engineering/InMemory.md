# In-Memory Runtime

> **Purpose:** Description of a fully volatile runtime in which all
> storage and coordination ports are replaced by in-memory adapters.
> No restart recovery, but consistent and fault-tolerant execution
> within the process lifetime.
>
> **Status:** 2026-11-09
>
> **Scope:** Complements [`Storage-alternative.md`](./Storage-alternative.md)
> and [`Core.md`](./Core.md). In case of contradictions, the spec under
> [`spec/`](../spec/README.md) applies.

## 1. Target picture

An **In-Memory Runtime** is an operating mode in which the entire
engine state lives on the heap of the running process:

- no database,
- no network storage,
- no persistence,
- no resumption after a restart.

In exchange:

- minimal latency (no I/O round-trips),
- simple deployment topology (single process),
- deterministic tests,
- full use of the existing ports and invariants.

**Guiding principle:** *Fault tolerance within the process, no durability
beyond the process.*

## 2. Guarantees and limits

### 2.1 Guarantees

| Guarantee | Implementation |
|---|---|
| A step is atomic (no half-state) | Commit at step end under per-session lock |
| At most one execution per session | `ClaimStore` as `ConcurrentHashMap` |
| Tool call visible before side effect (G-03) | In-memory journal, append before execution |
| Exactly one terminal event per execution | `Coordinator` publishes once |
| Consistent reads | Immutable snapshots (`List.copyOf`, `Map.copyOf`) |
| No data loss on exception/timeout | Discard buffer or commit deliberately |
| Isolation between sessions | Per-session lock, no global hot-path locks |

### 2.2 Deliberate limits

| Not guaranteed | Reason |
|---|---|
| Lossless restart | State lives only on the heap |
| Crash recovery | No durable journal |
| Multi-node / HA | No shared store |
| Audit across process boundaries | No persisted events |
| Large histories over weeks | Memory limit |
| Duplicate external side effects on crash | Only idempotency helps |

## 3. Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ Process (JVM or Native Image)                                │
│                                                              │
│  Engine / Coordinator / ExecutionRunner                      │
│    │                                                         │
│    ▼                                                         │
│  RuntimeState (authoritative, on the heap)                    │
│    ├─ sessions        ConcurrentHashMap<SessionId, Session>  │
│    ├─ conversations   Map<SessionId, AppendList<Message>>    │
│    ├─ journal         Map<AggregateId, EventList + AtomicLong>│
│    ├─ claims          ConcurrentHashMap<SessionId, Claim>    │
│    ├─ approvals       Map<ProjectId, Set<SavedApproval>>     │
│    ├─ instructions    Map<SessionId, State> + BlobMap        │
│    ├─ workspaces      Map<SessionId, WorkspaceState>         │
│    ├─ credentials     Map<Id, Credential>                    │
│    ├─ snapshots       Map<SnapshotId, Map<Path, byte[]>>     │
│    ├─ toolOutputs     Content-addressed Map + LRU            │
│    └─ toolCache       LRU                                   │
│                                                              │
│  The world stays real:                                       │
│    ProviderAdapter, FileSystem, ProcessRunner,               │
│    HttpTransport, RemoteShell, EmailClient                   │
└──────────────────────────────────────────────────────────────┘
```

**Important:** "All ports in-memory" applies to the **storage and
coordination ports**. The **world ports** (LLM, file system, processes, HTTP,
email) remain real adapters — otherwise it would be a simulation. For tests
there are additional fake adapters (record/replay, `FixedClock`, stub provider).

## 4. Port → In-memory adapter

> **Note (minimal mode):** The in-memory mode is trimmed to the minimum.
> Only the ports needed for direct execution have real adapters:
> `SessionStore`/`SessionReader`, `ConversationStore`, `Journal`, `ClaimStore`,
> `SessionWorkspaceStore` (folded into `SessionStore`) and `ToolOutputStore`
> (LRU-bounded). All other ports (`ApprovalStore`, `InstructionStore`,
> `CredentialStore`, `SnapshotStore`, `ToolResultCache`) are served by no-op
> adapters and hold no state. `MemoryBudget` is no longer used.

### 4.1 Storage ports

| Port | Adapter | Data structure | Notes |
|---|---|---|---|
| `SessionStore` / `SessionReader` | `InMemorySessionStore` | `ConcurrentHashMap<SessionId, SessionInfo>`; inbox as `ArrayDeque<InboxEntry>` per session | Inbox access under session lock; `list()` returns snapshot |
| `ConversationStore` / `Conversation` | `InMemoryConversationStore` | per session `ArrayList<StoredMessage>` + `AtomicLong seq` | `nextSeq` = `incrementAndGet`; `deleteFrom` = truncate under lock |
| `Journal` | `InMemoryJournal` | per aggregate `ArrayList<StoredEvent>` + `AtomicLong seq`; optional ring buffer | `append` under lock; `history` immutable; retention per ring/config |
| `ClaimStore` | `InMemoryClaimStore` | `ConcurrentHashMap<SessionId, Claim>` | `acquire` = `putIfAbsent`; `release` = `remove`; optional lease timestamp |
| `ApprovalStore` | `NoOpApprovalStore` | – | No-op in minimal mode |
| `InstructionStore` | `NoOpInstructionStore` | – | No-op in minimal mode |
| `SessionWorkspaceStore` | `InMemorySessionStore` (folded) | `Map<SessionId, WorkspaceState>` | small, non-critical |
| `CredentialStore` | `NoOpCredentialStore` | – | No-op in minimal mode |
| `SnapshotStore` | `NoOpSnapshotStore` | – | No-op in minimal mode (no revert) |
| `ToolOutputStore` | `InMemoryToolOutputStore` | `Map<String, byte[]>` (content-addressed) + LRU (max 256) | Limit per blob and globally |
| `ToolResultCache` | `NoOpToolResultCache` | – | No-op in minimal mode |

### 4.2 Runtime/human ports

| Port | Adapter | Data structure | Notes |
|---|---|---|---|
| `Clock` | `SystemClock` / `FixedClock` | – | testable |
| `SessionWake` | `DirectSessionWake` | direct `Coordinator.wake` | possibly on a virtual thread |
| `ApprovalSource` | `InMemoryApprovalSource` | `Map<RequestId, CompletableFuture<ApprovalResult>>` | blocks until answer/timeout |
| `QuestionSource` | `InMemoryQuestionSource` | `Map<QuestionId, CompletableFuture<Answer>>` | same |
| `PolicySource` | `StaticPolicySource` | `List<PolicyStatement>` | immutable |
| `NotificationChannel` | `InMemoryNotificationChannel` | `List<Notification>` | for UI/SSE |
| `EventBus` | `InProcessEventBus` | subscriber list + `publishLive` | SSE integration |

### 4.3 World ports (remain real)

| Port | Adapter | Note |
|---|---|---|
| `ProviderAdapter` | `HttpProviderAdapter` | network/streaming; `FakeProvider` for tests |
| `FileSystem` | `NioFileSystem` / `CachedFileSystem` | real files |
| `ProcessRunner` | `JdkProcessRunner` / `SandboxedProcessRunner` | real processes |
| `HttpTransport` | `JdkHttpTransport` | real HTTP |
| `RemoteShell` | `MinaSshRemoteShell` | real SSH |
| `EmailClient` | SMTP/IMAP adapter | real emails |
| `InstructionLoader` | `NioInstructionLoader` | real files |

## 5. Concurrency and consistency

**Rules from the spec:**

- **X-2:** one virtual thread per execution.
- **X-3:** tool calls within a step run in parallel, quota max. 8 (FIFO).
- **X-4:** no I/O under lock; `ReentrantLock` only around state change.
- **X-5:** no `ThreadLocal` for session context; session ID is passed through.
- **X-8:** waiting sessions occupy no thread (inbox).

**Locking model:**

```
per session:  ReentrantLock sessionLock
global:       no global locks in the hot path
readers:      immutable snapshots (List.copyOf, Map.copyOf)
sequences:    AtomicLong per aggregate
```

**Commit model per step:**

```
lock(session):
  1. Write events and projections into a buffer
  2. Execute tool calls (outside the lock!)
  3. Add results + ToolSuccess/ToolFailed to the buffer
  4. Commit: apply buffer to Journal/Conversation/Session
unlock
```

Thus there is **no half-persisted step**. If the step fails before the
commit, the buffer is discarded; the state remains consistent.

## 6. Fault tolerance without restart

| Failure | Behavior |
|---|---|
| **Tool exception** | `ToolFailed` event, error to the model, step continues |
| **Provider error (transient)** | Retry with backoff (`RetryPolicy`) |
| **Provider error (permanent)** | `ExecutionFailed`, claim released |
| **Timeout** | Tool/provider timeout, typed error |
| **Thread interrupt** | `Aborted`/`Interrupted`, terminal event |
| **Cancel by user** | Interrupt + `TaskCancelled`/`ExecutionInterrupted` |
| **Exception in runner** | `ExecutionFailed`, cleanup, claim released |
| **OOM risk** | Memory budget + backpressure (see §7) |
| **Shutdown (SIGTERM)** | Stop admission, interrupt executions, attempt terminal events |

### Effect ledger (optional)

```
Map<CallId, ToolEffect> effects

before tool:
    if effects contains callId && succeeded → return cached result
execute tool
after tool:
    effects.put(callId, result)
```

This prevents duplicate side effects on retries **within** the process.
For external tools, also pass an `IdempotencyKey` to the provider.

**Limit:** A crash between side effect and ledger entry is not detectable
without persistence. Therefore tools should be idempotent.

## 7. Memory budget and backpressure

| Limit | Default suggestion | Behavior on exceed |
|---|---|---|
| Max sessions | 1,000 | reject new session (HTTP 429/503) |
| Max total bytes | 512 MB – 2 GB (heap-dependent) | reject admission, evict finished sessions |
| Max messages/session | 10,000 | force compaction or reject |
| Max journal events/session | 50,000 (ring buffer) | discard oldest events (finished sessions only) |
| Max tool output/blob | 1–8 MB | reject or spill to FS (hybrid) |
| Max snapshot size | 64 MB | reject or move to FS |
| Max ToolResultCache | 10,000 entries / 128 MB | LRU eviction |
| Max background tasks | 4 global, 2/session | semaphore |

**Rules:**

- Only **finished** sessions may be evicted. Running sessions never.
- Eviction produces a `SessionEvicted` event (UI/audit).
- `MemoryBudget` counts bytes per category (messages, events, tool outputs,
  snapshots, caches) and checks **before** the commit.
- Under high pressure: reject new prompts with `429`/`503`, do not let it
  run into OOM.

## 8. Failure matrix

| Failure | State | Result |
|---|---|---|
| Exception in step | Buffer discarded, session consistent | safe |
| Provider timeout | Retry or `ExecutionFailed` | safe |
| Tool hangs | Timeout/cancel, `ToolFailed` | safe |
| JVM crash | **everything gone** | no result |
| OOM | Process dies | everything gone |
| Shutdown | Terminal events attempted, then gone | no resume |
| Restart | empty state | no recovery |

This is the deliberate boundary: **fault tolerance within the process, no
durability beyond the process.**

## 9. Configuration

```properties
# profile "memory"
engine.storage=memory
engine.memory.max-sessions=1000
engine.memory.max-bytes=314572800
engine.memory.max-tool-output-bytes=8388608
engine.memory.max-snapshot-bytes=67108864
engine.memory.max-messages-per-session=10000
engine.memory.journal-ring-events=50000
engine.memory.evict-finished=true
engine.recovery.enabled=false
```

**Startup guard:** If `engine.storage=memory` and `engine.db.kind` is set,
warn or fail fast. No silent mixed operation. The existing switch
`engine.db.kind=sqlite` remains unaffected.

## 10. Use cases

| Suitable | Not suitable |
|---|---|
| Local desktop/CLI agent (single user) | Multi-user production server |
| Tests, CI, deterministic E2E runs | HA/cluster with failover |
| Ephemeral assistants ("one run, then gone") | Audit/compliance requirements |
| Edge/embedded scenarios | Long histories over weeks |
| Prototyping, demos | Large snapshots/tool outputs |

## 11. Optional "soft persistence" (not part of the guarantee)

| Option | Benefit | Effort |
|---|---|---|
| **Debug dump on shutdown** (JSON: sessions, journal, conversation) | Post-mortem, support | small |
| **Periodic snapshot to disk** (write-behind) | Emergency recovery | medium |
| **Journal WAL on disk** | Restart-capable | large (then hybrid) |

If "no restart" applies, these options are **not** needed. A debug dump is
still useful.

## 12. Detailed implementation

### 12.1 Package structure

```
qoc/
├─ engine/
│  └─ src/main/java/de/augmentia/oc/engine/
│     ├─ core/port/...                         # unchanged (single source)
│     ├─ core/model/...                        # unchanged (single source)
│     ├─ runtime/...                           # unchanged (single source)
│     ├─ adapter/memory/                       # NEW
│     │  ├─ RuntimeState.java                  # container for all in-memory stores
│     │  ├─ MemoryBudget.java                  # byte accounting + limits
│     │  ├─ MemoryStores.java                  # bundle/facade
│     │  ├─ InMemorySessionStore.java
│     │  ├─ InMemoryConversationStore.java
│     │  ├─ InMemoryJournal.java
│     │  ├─ InMemoryClaimStore.java
│     │  ├─ InMemoryApprovalStore.java
│     │  ├─ InMemoryInstructionStore.java
│     │  ├─ InMemorySessionWorkspaceStore.java
│     │  ├─ InMemoryCredentialStore.java
│     │  ├─ InMemorySnapshotStore.java
│     │  ├─ InMemoryToolOutputStore.java
│     │  ├─ InMemoryToolResultCache.java
│     │  ├─ InMemoryApprovalSource.java
│     │  ├─ InMemoryQuestionSource.java
│     │  ├─ StaticPolicySource.java
│     │  ├─ InMemoryNotificationChannel.java
│     │  ├─ InProcessEventBus.java
│     │  ├─ DirectSessionWake.java
│     │  ├─ SystemClock.java
│     │  └─ MemoryLimitExceeded.java           # typed error
│     └─ runtime/wiring/                       # NEW
│        ├─ StorageKind.java                   # MEMORY | SQLITE | POSTGRES
│        ├─ Stores.java                        # record of all ports
│        └─ StorageFactory.java                # single switch
├─ quarkus/
│  └─ src/main/java/de/augmentia/oc/engine/http/
│     ├─ Engine.java                           # uses StorageFactory
│     ├─ StorageProducers.java                 # optional: CDI producer
│     └─ DatabaseMigration.java                # disabled in memory mode
└─ engine/src/test/java/de/augmentia/oc/engine/
   └─ adapter/contract/                        # NEW: shared TCK
      ├─ SessionStoreContract.java
      ├─ ConversationStoreContract.java
      ├─ JournalContract.java
      ├─ ClaimStoreContract.java
      ├─ ApprovalStoreContract.java
      ├─ InstructionStoreContract.java
      ├─ SnapshotStoreContract.java
      ├─ ToolOutputStoreContract.java
      └─ ToolResultCacheContract.java
```

### 12.2 Classes and responsibilities

| Class | Responsibility | Thread safety |
|---|---|---|
| `RuntimeState` | Holds all maps/logs and per-session locks | `ConcurrentHashMap` + `ReentrantLock` |
| `MemoryBudget` | Reserve/release bytes, high-watermark | `AtomicLong`/CAS |
| `MemoryStores` | Creates all adapters over a `RuntimeState` | immutable |
| `InMemoryJournal` | Append/history/sequence per aggregate | lock per `EventLog` |
| `InMemoryConversationStore` | Messages, `nextSeq`, `deleteFrom` | lock per session |
| `InMemorySessionStore` | Session info, inbox | lock per session |
| `InMemoryClaimStore` | `acquire`/`release`/`open` | `putIfAbsent` |
| `InMemoryApprovalStore` | Saved approvals per project | copy-on-write |
| `InMemoryInstructionStore` | State/entries/blobs | lock per session |
| `InMemorySessionWorkspaceStore` | Workspace/instance mapping | `ConcurrentHashMap` |
| `InMemoryCredentialStore` | Credentials (only from ENV/secret manager) | `ConcurrentHashMap` |
| `InMemorySnapshotStore` | Snapshot metadata + file contents | lock per snapshot |
| `InMemoryToolOutputStore` | Content-addressed blobs + LRU | lock + LRU |
| `InMemoryToolResultCache` | Idempotency cache | LRU |
| `InProcessEventBus` | In-process pub/sub for SSE | `CopyOnWriteArrayList` |
| `DirectSessionWake` | Direct `Coordinator.wake` | – |
| `MemoryLimitExceeded` | Typed error on budget exceedance | – |

### 12.3 Code sketches

#### `RuntimeState`

```java
public final class RuntimeState {
    final ConcurrentHashMap<SessionId, SessionInfo> sessions = new ConcurrentHashMap<>();
    final ConcurrentHashMap<SessionId, Deque<InboxEntry>> inbox = new ConcurrentHashMap<>();
    final ConcurrentHashMap<SessionId, ConversationLog> conversations = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, EventLog> journals = new ConcurrentHashMap<>();
    final ConcurrentHashMap<SessionId, Claim> claims = new ConcurrentHashMap<>();
    final ConcurrentHashMap<SessionId, ReentrantLock> locks = new ConcurrentHashMap<>();
    final ConcurrentHashMap<ProjectId, Set<SavedApproval>> approvals = new ConcurrentHashMap<>();
    final ConcurrentHashMap<SessionId, InstructionState> instructions = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, String> instructionBlobs = new ConcurrentHashMap<>();
    final ConcurrentHashMap<SessionId, WorkspaceState> workspaces = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Credential> credentials = new ConcurrentHashMap<>();
    final ConcurrentHashMap<SnapshotId, Map<Path, byte[]>> snapshots = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, byte[]> toolOutputs = new ConcurrentHashMap<>();
    final ConcurrentHashMap<IdempotencyKey, JsonValue> toolCache = new ConcurrentHashMap<>();
    final MemoryBudget budget;

    ReentrantLock lock(SessionId id) {
        return locks.computeIfAbsent(id, k -> new ReentrantLock());
    }
}
```

> `RuntimeState` is **not** part of the ports. It is the shared backend of
> the memory adapters and stays in the `adapter.memory` package.

#### `MemoryBudget`

```java
public final class MemoryBudget {
    private final long maxBytes;
    private final AtomicLong used = new AtomicLong();

    public boolean reserve(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("bytes must not be negative");
        while (true) {
            long current = used.get();
            if (current + bytes > maxBytes) return false;
            if (used.compareAndSet(current, current + bytes)) return true;
        }
    }

    public void release(long bytes) {
        if (bytes > 0) used.addAndGet(-bytes);
    }

    public long used() { return used.get(); }
    public long max() { return maxBytes; }
    public long remaining() { return maxBytes - used.get(); }
}
```

**Reservation points:**

| Store | Reserves |
|---|---|
| `InMemoryJournal` | estimated event size (JSON + metadata) |
| `InMemoryConversationStore` | message size |
| `InMemoryToolOutputStore` | blob bytes |
| `InMemorySnapshotStore` | file bytes |
| `InMemoryToolResultCache` | entry size |
| `InMemorySessionStore` | small metadata (optional) |

On `reserve == false` → `MemoryLimitExceeded` (typed), never write half.

#### `InMemoryJournal`

```java
public final class InMemoryJournal implements Journal {
    private final RuntimeState state;
    private final Clock clock;

    public InMemoryJournal(RuntimeState state, Clock clock) {
        this.state = state;
        this.clock = clock;
    }

    @Override
    public Seq append(EventDraft draft) {
        return state.journals
                .computeIfAbsent(draft.aggregateId(), k -> new EventLog(state.budget, clock))
                .append(draft);
    }

    @Override
    public List<StoredEvent> history(String aggregateId, Seq after) {
        EventLog log = state.journals.get(aggregateId);
        return log == null ? List.of() : log.snapshot(after);
    }

    @Override
    public List<Seq> appendAll(List<EventDraft> drafts) {
        return drafts.stream().map(this::append).toList();
    }

    @Override
    public void deleteAggregate(String aggregateId) {
        EventLog removed = state.journals.remove(aggregateId);
        if (removed != null) removed.releaseBudget();
    }

    @Override
    public void deleteOlderThan(Instant cutoff) {
        // In-memory: retention only via ring buffer/eviction, no time scan.
    }
}

final class EventLog {
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayList<StoredEvent> events = new ArrayList<>();
    private final MemoryBudget budget;
    private final Clock clock;
    private long seq;

    EventLog(MemoryBudget budget, Clock clock) {
        this.budget = budget;
        this.clock = clock;
    }

    Seq append(EventDraft draft) {
        lock.lock();
        try {
            int size = estimateBytes(draft); // e.g. via EventCodec/Json
            if (!budget.reserve(size)) throw new MemoryLimitExceeded("journal");
            seq++;
            events.add(StoredEvent.from(draft.aggregateId(), Seq.of(seq), draft, clock.now()));
            return Seq.of(seq);
        } finally {
            lock.unlock();
        }
    }

    List<StoredEvent> snapshot(Seq after) {
        lock.lock();
        try {
            long from = after == null ? 0 : after.value();
            return List.copyOf(events.subList((int) Math.min(from, events.size()), events.size()));
        } finally {
            lock.unlock();
        }
    }
}
```

> **Important:** `lastSeq`/`nextSeq` are read from `seq` — not from
> `history().size()`.

#### `InMemoryClaimStore`

```java
public final class InMemoryClaimStore implements ClaimStore {
    private final RuntimeState state;

    @Override
    public Option<Claim> acquire(SessionId session, Instant at) {
        Claim fresh = new Claim(session, at, 0);
        Claim previous = state.claims.putIfAbsent(session, fresh);
        return previous == null ? Option.of(fresh) : Option.none();
    }

    @Override
    public void release(SessionId session) {
        state.claims.remove(session);
    }

    @Override
    public List<Claim> open() {
        return List.copyOf(state.claims.values());
    }
}
```

#### `InMemoryConversationStore`

```java
public final class InMemoryConversationStore implements ConversationStore {
    private final RuntimeState state;

    @Override
    public Seq nextSeq(SessionId session) {
        return Seq.of(state.conversations
                .computeIfAbsent(session, k -> new ConversationLog(state.budget))
                .next());
    }

    @Override
    public void insertWithParent(SessionId session, Seq seq, Message message,
                                 Option<MessageId> parent) {
        ConversationLog log = state.conversations
                .computeIfAbsent(session, k -> new ConversationLog(state.budget));
        log.append(seq, message, parent);
    }

    @Override
    public List<Message> messages(SessionId session, Seq after) {
        ConversationLog log = state.conversations.get(session);
        return log == null ? List.of() : log.snapshot(after);
    }

    @Override
    public void deleteFrom(SessionId session, Seq seq) {
        ConversationLog log = state.conversations.get(session);
        if (log != null) log.truncate(seq);
    }
}
```

#### `InMemoryToolOutputStore` (content-addressed + LRU)

```java
public final class InMemoryToolOutputStore implements ToolOutputStore {
    private final RuntimeState state;
    private final int maxEntries;
    private final Map<String, byte[]> blobs =
            new LinkedHashMap<>(16, 0.75f, true);      // hash → bytes
    private final Map<String, ArtifactRef> refs = new HashMap<>();

    public InMemoryToolOutputStore(RuntimeState state, int maxEntries) {
        this.state = state;
        this.maxEntries = maxEntries;
    }

    @Override
    public synchronized ArtifactRef save(SessionId session, String suffix, byte[] bytes) {
        if (!state.budget.reserve(bytes.length)) throw new MemoryLimitExceeded("tool-output");
        String id = "art_" + suffix;
        String hash = sha256(bytes);
        ArtifactRef ref = new ArtifactRef(session, id, "application/octet-stream",
                hash, bytes.length);
        blobs.put(hash, bytes);
        refs.put(id, ref);
        evictIfNeeded();
        return ref;
    }

    @Override
    public synchronized Option<ArtifactRef> find(SessionId session, String id) {
        ArtifactRef ref = refs.get(id);
        return ref != null && ref.session().equals(session)
                ? Option.of(ref) : Option.none();
    }

    @Override
    public synchronized byte[] load(SessionId session, String id) {
        ArtifactRef ref = refs.get(id);
        if (ref == null || !ref.session().equals(session)) return null;
        return blobs.get(ref.sha256());
    }

    @Override
    public synchronized void sweep(Instant olderThan) {
        // In-memory: retention via LRU/eviction, no time scan.
    }

    private void evictIfNeeded() {
        while (refs.size() > maxEntries) {
            Iterator<Map.Entry<String, ArtifactRef>> it = refs.entrySet().iterator();
            ArtifactRef eldest = it.next().getValue();
            it.remove();
            blobs.remove(eldest.sha256());
            state.budget.release(eldest.size());
        }
    }
}
```

#### `DirectSessionWake`

```java
public final class DirectSessionWake implements SessionWake {
    private final Coordinator coordinator;

    @Override
    public void wake(SessionId session) {
        coordinator.wake(session, ExecutionScope.INPUT);
    }
}
```

### 12.4 Commit and locking implementation

**Goal:** A step is atomic; readers never see a half-state.

```java
final class SessionMutex {
    private final ReentrantLock lock = new ReentrantLock();

    <T> T withLock(Supplier<T> action) {
        lock.lock();
        try { return action.get(); }
        finally { lock.unlock(); }
    }
}
```

**Step buffer:**

```java
final class StepBuffer {
    final List<EventDraft> events = new ArrayList<>();
    final List<Message> messages = new ArrayList<>();
    final List<ToolOutcome> outcomes = new ArrayList<>();

    void commit(RuntimeState state, SessionId session) {
        state.lock(session).lock();
        try {
            for (EventDraft event : events) state.journals...append(event);
            for (Message message : messages) state.conversations...append(message);
            state.sessions.put(session, updatedSession);
        } finally {
            state.lock(session).unlock();
        }
    }
}
```

**Rules:**

1. Tool calls run **outside** the session lock.
2. Only the commit (events + projections) runs under the lock.
3. Readers receive `List.copyOf`/`Map.copyOf`.
4. Sequences are incremented **under the same lock** as the append.
5. No `synchronized` around I/O (X-4).

### 12.5 Eviction and retention

| Category | Strategy |
|---|---|
| **Finished sessions** | LRU by `finishedAt`, only when `state == IDLE` |
| **Journal** | Ring buffer per aggregate (max N events); oldest first |
| **Conversation** | Stays complete until session eviction; compaction reduces |
| **Tool outputs** | LRU over `LinkedHashMap` (access order) |
| **Snapshots** | LRU or fully disabled (`engine.memory.snapshots=false`) |
| **ToolResultCache** | LRU with cap |
| **EventBus subscribers** | `CopyOnWriteArrayList`, no eviction |

**Eviction event:** `SessionEvicted { session, at, reason }` for UI/audit.
Running sessions are **never** evicted.

### 12.6 Wiring

#### `StorageKind` and `Stores`

```java
public enum StorageKind { MEMORY, SQLITE, POSTGRES }

public record Stores(
        SessionStore sessions,
        ConversationStore conversation,
        Journal journal,
        ClaimStore claims,
        ApprovalStore approvals,
        InstructionStore instructions,
        SessionWorkspaceStore workspaces,
        CredentialStore credentials,
        SnapshotStore snapshots,
        ToolOutputStore toolOutputs,
        ToolResultCache toolCache) {}
```

#### `StorageFactory`

```java
public final class StorageFactory {
    public static Stores create(StorageKind kind, RuntimeState memory,
                                DataSource dataSource) {
        return switch (kind) {
            case MEMORY -> MemoryStores.create(memory);
            case SQLITE -> SqliteStores.create(dataSource);
            case POSTGRES -> SqlStores.create(dataSource);
        };
    }
}
```

#### Quarkus producer (optional)

```java
@ApplicationScoped
public class StorageProducers {
    @Produces @Singleton
    Stores stores(@ConfigProperty(name = "engine.storage", defaultValue = "postgres") String kind,
                  RuntimeState memory, DataSource dataSource) {
        return StorageFactory.create(StorageKind.from(kind), memory, dataSource);
    }
}
```

**Rule:** The only `switch` over the storage type lives in
`StorageFactory`. `Engine`, `Coordinator`, `Step` and all runtime classes
remain unchanged.

### 12.7 Build options

#### Option A – Runtime switch (simplest path)

One artifact contains all adapters; `engine.storage` decides at runtime.

```properties
engine.storage=memory
```

| Advantage | Disadvantage |
|---|---|
| one build, one image | all adapters + DB extensions in the image |
| fast switch via config | larger native image |
| ideal for development/tests | – |

#### Option B – Maven profile `storage-memory`

Lean build without DB extensions. In `quarkus/pom.xml`:

```xml
<profile>
  <id>storage-memory</id>
  <properties>
    <quarkus.datasource.devservices.enabled>false</quarkus.datasource.devservices.enabled>
    <quarkus.flyway.enabled>false</quarkus.flyway.enabled>
    <engine.storage>memory</engine.storage>
  </properties>
</profile>
```

Additionally the DB extensions (`quarkus-jdbc-postgresql`,
`quarkus-flyway`, possibly `quarkus-agroal`) must be removed from the
dependencies in this profile or provided via a separate module.
`DatabaseMigration` gets a profile guard:

```java
@ApplicationScoped
@IfBuildProfile("!storage-memory")
public class DatabaseMigration { ... }
```

Build:

```bash
mvn -pl quarkus -am package -Pstorage-memory -DskipTests
mvn -pl quarkus -am package -Pstorage-memory -DskipTests \
    -Dquarkus.package.type=native
```

#### Option C – Separate module `qoc-memory` (lean, but refactor)

A separate runnable module containing only `engine` + the HTTP skin (without
DB extensions). For this the HTTP skin must be extracted from `quarkus/`
into a reusable module. Effort: medium–large; worthwhile when a really small
native image is needed.

```
qoc/
├─ engine/
├─ http-skin/            # extracted from quarkus/ (without DB)
├─ quarkus/              # production: postgres/sqlite
└─ quarkus-memory/       # memory: http-skin + engine + memory adapters
```

#### Option D – Quarkus build profile

```java
@ApplicationScoped
@IfBuildProfile("memory")
public class MemoryStorageProducers { ... }

@ApplicationScoped
@IfBuildProfile("!memory")
public class JdbcStorageProducers { ... }
```

Build with `-Dquarkus.profile=memory`. Simple, but the DB extensions
remain in the image unless excluded via dependency.

#### Comparison

| Option | Effort | Image size | Switch | Recommendation |
|---|---|---|---|---|
| A Runtime switch | small | large | at runtime | ✅ Start |
| B Maven profile | medium | medium | build | ✅ for production |
| C Separate module | large | small | build | later |
| D Quarkus profile | small–medium | medium | build | ✅ supplement |

### 12.8 Configuration matrix

| Property | Memory | SQLite | Postgres |
|---|---|---|---|
| `engine.storage` | `memory` | `sqlite` | `postgres` |
| `engine.db.kind` | – (ignored) | `sqlite` | `postgresql` |
| `engine.recovery.enabled` | `false` | `true` | `true` |
| `quarkus.datasource.*` | not needed | SQLite URL | Neon/Postgres |
| `quarkus.flyway.enabled` | `false` | `true` | `true` |
| `engine.memory.max-bytes` | set | – | – |
| `engine.memory.max-sessions` | set | – | – |
| `engine.memory.max-tool-output-bytes` | set | – | – |
| `engine.memory.snapshots` | `false` (default) | `true` | `true` |

**Startup guard:**

```java
if (kind == MEMORY && dataSourcePresent) {
    LOG.warn("engine.storage=memory ignores the configured DataSource");
}
if (kind == MEMORY && recoveryEnabled) {
    throw new IllegalStateException("Recovery is not available in memory mode");
}
```

### 12.9 Tests and contract tests (TCK)

**Goal:** Same semantics for all adapters.

```java
abstract class SessionStoreContract {
    abstract SessionStore store();

    @Test void putAndRead() { ... }
    @Test void listReturnsSnapshot() { ... }
    @Test void deleteRemovesSession() { ... }
}

class InMemorySessionStoreTest extends SessionStoreContract {
    private final RuntimeState state = new RuntimeState(new MemoryBudget(1 << 20));
    @Override SessionStore store() { return new InMemorySessionStore(state); }
}

class SqliteSessionStoreTest extends SessionStoreContract { ... }
class SqlSessionStoreTest extends SessionStoreContract { ... }
```

**Test matrix:**

| Test type | Memory | SQLite | Postgres |
|---|---|---|---|
| Unit/contract | ✅ | ✅ | ✅ |
| Quarkus integration | `@TestProfile(MemoryProfile)` | SQLite profile | Postgres/Testcontainers |
| E2E (prompt → tools) | ✅ (fake provider) | ✅ | ✅ |
| Restart/recovery | ❌ (not defined) | ✅ | ✅ |
| Load/eviction | ✅ | – | – |

**CI:** Matrix job per storage kind; same test code, different factory.

### 12.10 Migration steps (concrete)

1. **Introduce TCK** (tests only, no production change).
2. **Create `StorageKind`/`Stores`/`StorageFactory`** in the `engine` module.
3. **Implement `RuntimeState` + `MemoryBudget`**.
4. **Implement memory adapters** port by port, switch TCK green.
5. **Switch `Engine` wiring** from `if (sqlite)` to `StorageFactory`.
6. **Make `DatabaseMigration`** deactivatable via profile/config.
7. **Extend `application.properties`** with `engine.storage` and memory limits.
8. **Add startup guard** and health output (`storage: memory`).
9. **Maven profile `storage-memory`** (optional, for lean builds).
10. **Load tests + eviction tests**.
11. **Update docs** (`InMemory.md`, `Storage-alternative.md`).

### 12.11 Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Unbounded growth | OOM | `MemoryBudget` + eviction + backpressure |
| `ConcurrentHashMap` race | inconsistent reads | Per-session lock + immutable snapshots |
| Blocking under lock | pinning/deadlock | I/O outside the lock (X-4) |
| Quarkus datasource starts anyway | startup failure | profile without DB extensions / config guard |
| Native image size | large | Maven profile or separate module |
| Recovery accidentally enabled | inconsistency | startup guard, `engine.recovery.enabled=false` |
| Duplicate external side effects | data errors | `ToolResultCache` + idempotency key |
| Eviction of running sessions | data loss | eviction only for `IDLE`/`FINISHED` |

## 13. Example: a session in memory

```java
// pseudocode
var state = new RuntimeState(new MemoryBudget(1L << 30));
var sessions = new InMemorySessionStore(state);
var journal  = new InMemoryJournal(state);
var convo    = new InMemoryConversationStore(state);
var claims   = new InMemoryClaimStore(state);

var coordinator = new Coordinator(claims, journal, systemClock,
        (session, execution, scope) -> runner.run(session, execution, scope));

// Prompt
var session = sessions.create(/* location, agent, ... */);
convo.insert(session, convo.nextSeq(session), userMessage);
journal.append(new EventDraft(session.value(), 1,
        new SessionEvent.InboxEnqueued(/* ... */)));
coordinator.wake(session, ExecutionScope.INPUT);

// Everything stays on the heap. No restart. No DB.
```

## 14. Comparison with existing modes

| Aspect | Memory | SQLite | PostgreSQL |
|---|---|---|---|
| Latency | minimal | low | network |
| Persistence | ❌ | ✅ (file) | ✅ |
| Multi-node | ❌ | ❌ | ✅ |
| Backup | ❌ | file/WAL | DB backup |
| Operational effort | minimal | low | medium |
| Suitable for | tests, local, ephemeral | single-node | multi-node |

## 15. Open questions

1. Should the memory runtime be its own **profile** (`engine.storage=memory`)
   or just a test setup?
2. How aggressive should **eviction** of finished sessions be?
3. Should **snapshots** be offered in-memory at all or rejected by
   default?
4. Is a **debug dump** on shutdown needed?
5. Should a **hybrid** (memory + optional FS spill for large blobs) be
   provided?

## 16. References (code)

| Topic | Path |
|---|---|
| Storage ports | `qoc/engine/src/main/java/de/augmentia/oc/engine/core/port/store/` |
| Runtime ports | `qoc/engine/src/main/java/de/augmentia/oc/engine/core/port/runtime/` |
| Human ports | `qoc/engine/src/main/java/de/augmentia/oc/engine/core/port/human/` |
| World ports | `qoc/engine/src/main/java/de/augmentia/oc/engine/core/port/world/` |
| Coordinator | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Coordinator.java` |
| ExecutionRunner | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/ExecutionRunner.java` |
| Step | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/step/Step.java` |
| BackgroundTasks | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/BackgroundTasks.java` |
| SQLite adapter (model) | `qoc/engine/src/main/java/de/augmentia/oc/engine/adapter/sqlite/` |
| Engine wiring | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java` |
| Storage analysis | [`Storage-alternative.md`](./Storage-alternative.md) |
| Core description | [`Core.md`](./Core.md) |
| Async/heartbeat | [`Async-use.md`](./Async-use.md) |