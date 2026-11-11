# Multi-Memory – wählbare Speicher-Backends pro Port

> **Zweck:** Implementierungsplan für eine Speicher-Architektur, bei der jeder
> Storage-Port einzeln zwischen In-Memory, SQLite, PostgreSQL (und später
> Redis) gewählt werden kann. Ziel sind konfigurierbare Zwischenstufen
> („DB + Memory-Cache“, „Redis Hot Tier“, etc.) ohne Code-Änderung.
>
> **Status:** 2026-11-10 (Planung, nicht implementiert)
>
> **Referenzen:** [`InMemory.md`](./InMemory.md), [`Storage-alternative.md`](./Storage-alternative.md),
> [`Layering.md`](./Layering.md), [`Core.md`](./Core.md).

---

## 1. Problem

Heute hat QOC einen **globalen** Speicher-Schalter:

```properties
engine.db.kind=sqlite          # oder postgresql
```

`Engine.wire()` nutzt einen `boolean sqlite`, um zwischen `Sqlite*`- und `Sql*`-Adaptern
zu wählen. Es gibt keine In-Memory-Adapter und keine Möglichkeit, einzelne Ports
unterschiedlich zu speichern (z. B. Sessions in DB, Tool-Cache im Heap).

Das Ziel: **per-Port-Auswahl** des Speicher-Backends, sodass Zwischenstufen
wie „DB + Memory-Cache“ oder „Redis Hot Tier“ reine Konfigurationsprofile sind.

## 2. Grundprinzip

### 2.1 Ein Port, ein Backend

Jeder Storage-Port (`SessionStore`, `Journal`, `ConversationStore`, …) wird
unabhängig konfiguriert:

```properties
# Globaler Default
engine.storage.default=postgres

# Per-Port-Overrides (Zwischenstufen)
engine.storage.journal=redis
engine.storage.sessions=postgres
engine.storage.conversation=postgres
engine.storage.tool-cache=memory
engine.storage.tool-outputs=memory
engine.storage.snapshots=memory
engine.storage.claims=redis
```

### 2.2 Adapter-Auswahl über Factory

Die einzige Stelle, die den Backend-Typ kennt, ist `StorageFactory`:

```java
public final class StorageFactory {
    public static Stores create(StorageConfig config, RuntimeState memory,
                                DataSource dataSource, RedisClient redis) {
        StorageKind defaultKind = config.defaultKind();
        return new Stores(
            sessionStore(config.kindFor("sessions", defaultKind), memory, dataSource, redis),
            conversationStore(config.kindFor("conversation", defaultKind), memory, dataSource, redis),
            journal(config.kindFor("journal", defaultKind), memory, dataSource, redis),
            claimStore(config.kindFor("claims", defaultKind), memory, dataSource, redis),
            approvalStore(config.kindFor("approvals", defaultKind), memory, dataSource, redis),
            instructionStore(config.kindFor("instructions", defaultKind), memory, dataSource, redis),
            workspaceStore(config.kindFor("workspaces", defaultKind), memory, dataSource, redis),
            credentialStore(config.kindFor("credentials", defaultKind), memory, dataSource, redis),
            snapshotStore(config.kindFor("snapshots", defaultKind), memory, dataSource, redis),
            toolOutputStore(config.kindFor("tool-outputs", defaultKind), memory, dataSource, redis),
            toolResultCache(config.kindFor("tool-cache", defaultKind), memory, dataSource, redis)
        );
    }
}
```

`Engine`, `Coordinator`, `Step` und alle Runtime-Klassen kennen nur die
Port-Interfaces — nie den Backend-Typ.

## 3. Zwischenstufen als Konfigurationsprofile

Die „Stufen“ sind **keine Code-Pfade**, sondern **Konfigurationsprofile**:

| Stufe | Name | Konfiguration | Beschreibung |
|---|---|---|---|
| **0** | Full DB | alles `postgres`/`sqlite` | Aktueller Zustand, Restart-fähig |
| **1** | DB + Memory-Cache | `tool-cache=memory`, `tool-outputs=memory`, `snapshots=memory` | Heiße Daten im Heap, Sessions durabel |
| **2** | Redis Hot Tier | `journal=redis`, `sessions=redis`, `claims=redis`, Rest `postgres` | Schnelle Writes, durable History |
| **3** | Redis + Memory | `sessions=redis`, `journal=redis`, `tool-*=memory`, `conversation=postgres` | Redis für Hot, DB für Cold |
| **4** | Full Memory | alles `memory` | Kein DB, kein Restart (wie InMemory.md) |

## 4. Package-Struktur

```
engine/
├─ core/port/store/          # Ports (unverändert)
├─ adapter/
│  ├─ sql/                   # PostgreSQL (existiert)
│  ├─ sqlite/                # SQLite (existiert)
│  ├─ memory/                # In-Memory (neu)
│  │  ├─ RuntimeState.java
│  │  ├─ MemoryBudget.java
│  │  ├─ MemoryStores.java
│  │  ├─ InMemorySessionStore.java
│  │  ├─ InMemoryConversationStore.java
│  │  ├─ InMemoryJournal.java
│  │  ├─ InMemoryClaimStore.java
│  │  ├─ InMemoryApprovalStore.java
│  │  ├─ InMemoryInstructionStore.java
│  │  ├─ InMemorySessionWorkspaceStore.java
│  │  ├─ InMemoryCredentialStore.java
│  │  ├─ InMemorySnapshotStore.java
│  │  ├─ InMemoryToolOutputStore.java
│  │  ├─ InMemoryToolResultCache.java
│  │  ├─ InMemoryApprovalSource.java
│  │  ├─ InMemoryQuestionSource.java
│  │  ├─ StaticPolicySource.java
│  │  ├─ InMemoryNotificationChannel.java
│  │  ├─ InProcessEventBus.java
│  │  ├─ DirectSessionWake.java
│  │  ├─ SystemClock.java
│  │  └─ MemoryLimitExceeded.java
│  ├─ redis/                 # Redis (später, optional)
│  ├─ tiered/                # Hybrid (später, optional)
│  ├─ StorageKind.java       # MEMORY | SQLITE | POSTGRES | REDIS
│  ├─ Stores.java            # Record aller Ports
│  ├─ StorageConfig.java      # Per-Port-Konfiguration
│  └─ StorageFactory.java    # einziger Switch
└─ runtime/...               # unverändert (Single Source)
```

## 5. Implementierungsplan

### Phase 0: Refactoring (keine neue Funktionalität)

1. **`StorageKind` + `Stores` + `StorageConfig`** in `engine/adapter/` anlegen.
2. **`SqlStores`** und **`SqliteStores`** als Factory-Methoden (bündeln die
   bestehenden Adapter).
3. **`Engine.wire()`** von `if (sqlite)` auf `StorageFactory.create(config, …)`
   umstellen. Die `sqlite`-Boolean-Kette verschwindet.
4. **`StorageConfig`** mit `defaultKind` und per-Port-Overrides
   (`engine.storage.<port>`).
5. Tests: bestehende Tests müssen weiterhin grün sein (reiner Refactor).

### Phase 1: In-Memory-Adapter

6. **`engine/adapter/memory/`** anlegen.
7. **`RuntimeState` + `MemoryBudget`** implementieren.
8. **Memory-Adapter** portweise implementieren.
9. **TCK** (Contract-Tests) für alle Ports.
10. **Startup-Guard**: Memory-Modus deaktiviert `DatabaseMigration` und `Recovery`.
11. **`engine.storage.default=memory`** als Konfiguration.

### Phase 2: Redis-Adapter (später, bei Bedarf)

12. Dependency `quarkus-redis-client` (optional).
13. **`engine/adapter/redis/`** mit `RedisSessionStore`, `RedisJournal`,
    `RedisClaimStore`, `RedisToolResultCache`, `RedisToolOutputStore`.
14. TCK für Redis-Adapter.
15. Konfiguration: `engine.storage.<port>=redis`, `engine.redis.url=...`.

### Phase 3: Tiered-Adapter (optional, bei Bedarf)

16. **`engine/adapter/tiered/`** mit `TieredSessionStore`, `TieredJournal`.
17. Nur wenn Stufe 3 (Redis + Memory + DB) benötigt wird.

### Phase 4: Build-Optionen

18. **Maven-Profil `storage-memory`** (lean Build ohne DB-Extensions).
19. **Maven-Profil `storage-redis`** (mit Redis-Client).
20. Native-Image-Reflect-Config für neue Adapter.

## 6. Redis: brauch ich das?

**Nein, nicht jetzt.** DB + Memory deckt alles ab, was QOC heute braucht.

### Wann Redis wirklich nötig wäre

| Szenario | Warum Redis? | Wann relevant? |
|---|---|---|
| Multi-Node (2+ Instanzen) | Shared Session-State, distributed Locks | Horizontal skalieren |
| Cross-Instance ClaimStore | `SET NX EX` statt DB-Lock | Mehrere Prozesse claimen gleichzeitig |
| Cross-Instance Session-Wake | Pub/Sub statt Polling | Subagent-Ergebnisse instanzübergreifend |
| Sub-ms Journal-Latenz | Redis als Hot-Path | Journal-Write wird Bottleneck |

**Für QOC heute (single-node, GCP VM, lokal):** Keines dieser Szenarien.

### Aufwand Redis später nachzuziehen

**1–2 Wochen**, weil die Port-Architektur es ermöglicht:

1. Neues Package `engine/adapter/redis/` mit Adaptern für die Ports, die Redis
   nutzen sollen.
2. Neuer `StorageKind.REDIS`.
3. Redis-Client-Dependency (optional, nur bei Redis-Config).
4. Konfiguration `engine.redis.url`, `engine.storage.<port>=redis`.
5. TCK-Tests.

**Was sich nicht ändert:** `Engine`, `Coordinator`, `Step`, `SessionService`,
`StorageFactory`, `Stores`, bestehende Adapter.

Redis ist also **kein jetzt-oder-nie-Entscheidung**. Die per-Port-Auswahl macht
es zu einer reinen Adapter-Zusatzarbeit, die jederzeit nachgezogen werden kann.

## 7. Konfigurationsmatrix

| Property | Memory | SQLite | Postgres | Redis |
|---|---|---|---|---|
| `engine.storage.default` | `memory` | `sqlite` | `postgres` | `redis` |
| `engine.db.kind` | – | `sqlite` | `postgresql` | – |
| `engine.recovery.enabled` | `false` | `true` | `true` | `true` |
| `quarkus.datasource.*` | nicht nötig | SQLite-URL | Postgres-URL | – |
| `quarkus.flyway.enabled` | `false` | `true` | `true` | `false` |
| `engine.memory.max-bytes` | gesetzt | – | – | – |
| `engine.memory.max-sessions` | gesetzt | – | – | – |
| `engine.redis.url` | – | – | – | gesetzt |

## 8. Risiken und Gegenmaßnahmen

| Risiko | Wirkung | Gegenmaßnahme |
|---|---|---|
| Unbounded Growth (Memory) | OOM | `MemoryBudget` + Eviction + Backpressure |
| `ConcurrentHashMap`-Race | inkonsistente Reads | Per-Session-Lock + immutable Snapshots |
| Blocking unter Lock | Pinning/Deadlock | I/O außerhalb des Locks |
| Quarkus-Datasource startet trotzdem | Startup-Fehler | Profil ohne DB-Extensions / Config-Guard |
| Native-Image-Größe | groß | Maven-Profil oder separates Modul |
| Recovery versehentlich aktiv (Memory) | Inkonsistenz | Startup-Guard, `engine.recovery.enabled=false` |
| Externe Side-Effects doppelt | Datenfehler | `ToolResultCache` + Idempotency-Key |
| Eviction laufender Sessions | Datenverlust | Eviction nur bei `IDLE`/`FINISHED` |

## 9. Referenzen

| Thema | Pfad |
|---|---|
| Storage-Ports | `engine/core/port/store/` |
| Runtime-Ports | `engine/core/port/runtime/` |
| Human-Ports | `engine/core/port/human/` |
| Welt-Ports | `engine/core/port/world/` |
| SQLite-Adapter (Vorbild) | `engine/adapter/sqlite/` |
| SQL-Adapter (Vorbild) | `engine/adapter/sql/` |
| Engine-Wiring | `quarkus/http/Engine.java` (Zeile 498–560) |
| In-Memory-Beschreibung | [`InMemory.md`](./InMemory.md) |
| Storage-Analyse | [`Storage-alternative.md`](./Storage-alternative.md) |
| Core-Beschreibung | [`Core.md`](./Core.md) |
| Async/Heartbeat | [`Async-use.md`](./Async-use.md) |
| Layering-Entscheidung | [`Layering.md`](./Layering.md) |
| Task-Orchestrierung | [`Task-Orchestration.md`](./Task-Orchestration.md) |
| Test-Matrix | [`Test.md`](./Test.md) |
