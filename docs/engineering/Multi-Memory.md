# Multi-Memory – selectable storage backends per port

> **Purpose:** Implementation plan for a storage architecture where each
> storage port can individually choose between In-Memory, SQLite, PostgreSQL (and later
> Redis). The goal is configurable intermediate stages> ("DB + Memory-Cache", "Redis Hot Tier", etc.) without code changes.
>
> **Status:** 2026-11-10 (Planning, not implemented)
>
> **References:** [`InMemory.md`](./InMemory.md), [`Storage-alternative.md`](./Storage-alternative.md),
> [`Layering.md`](./Layering.md), [`Core.md`](./Core.md).

---

## 1. Problem

Today QOC has a **global** storage switch:

```properties
engine.db.kind=sqlite          # oder postgresql
```

`Engine.wire()` uses a `boolean sqlite` to choose between `Sqlite*`- and
`Sql*`-adapters. There are no In-Memory adapters and no way to store
individual ports differently (e.g. Sessions in DB, Tool-Cache in heap).

Goal: **per-port selection** of the storage backend, so that intermediate
stages like "DB + Memory-Cache" or "Redis Hot Tier" are pure configuration
profiles.

## 2. Core principle

### 2.1 One port, one backend

Each storage port (`SessionStore`, `Journal`, `ConversationStore`, …) is
configured independently:

```properties
# Global default
engine.storage.default=postgres

# Per-port overrides (intermediate stages)
engine.storage.journal=redis
engine.storage.sessions=postgres
engine.storage.conversation=postgres
engine.storage.tool-cache=memory
engine.storage.tool-outputs=memory
engine.storage.snapshots=memory
engine.storage.claims=redis
```

### 2.2 Adapter selection via factory

The only place that knows the backend type is `StorageFactory`:

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

`Engine`, `Coordinator`, `Step` and all runtime classes only know the
port interfaces — never the backend type.

## 3. Intermediate stages as configuration profiles

The "stages" are **not code paths**, but **configuration profiles**:

| Stage | Name | Configuration | Description |
|---|---|---|---|
| **0** | Full DB | everything `postgres`/`sqlite` | Current state, restart-capable |
| **1** | DB + Memory-Cache | `tool-cache=memory`, `tool-outputs=memory`, `snapshots=memory` | Hot data in heap, sessions durable |
| **2** | Redis Hot Tier | `journal=redis`, `sessions=redis`, `claims=redis`, rest `postgres` | Fast writes, durable history |
| **3** | Redis + Memory | `sessions=redis`, `journal=redis`, `tool-*=memory`, `conversation=postgres` | Redis for hot, DB for cold |
| **4** | Full Memory | everything `memory` | No DB, no restart (like InMemory.md) |

## 4. Package structure

```
engine/
├─ core/port/store/          # Ports (unchanged)
├─ adapter/
│  ├─ sql/                   # PostgreSQL (exists)
│  ├─ sqlite/                # SQLite (exists)
│  ├─ memory/                # In-Memory (new)
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
│  ├─ redis/                 # Redis (later, optional)
│  ├─ tiered/                # Hybrid (later, optional)
│  ├─ StorageKind.java       # MEMORY | SQLITE | POSTGRES | REDIS
│  ├─ Stores.java            # Record of all ports
│  ├─ StorageConfig.java     # Per-port configuration
│  └─ StorageFactory.java    # only switch
└─ runtime/...               # unchanged (Single Source)
```

## 5. Implementation plan

### Phase 0: Refactoring (no new functionality)

1. Create **`StorageKind` + `Stores` + `StorageConfig`** in `engine/adapter/`.
2. **`SqlStores`** and **`SqliteStores`** as factory methods (bundle the
   existing adapters).
3. Switch **`Engine.wire()`** from `if (sqlite)` to `StorageFactory.create(config, …)`.
   The `sqlite`-boolean chain disappears.
4. **`StorageConfig`** with `defaultKind` and per-port overrides
   (`engine.storage.<port>`).
5. Tests: existing tests must still be green (pure refactor).

### Phase 1: In-Memory adapters

6. Create **`engine/adapter/memory/`**.
7. Implement **`RuntimeState` + `MemoryBudget`**.
8. Implement memory adapters port by port.
9. **TCK** (contract tests) for all ports.
10. **Startup guard**: Memory mode disables `DatabaseMigration` and `Recovery`.
11. **`engine.storage.default=memory`** as configuration.

### Phase 2: Redis adapter (later, on demand)

12. Dependency `quarkus-redis-client` (optional).
13. **`engine/adapter/redis/`** with `RedisSessionStore`, `RedisJournal`,
    `RedisClaimStore`, `RedisToolResultCache`, `RedisToolOutputStore`.
14. TCK for Redis adapters.
15. Configuration: `engine.storage.<port>=redis`, `engine.redis.url=...`.

### Phase 3: Tiered adapter (optional, on demand)

16. **`engine/adapter/tiered/`** with `TieredSessionStore`, `TieredJournal`.
17. Only if stage 3 (Redis + Memory + DB) is needed.

### Phase 4: Build options

18. **Maven profile `storage-memory`** (lean build without DB extensions).
19. **Maven profile `storage-redis`** (with Redis client).
20. Native-Image reflect config for new adapters.

## 6. Redis: do I need it?

**No, not now.** DB + Memory covers everything QOC needs today.

### When Redis would really be needed

| Scenario | Why Redis? | When relevant? |
|---|---|---|
| Multi-Node (2+ instances) | Shared session state, distributed locks | Horizontal scaling |
| Cross-instance ClaimStore | `SET NX EX` instead of DB lock | Multiple processes claim simultaneously |
| Cross-instance Session-Wake | Pub/Sub instead of polling | Subagent results across instances |
| Sub-ms journal latency | Redis as hot path | Journal write becomes bottleneck |

**For QOC today (single-node, GCP VM, local):** None of these scenarios.

### Effort to add Redis later

**1–2 weeks**, because the port architecture makes it possible:

1. New package `engine/adapter/redis/` with adapters for the ports that should
   use Redis.
2. New `StorageKind.REDIS`.
3. Redis client dependency (optional, only with Redis config).
4. Configuration `engine.redis.url`, `engine.storage.<port>=redis`.
5. TCK tests.

**What does not change:** `Engine`, `Coordinator`, `Step`, `SessionService`,
`StorageFactory`, `Stores`, existing adapters.

Redis is therefore **not a now-or-never decision**. The per-port selection
makes it pure adapter add-on work that can be picked up at any time.

## 7. Configuration matrix

| Property | Memory | SQLite | Postgres | Redis |
|---|---|---|---|---|
| `engine.storage.default` | `memory` | `sqlite` | `postgres` | `redis` |
| `engine.db.kind` | – | `sqlite` | `postgresql` | – |
| `engine.recovery.enabled` | `false` | `true` | `true` | `true` |
| `quarkus.datasource.*` | not needed | SQLite-URL | Postgres-URL | – |
| `quarkus.flyway.enabled` | `false` | `true` | `true` | `false` |
| `engine.memory.max-bytes` | set | – | – | – |
| `engine.memory.max-sessions` | set | – | – | – |
| `engine.redis.url` | – | – | – | set |

## 8. Risks and countermeasures

| Risk | Impact | Countermeasure |
|---|---|---|
| Unbounded Growth (Memory) | OOM | `MemoryBudget` + Eviction + Backpressure |
| `ConcurrentHashMap`-race | inconsistent reads | Per-session lock + immutable snapshots |
| Blocking under lock | Pinning/Deadlock | I/O outside the lock |
| Quarkus datasource starts anyway | Startup error | Profile without DB extensions / config guard |
| Native-Image size | large | Maven profile or separate module |
| Recovery accidentally active (Memory) | Inconsistency | Startup guard, `engine.recovery.enabled=false` |
| External side effects duplicated | Data errors | `ToolResultCache` + Idempotency-Key |
| Eviction of running sessions | Data loss | Eviction only for `IDLE`/`FINISHED` |

## 9. References

| Topic | Path |
|---|---|
| Storage ports | `engine/core/port/store/` |
| Runtime ports | `engine/core/port/runtime/` |
| Human ports | `engine/core/port/human/` |
| World ports | `engine/core/port/world/` |
| SQLite adapter (template) | `engine/adapter/sqlite/` |
| SQL adapter (template) | `engine/adapter/sql/` |
| Engine wiring | `quarkus/http/Engine.java` (lines 498–560) |
| In-Memory description | [`InMemory.md`](./InMemory.md) |
| Storage analysis | [`Storage-alternative.md`](./Storage-alternative.md) |
| Core description | [`Core.md`](./Core.md) |
| Async/Heartbeat | [`Async-use.md`](./Async-use.md) |
| Layering decision | [`Layering.md`](./Layering.md) |
| Task orchestration | [`Task-Orchestration.md`](./Task-Orchestration.md) |
| Test matrix | [`Test.md`](./Test.md) |
