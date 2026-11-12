# In-Memory-Runtime

> **Zweck:** Beschreibung einer vollständig flüchtigen Runtime, in der alle
> Storage- und Koordinations-Ports durch In-Memory-Adapter ersetzt sind.
> Kein Restart-Recovery, dafür konsistente und fehlertolerante Ausführung
> innerhalb der Prozess-Lebensdauer.
>
> **Status:** 2026-11-09
>
> **Abgrenzung:** Ergänzt [`Storage-alternative.md`](./Storage-alternative.md)
> und [`Core.md`](./Core.md). Bei Widersprüchen gilt die Spec unter
> [`spec/`](../spec/README.md).

## 1. Zielbild

Eine **In-Memory-Runtime** ist ein Betriebsmodus, in dem der gesamte
Engine-Zustand im Heap des laufenden Prozesses liegt:

- keine Datenbank,
- kein Netzwerk-Storage,
- keine Persistenz,
- keine Wiederaufnahme nach einem Neustart.

Dafür:

- minimale Latenz (keine I/O-Roundtrips),
- einfache Deployment-Topologie (ein Prozess),
- deterministische Tests,
- volle Nutzung der bestehenden Ports und Invarianten.

**Leitbild:** *Fehlertoleranz im Prozess, keine Dauerhaftigkeit über den
Prozess hinaus.*

## 2. Garantien und Grenzen

### 2.1 Garantien

| Garantie | Umsetzung |
|---|---|
| Ein Schritt ist atomar (kein halber Zustand) | Commit am Step-Ende unter Per-Session-Lock |
| Höchstens eine Execution pro Session | `ClaimStore` als `ConcurrentHashMap` |
| Tool-Call vor Side-Effect sichtbar (G-03) | In-Memory-Journal, Append vor Ausführung |
| Genau ein Terminal-Event pro Execution | `Coordinator` publiziert einmalig |
| Konsistente Reads | Immutable Snapshots (`List.copyOf`, `Map.copyOf`) |
| Kein Datenverlust bei Exception/Timeout | Buffer verwerfen oder gezielt committen |
| Isolation zwischen Sessions | Per-Session-Lock, keine globalen Hot-Path-Locks |

### 2.2 Bewusste Grenzen

| Nicht garantiert | Grund |
|---|---|
| Verlustfreier Neustart | Zustand liegt nur im Heap |
| Crash-Recovery | Kein durables Journal |
| Multi-Node / HA | Kein geteilter Store |
| Audit über Prozessgrenzen | Keine persistierten Events |
| Große Histories über Wochen | Speicherlimit |
| Doppelte externe Side-Effects bei Crash | Nur Idempotenz hilft |

## 3. Architektur

```
┌──────────────────────────────────────────────────────────────┐
│ Prozess (JVM oder Native Image)                              │
│                                                              │
│  Engine / Coordinator / ExecutionRunner                      │
│    │                                                         │
│    ▼                                                         │
│  RuntimeState (autoritativ, im Heap)                         │
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
│  Welt bleibt echt:                                           │
│    ProviderAdapter, FileSystem, ProcessRunner,               │
│    HttpTransport, RemoteShell, EmailClient                   │
└──────────────────────────────────────────────────────────────┘
```

**Wichtig:** „Alle Ports in In-Memory" betrifft die **Storage- und
Koordinations-Ports**. Die **Welt-Ports** (LLM, Dateisystem, Prozesse, HTTP,
E-Mail) bleiben echte Adapter – sonst wäre es eine Simulation. Für Tests
gibt es zusätzlich Fake-Adapter (Record/Replay, `FixedClock`, Stub-Provider).

## 4. Port → In-Memory-Adapter

### 4.1 Storage-Ports

| Port | Adapter | Datenstruktur | Hinweise |
|---|---|---|---|
| `SessionStore` / `SessionReader` | `InMemorySessionStore` | `ConcurrentHashMap<SessionId, SessionInfo>`; Inbox als `ArrayDeque<InboxEntry>` pro Session | Inbox-Zugriff unter Session-Lock; `list()` liefert Snapshot |
| `ConversationStore` / `Conversation` | `InMemoryConversationStore` | pro Session `ArrayList<StoredMessage>` + `AtomicLong seq` | `nextSeq` = `incrementAndGet`; `deleteFrom` = Truncate unter Lock |
| `Journal` | `InMemoryJournal` | pro Aggregat `ArrayList<StoredEvent>` + `AtomicLong seq`; optional Ringbuffer | `append` unter Lock; `history` immutable; Retention per Ring/Konfiguration |
| `ClaimStore` | `InMemoryClaimStore` | `ConcurrentHashMap<SessionId, Claim>` | `acquire` = `putIfAbsent`; `release` = `remove`; optional Lease-Timestamp |
| `ApprovalStore` | `InMemoryApprovalStore` | `Map<ProjectId, Set<SavedApproval>>` | Copy-on-Write für Reader |
| `InstructionStore` | `InMemoryInstructionStore` | `Map<SessionId, InstructionState>`, `Map<Hash, String> blobs`, `Map<SessionId, Map<Key, Entry>>` | Blobs content-addressed |
| `SessionWorkspaceStore` | `InMemorySessionWorkspaceStore` | `Map<SessionId, WorkspaceState>` | klein, unkritisch |
| `CredentialStore` | `InMemoryCredentialStore` | `Map<String, Credential>` | Secrets aus ENV/Secret-Manager; nie loggen |
| `SnapshotStore` | `InMemorySnapshotStore` | `Map<SnapshotId, Map<Path, byte[]>>` | **größter Speicherfresser**; hart limitieren |
| `ToolOutputStore` | `InMemoryToolOutputStore` | `Map<String, byte[]>` (content-addressed) + LRU | Limit pro Blob und global |
| `ToolResultCache` | `InMemoryToolResultCache` | Caffeine/LRU oder `LinkedHashMap` mit Cap | reiner Cache, kein Durability |

### 4.2 Runtime-/Human-Ports

| Port | Adapter | Datenstruktur | Hinweise |
|---|---|---|---|
| `Clock` | `SystemClock` / `FixedClock` | – | testbar |
| `SessionWake` | `DirectSessionWake` | direkter `Coordinator.wake` | ggf. auf virtuellem Thread |
| `ApprovalSource` | `InMemoryApprovalSource` | `Map<RequestId, CompletableFuture<ApprovalResult>>` | blockiert bis Antwort/Timeout |
| `QuestionSource` | `InMemoryQuestionSource` | `Map<QuestionId, CompletableFuture<Answer>>` | dito |
| `PolicySource` | `StaticPolicySource` | `List<PolicyStatement>` | unveränderlich |
| `NotificationChannel` | `InMemoryNotificationChannel` | `List<Notification>` | für UI/SSE |
| `EventBus` | `InProcessEventBus` | Subscriber-Liste + `publishLive` | SSE-Anbindung |

### 4.3 Welt-Ports (bleiben echt)

| Port | Adapter | Anmerkung |
|---|---|---|
| `ProviderAdapter` | `HttpProviderAdapter` | Netzwerk/Streaming; für Tests `FakeProvider` |
| `FileSystem` | `NioFileSystem` / `CachedFileSystem` | echte Dateien |
| `ProcessRunner` | `JdkProcessRunner` / `SandboxedProcessRunner` | echte Prozesse |
| `HttpTransport` | `JdkHttpTransport` | echtes HTTP |
| `RemoteShell` | `MinaSshRemoteShell` | echtes SSH |
| `EmailClient` | SMTP/IMAP-Adapter | echte E-Mails |
| `InstructionLoader` | `NioInstructionLoader` | echte Dateien |

## 5. Nebenläufigkeit und Konsistenz

**Regeln aus der Spec:**

- **X-2:** eine virtuelle Thread pro Execution.
- **X-3:** Tool-Calls eines Steps parallel, Quota max. 8 (FIFO).
- **X-4:** kein I/O unter Lock; `ReentrantLock` nur um State-Change.
- **X-5:** kein `ThreadLocal` für Session-Kontext; Session-ID wird durchgereicht.
- **X-8:** wartende Sessions belegen keinen Thread (Inbox).

**Locking-Modell:**

```
per Session:  ReentrantLock sessionLock
global:       keine globalen Locks im Hot-Path
Reader:       immutable Snapshots (List.copyOf, Map.copyOf)
Sequenzen:    AtomicLong pro Aggregat
```

**Commit-Modell pro Step:**

```
lock(session):
  1. Events und Projektionen in einen Buffer schreiben
  2. Tool-Calls ausführen (außerhalb des Locks!)
  3. Ergebnisse + ToolSuccess/ToolFailed in den Buffer
  4. Commit: Buffer in Journal/Conversation/Session anwenden
unlock
```

Damit gibt es **keinen halb gespeicherten Schritt**. Schlägt der Step vor dem
Commit fehl, wird der Buffer verworfen; der Zustand bleibt konsistent.

## 6. Fehlertoleranz ohne Restart

| Fehlerfall | Verhalten |
|---|---|
| **Tool-Exception** | `ToolFailed`-Event, Fehler an das Modell, Step läuft weiter |
| **Provider-Fehler transient** | Retry mit Backoff (`RetryPolicy`) |
| **Provider-Fehler permanent** | `ExecutionFailed`, Claim frei |
| **Timeout** | Tool-/Provider-Timeout, typisierter Fehler |
| **Thread-Interrupt** | `Aborted`/`Interrupted`, Terminal-Event |
| **Cancel durch User** | Interrupt + `TaskCancelled`/`ExecutionInterrupted` |
| **Exception im Runner** | `ExecutionFailed`, Cleanup, Claim frei |
| **OOM-Gefahr** | Speicherbudget + Backpressure (siehe §7) |
| **Shutdown (SIGTERM)** | Admission stoppen, Executions interrupten, Terminal-Events versuchen |

### Effect-Ledger (optional)

```
Map<CallId, ToolEffect> effects

before tool:
    if effects contains callId && succeeded → return cached result
execute tool
after tool:
    effects.put(callId, result)
```

Das verhindert doppelte Side-Effects bei Retries **innerhalb** des Prozesses.
Für externe Tools zusätzlich `IdempotencyKey` an den Anbieter.

**Grenze:** Ein Crash zwischen Side-Effect und Ledger-Eintrag ist ohne
Persistenz nicht erkennbar. Deshalb sollten Tools idempotent sein.

## 7. Speicherbudget und Backpressure

| Limit | Default-Vorschlag | Verhalten bei Überschreitung |
|---|---|---|
| Max. Sessions | 1.000 | neue Session ablehnen (HTTP 429/503) |
| Max. Bytes gesamt | 512 MB – 2 GB (heap-abhängig) | Admission ablehnen, beendete Sessions evicten |
| Max. Messages/Session | 10.000 | Compaction erzwingen oder ablehnen |
| Max. Journal-Events/Session | 50.000 (Ringbuffer) | älteste Events verwerfen (nur beendete Sessions) |
| Max. Tool-Output/Blob | 1–8 MB | ablehnen oder auf FS spillen (Hybrid) |
| Max. Snapshot-Größe | 64 MB | ablehnen oder auf FS |
| Max. ToolResultCache | 10.000 Einträge / 128 MB | LRU-Eviction |
| Max. Background-Tasks | 4 global, 2/Session | Semaphore |

**Regeln:**

- Nur **beendete** Sessions dürfen evicted werden. Laufende Sessions nie.
- Eviction erzeugt ein `SessionEvicted`-Event (UI/Audit).
- `MemoryBudget` zählt Bytes pro Kategorie (Messages, Events, ToolOutputs,
  Snapshots, Caches) und prüft **vor** dem Commit.
- Bei hohem Druck: neue Prompts mit `429`/`503` ablehnen, nicht OOM laufen
  lassen.

## 8. Ausfallmatrix

| Ausfall | Zustand | Ergebnis |
|---|---|---|
| Exception im Step | Buffer verworfen, Session konsistent | sicher |
| Provider-Timeout | Retry oder `ExecutionFailed` | sicher |
| Tool hängt | Timeout/Cancel, `ToolFailed` | sicher |
| JVM-Crash | **alles weg** | kein Ergebnis |
| OOM | Prozess stirbt | alles weg |
| Shutdown | Terminal-Events versucht, dann weg | kein Resume |
| Neustart | leerer State | keine Recovery |

Das ist die bewusste Abgrenzung: **Fehlertoleranz im Prozess, keine
Dauerhaftigkeit über den Prozess hinaus.**

## 9. Konfiguration

```properties
# Profil "memory"
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

**Startup-Guard:** Wenn `engine.storage=memory` und `engine.db.kind`
gesetzt ist, warnen oder fail-fast. Kein stiller Mischbetrieb. Der
bestehende Schalter `engine.db.kind=sqlite` bleibt davon unberührt.

## 10. Einsatzszenarien

| Geeignet | Nicht geeignet |
|---|---|
| Lokaler Desktop-/CLI-Agent (single user) | Multi-User-Produktionsserver |
| Tests, CI, deterministische E2E-Läufe | HA/Cluster mit Failover |
| Ephemere Assistenten („ein Lauf, dann weg") | Audit-/Compliance-Anforderungen |
| Edge-/Embedded-Szenarien | Lange Histories über Wochen |
| Prototyping, Demos | Große Snapshots/Tool-Outputs |

## 11. Optionale „Soft-Persistenz" (nicht Teil der Garantie)

| Option | Nutzen | Aufwand |
|---|---|---|
| **Debug-Dump bei Shutdown** (JSON: Sessions, Journal, Conversation) | Post-Mortem, Support | klein |
| **Periodischer Snapshot auf Platte** (write-behind) | Notfall-Wiederherstellung | mittel |
| **Journal-WAL auf Platte** | Restart-fähig | groß (dann Hybrid) |

Wenn „ohne Restart" gilt, sind diese Optionen **nicht** nötig. Ein
Debug-Dump ist trotzdem sinnvoll.

## 12. Detaillierte Umsetzung

### 12.1 Package-Struktur

```
qoc/
├─ engine/
│  └─ src/main/java/de/augmentia/oc/engine/
│     ├─ core/port/...                         # unverändert (Single Source)
│     ├─ core/model/...                        # unverändert (Single Source)
│     ├─ runtime/...                           # unverändert (Single Source)
│     ├─ adapter/memory/                       # NEU
│     │  ├─ RuntimeState.java                  # Container aller In-Memory-Stores
│     │  ├─ MemoryBudget.java                  # Bytes-Accounting + Limits
│     │  ├─ MemoryStores.java                  # Bündel/Fassade
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
│     │  └─ MemoryLimitExceeded.java           # typisierter Fehler
│     └─ runtime/wiring/                       # NEU
│        ├─ StorageKind.java                   # MEMORY | SQLITE | POSTGRES
│        ├─ Stores.java                        # Record aller Ports
│        └─ StorageFactory.java                # einziger Switch
├─ quarkus/
│  └─ src/main/java/de/augmentia/oc/engine/http/
│     ├─ Engine.java                           # nutzt StorageFactory
│     ├─ StorageProducers.java                 # optional: CDI-Producer
│     └─ DatabaseMigration.java                # im Memory-Modus deaktivieren
└─ engine/src/test/java/de/augmentia/oc/engine/
   └─ adapter/contract/                        # NEU: gemeinsame TCK
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

### 12.2 Klassen und Verantwortlichkeiten

| Klasse | Verantwortung | Thread-Safety |
|---|---|---|
| `RuntimeState` | Hält alle Maps/Logs und die Per-Session-Locks | `ConcurrentHashMap` + `ReentrantLock` |
| `MemoryBudget` | Bytes reservieren/freigeben, High-Watermark | `AtomicLong`/CAS |
| `MemoryStores` | Erzeugt alle Adapter über einem `RuntimeState` | immutable |
| `InMemoryJournal` | Append/History/Sequenz pro Aggregat | Lock pro `EventLog` |
| `InMemoryConversationStore` | Messages, `nextSeq`, `deleteFrom` | Lock pro Session |
| `InMemorySessionStore` | Session-Info, Inbox | Lock pro Session |
| `InMemoryClaimStore` | `acquire`/`release`/`open` | `putIfAbsent` |
| `InMemoryApprovalStore` | gespeicherte Approvals pro Projekt | Copy-on-Write |
| `InMemoryInstructionStore` | State/Einträge/Blobs | Lock pro Session |
| `InMemorySessionWorkspaceStore` | Workspace-/Instance-Zuordnung | `ConcurrentHashMap` |
| `InMemoryCredentialStore` | Credentials (nur aus ENV/Secret-Manager) | `ConcurrentHashMap` |
| `InMemorySnapshotStore` | Snapshot-Metadaten + Dateiinhalte | Lock pro Snapshot |
| `InMemoryToolOutputStore` | Content-addressed Blobs + LRU | Lock + LRU |
| `InMemoryToolResultCache` | Idempotenz-Cache | LRU |
| `InProcessEventBus` | In-Process-Pub/Sub für SSE | `CopyOnWriteArrayList` |
| `DirectSessionWake` | `Coordinator.wake` direkt | – |
| `MemoryLimitExceeded` | typisierter Fehler bei Budgetüberschreitung | – |

### 12.3 Code-Skizzen

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

> `RuntimeState` ist **nicht** Teil der Ports. Es ist das gemeinsame
> Backend der Memory-Adapter und bleibt im `adapter.memory`-Paket.

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

**Reservierungspunkte:**

| Store | Reserviert |
|---|---|
| `InMemoryJournal` | geschätzte Event-Größe (JSON + Metadaten) |
| `InMemoryConversationStore` | Message-Größe |
| `InMemoryToolOutputStore` | Blob-Bytes |
| `InMemorySnapshotStore` | Datei-Bytes |
| `InMemoryToolResultCache` | Eintragsgröße |
| `InMemorySessionStore` | kleine Metadaten (optional) |

Bei `reserve == false` → `MemoryLimitExceeded` (typisiert), nie halb
schreiben.

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
        // In-Memory: Retention nur über Ringbuffer/Eviction, kein Zeit-Scan.
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
            int size = estimateBytes(draft); // z. B. über EventCodec/Json
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

> **Wichtig:** `lastSeq`/`nextSeq` werden aus `seq` gelesen – kein
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

#### `InMemoryToolOutputStore` (Content-addressed + LRU)

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
        // In-Memory: Retention über LRU/Eviction, kein Zeit-Scan.
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

### 12.4 Commit- und Locking-Implementierung

**Ziel:** Ein Step ist atomar; Leser sehen nie einen halben Zustand.

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

**Step-Buffer:**

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

**Regeln:**

1. Tool-Calls laufen **außerhalb** des Session-Locks.
2. Nur der Commit (Events + Projektionen) läuft unter dem Lock.
3. Reader bekommen `List.copyOf`/`Map.copyOf`.
4. Sequenzen werden **im selben Lock** erhöht wie der Append.
5. Kein `synchronized` um I/O (X-4).

### 12.5 Eviction und Retention

| Kategorie | Strategie |
|---|---|
| **Beendete Sessions** | LRU nach `finishedAt`, nur wenn `state == IDLE` |
| **Journal** | Ringbuffer pro Aggregat (max. N Events); älteste zuerst |
| **Conversation** | bleibt vollständig bis Session-Eviction; Compaction reduziert |
| **ToolOutputs** | LRU über `LinkedHashMap` (Zugriffsreihenfolge) |
| **Snapshots** | LRU oder komplett deaktiviert (`engine.memory.snapshots=false`) |
| **ToolResultCache** | LRU mit Cap |
| **EventBus-Subscriber** | `CopyOnWriteArrayList`, keine Eviction |

**Eviction-Event:** `SessionEvicted { session, at, reason }` für UI/Audit.
Laufende Sessions werden **nie** evicted.

### 12.6 Wiring

#### `StorageKind` und `Stores`

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

#### Quarkus-Producer (optional)

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

**Regel:** Der einzige `switch` über den Storage-Typ lebt in
`StorageFactory`. `Engine`, `Coordinator`, `Step` und alle Runtime-Klassen
bleiben unverändert.

### 12.7 Build-Optionen

#### Option A – Runtime-Switch (einfachster Weg)

Ein Artefakt enthält alle Adapter; `engine.storage` entscheidet zur Laufzeit.

```properties
engine.storage=memory
```

| Vorteil | Nachteil |
|---|---|
| ein Build, ein Image | alle Adapter + DB-Extensions im Image |
| schneller Wechsel per Config | größeres Native-Image |
| ideal für Entwicklung/Tests | – |

#### Option B – Maven-Profil `storage-memory`

Lean Build ohne DB-Extensions. Im `quarkus/pom.xml`:

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

Zusätzlich müssen die DB-Extensions (`quarkus-jdbc-postgresql`,
`quarkus-flyway`, ggf. `quarkus-agroal`) in diesem Profil aus den
Dependencies entfernt oder über ein separates Modul bereitgestellt werden.
`DatabaseMigration` bekommt einen Profil-Guard:

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

#### Option C – Separates Modul `qoc-memory` (lean, aber Refactor)

Ein eigenes Runnable-Modul, das nur `engine` + den HTTP-Skin (ohne
DB-Extensions) enthält. Dafür muss der HTTP-Skin aus `quarkus/` in ein
wiederverwendbares Modul extrahiert werden. Aufwand: mittel–groß; lohnt
sich, wenn ein wirklich kleines Native-Image benötigt wird.

```
qoc/
├─ engine/
├─ http-skin/            # extrahiert aus quarkus/ (ohne DB)
├─ quarkus/              # Produktion: postgres/sqlite
└─ quarkus-memory/       # Memory: http-skin + engine + memory-Adapter
```

#### Option D – Quarkus-Build-Profile

```java
@ApplicationScoped
@IfBuildProfile("memory")
public class MemoryStorageProducers { ... }

@ApplicationScoped
@IfBuildProfile("!memory")
public class JdbcStorageProducers { ... }
```

Build mit `-Dquarkus.profile=memory`. Einfach, aber die DB-Extensions
bleiben im Image, sofern sie nicht per Dependency ausgeschlossen werden.

#### Vergleich

| Option | Aufwand | Image-Größe | Wechsel | Empfehlung |
|---|---|---|---|---|
| A Runtime-Switch | klein | groß | zur Laufzeit | ✅ Start |
| B Maven-Profil | mittel | mittel | Build | ✅ für Produktion |
| C Separates Modul | groß | klein | Build | später |
| D Quarkus-Profil | klein–mittel | mittel | Build | ✅ Ergänzung |

### 12.8 Konfigurationsmatrix

| Property | Memory | SQLite | Postgres |
|---|---|---|---|
| `engine.storage` | `memory` | `sqlite` | `postgres` |
| `engine.db.kind` | – (ignoriert) | `sqlite` | `postgresql` |
| `engine.recovery.enabled` | `false` | `true` | `true` |
| `quarkus.datasource.*` | nicht nötig | SQLite-URL | Neon/Postgres |
| `quarkus.flyway.enabled` | `false` | `true` | `true` |
| `engine.memory.max-bytes` | gesetzt | – | – |
| `engine.memory.max-sessions` | gesetzt | – | – |
| `engine.memory.max-tool-output-bytes` | gesetzt | – | – |
| `engine.memory.snapshots` | `false` (Default) | `true` | `true` |

**Startup-Guard:**

```java
if (kind == MEMORY && dataSourcePresent) {
    LOG.warn("engine.storage=memory ignoriert die konfigurierte DataSource");
}
if (kind == MEMORY && recoveryEnabled) {
    throw new IllegalStateException("Recovery ist im Memory-Modus nicht verfügbar");
}
```

### 12.9 Tests und Contract-Tests (TCK)

**Ziel:** Dieselbe Semantik für alle Adapter.

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

**Test-Matrix:**

| Testart | Memory | SQLite | Postgres |
|---|---|---|---|
| Unit/Contract | ✅ | ✅ | ✅ |
| Quarkus-Integration | `@TestProfile(MemoryProfile)` | SQLite-Profil | Postgres/Testcontainers |
| E2E (Prompt → Tools) | ✅ (Fake-Provider) | ✅ | ✅ |
| Restart/Recovery | ❌ (nicht definiert) | ✅ | ✅ |
| Last/Eviction | ✅ | – | – |

**CI:** Matrix-Job pro Storage-Kind; derselbe Testcode, andere Factory.

### 12.10 Migrationsschritte (konkret)

1. **TCK einführen** (nur Tests, keine Produktionsänderung).
2. **`StorageKind`/`Stores`/`StorageFactory`** im `engine`-Modul anlegen.
3. **`RuntimeState` + `MemoryBudget`** implementieren.
4. **Memory-Adapter** portweise implementieren, TCK grün schalten.
5. **`Engine`-Wiring** von `if (sqlite)` auf `StorageFactory` umstellen.
6. **`DatabaseMigration`** per Profil/Config deaktivierbar machen.
7. **`application.properties`** um `engine.storage` und Memory-Limits
   erweitern.
8. **Startup-Guard** und Health-Ausgabe (`storage: memory`) ergänzen.
9. **Maven-Profil `storage-memory`** (optional, für lean Builds).
10. **Lasttests + Eviction-Tests**.
11. **Doku** (`InMemory.md`, `Storage-alternative.md`) aktualisieren.

### 12.11 Risiken und Gegenmaßnahmen

| Risiko | Wirkung | Gegenmaßnahme |
|---|---|---|
| Unbounded Growth | OOM | `MemoryBudget` + Eviction + Backpressure |
| `ConcurrentHashMap`-Race | inkonsistente Reads | Per-Session-Lock + immutable Snapshots |
| Blocking unter Lock | Pinning/Deadlock | I/O außerhalb des Locks (X-4) |
| Quarkus-Datasource startet trotzdem | Startup-Fehler | Profil ohne DB-Extensions / Config-Guard |
| Native-Image-Größe | groß | Maven-Profil oder separates Modul |
| Recovery versehentlich aktiv | Inkonsistenz | Startup-Guard, `engine.recovery.enabled=false` |
| Externe Side-Effects doppelt | Datenfehler | `ToolResultCache` + Idempotency-Key |
| Eviction laufender Sessions | Datenverlust | Eviction nur bei `IDLE`/`FINISHED` |

## 13. Beispiel: eine Session im Speicher

```java
// Pseudocode
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

// Alles bleibt im Heap. Kein Restart. Kein DB.
```

## 14. Vergleich mit den bestehenden Modi

| Aspekt | Memory | SQLite | PostgreSQL |
|---|---|---|---|
| Latenz | minimal | niedrig | Netzwerk |
| Persistenz | ❌ | ✅ (Datei) | ✅ |
| Multi-Node | ❌ | ❌ | ✅ |
| Backup | ❌ | Datei/WAL | DB-Backup |
| Betriebsaufwand | minimal | niedrig | mittel |
| Geeignet für | Tests, lokal, ephemer | single-node | multi-node |

## 15. Offene Fragen

1. Soll die Memory-Runtime ein eigenes **Profil** (`engine.storage=memory`)
   oder nur ein Test-Setup sein?
2. Wie aggressiv soll die **Eviction** beendeter Sessions sein?
3. Sollen **Snapshots** in-memory überhaupt angeboten oder standardmäßig
   abgelehnt werden?
4. Braucht es einen **Debug-Dump** bei Shutdown?
5. Soll ein **Hybrid** (Memory + optionaler FS-Spill für große Blobs)
   vorgesehen werden?

## 16. Referenzen (Code)

| Thema | Pfad |
|---|---|
| Storage-Ports | `qoc/engine/src/main/java/de/augmentia/oc/engine/core/port/store/` |
| Runtime-Ports | `qoc/engine/src/main/java/de/augmentia/oc/engine/core/port/runtime/` |
| Human-Ports | `qoc/engine/src/main/java/de/augmentia/oc/engine/core/port/human/` |
| Welt-Ports | `qoc/engine/src/main/java/de/augmentia/oc/engine/core/port/world/` |
| Coordinator | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Coordinator.java` |
| ExecutionRunner | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/ExecutionRunner.java` |
| Step | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/step/Step.java` |
| BackgroundTasks | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/BackgroundTasks.java` |
| SQLite-Adapter (Vorbild) | `qoc/engine/src/main/java/de/augmentia/oc/engine/adapter/sqlite/` |
| Engine-Wiring | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java` |
| Storage-Analyse | [`Storage-alternative.md`](./Storage-alternative.md) |
| Core-Beschreibung | [`Core.md`](./Core.md) |
| Async/Heartbeat | [`Async-use.md`](./Async-use.md) |
