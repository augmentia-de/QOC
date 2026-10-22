# Java package structure

> **Purpose:** The physical realisation of the [module plan](./module-plan.md) as
> Java packages under `de.augmentia.oc.engine` – including visibility rules,
> import rules, and the packages that **must not** be imported.
>
> **Option B (aggressive consolidation):** logical modules from the module
> plan are grouped into a small number of packages. The stage order (S0–S8)
> is preserved as a *convention* documented in `package-info.java` and in
> §5, but it is **no longer enforceable by ArchUnit at package level**,
> because several stages now share one package. Only the cross-cutting rules
> (§7) remain mechanically checked.
>
> **Principle:** The module plan describes **logical** modules. A package may
> bundle several logical modules (e.g. `values` + `message` + event payloads
> in `core`). It may never skip a stage in its *imports*, even when the
> packages are merged.
>
> **Binding:** This file is the **leading** build form.
> `session-engine/Java-Impl.md` §4 maps its earlier module sketch onto this.
>
> **Status:** 2026-10-22

## 1. Five principles

| # | Principle | Verifiable by |
|---|---|---|
| 1 | **One package = one bundle** of logical modules (conscious grouping). | Package list in §4 |
| 2 | **Package name = role**, not stage number. | `engine.tool`, not `engine.s4.tool` |
| 3 | **Only the interface is public**; implementation is package-private. | `*Impl` may not be imported anywhere |
| 4 | **Adapters live in `engine.adapter.*`** and are used only by `wiring`. | ArchUnit rule §7 |
| 5 | **No JPMS** – layering is enforced by ArchUnit (cross-cutting rules only). | Decision §10 |

> **Principle 3 is the one most often broken.** A `public class SqlJournal`
> is convenient – and turns every layer boundary into decoration. The rule is
> therefore mechanical: **no type outside `adapter` ends in `Impl`**.

## 2. Root and naming scheme

```
de.augmentia.oc.engine.<area>.<module>
```

| Part | Meaning | Example |
|---|---|---|
| `de.augmentia.oc` | organisation + product | – |
| `engine` | the runtime (everything this spec describes) | – |
| `<area>` | functional area | `tool`, `permission`, `provider`, `session` |
| `<module>` | module within the area | `fs`, `rule`, `client`, `core` |

**Flat areas** (no sub-module) are allowed when the area has only one module:
`engine.model`, `engine.step`, `engine.execution`.

**No package** contains more than ~25 types. If one grows beyond that, it is
either a missing module or a missing split.

## 3. The tree (Option B)

```
de.augmentia.oc.engine
│
├── core                    S0–S2   values, errors, JSON, patterns, ports,
│                                   event payloads, all contracts, pure evaluators
│
├── runtime                 S3–S7   registry, permission engine, provider client,
│                                   context, tools, step, compaction, session,
│                                   delivery, revert, execution
│
├── http                    S8      HTTP/SSE API + wiring
│
└── adapter                 –       implementations (outside the stages)
    ├── sql                 –       SqlJournal, SqlConversation, …, migrations
    ├── fs                  –       NioFileSystem, GitSnapshotStore
    ├── process             –       JdkProcessRunner, PtyProcess
    ├── net                 –       JdkHttpTransport, JdkChannel
    └── time                –       SystemClock
```

> **`adapter` sits inside `engine` but outside the stages.** Intentional: the
> package root stays one unit, and `wiring` can wire everything without any
> domain package seeing an adapter.

## 4. Package by package

### `engine.core` (S0–S2)

| Logical modules | Public types | May import |
|---|---|---|
| `model`, `error`, `json`, `pattern`, `shell.scan` | `SessionId`, `ProjectId`, `MessageId`, `CallId`, `InboxId`, `PermissionRequestId`, `SnapshotId`, `AgentId`, `ProviderId`, `ModelId`, `ToolName`, `Seq`, `Money`, `TokenCount`, `Usage`, `ModelRef`, `Message`, `Content`, `ToolCard`, `SessionInfo`, `InboxEntry`, `Claim`, `SavedApproval`, `Credential`, `Connection`, `SkillSummary`, `InstructionState`, `InstructionEntry`, `InstructionBlob`, `FileDiff`, `EventDraft`, `StoredEvent`, `Payload` (sealed), `Defect`, `Aborted`, `StoreFailure`, `Json`, `Schema`, `DecodeError`, `Wildcard`, `Scanner`, `ScanResult`, `ScannedCommand` | `java.*` |
| ports (`store`, `world`, `human`, `runtime`) | `Journal`, `Conversation`, `SessionReader`, `ClaimStore`, `ApprovalStore`, `CredentialStore`, `InstructionStore`, `ToolOutputStore`, `SnapshotStore`, `FileSystem`, `ProcessRunner`, `HttpTransport`, `ApprovalSource`, `PolicySource`, `Clock`, `SessionWake` | `model`, `error`, `java.*` |
| `event`, `tool.contract`, `permission.contract`, `provider.contract` | `EventCodec`, `PayloadRegistry`, `ToolDefinition`, `ToolResult`, `ToolContent`, `ToolFailure`, `ToolExecutor`, `ToolGate`, `ToolCall`, `ToolContext`, `ToolSnapshot`, `Hooks`, `Rule`, `Effect`, `PermissionRequest`, `Reply`, `PermissionGate`, `PermissionHook`, `PermissionDenied`, `ModelRequest`, `ModelEvent` (sealed), `ProviderFailure` (sealed), `FinishReason`, `OutcomePayload`, `ProviderAdapter` | `model`, `error`, `json` |
| `permission.rule`, `provider.classify`, `provider.budget`, `provider.affinity`, `tool.output`, `instruction` | `Rules`, `ErrorClassifier`, `RetryPolicy`, `OutputBudget`, `Affinity`, `Bounding`, `Instructions`, `InstructionDelta`, `Baseline` | `model`, `json`, `pattern`, `port.*`, `contract.*` |

> **`engine.core` is the shared kernel.** It contains **data only** plus pure
> helpers and ports. No domain orchestration lives here, so the whole engine
> can be built and tested against fakes from this package alone.

> **`Payload` is `sealed`** and lives in `core`, not in a separate event
> package. Reason: `EventDraft` carries a payload, and `Journal` takes
> `EventDraft` – if `Payload` were in a later package, the port would have to
> reach upward.

> **`ToolDefinition` carries the execution function** – therefore S1 and not
> `core`-data. A definition without behaviour would be a data sheet, not a tool.

### `engine.runtime` (S3–S7)

| Logical modules | Public types | May import |
|---|---|---|
| `tool.registry`, `tool.runtime`, `tool.fs`, `tool.shell`, `tool.skill`, `tool.mcp`, `tool.output` | `ToolRegistry`, `Registration`, `ToolRuntime`, `ReadTool`, `WriteTool`, `EditTool`, `PatchTool`, `GlobTool`, `GrepTool`, `FileAccess`, `ShellTool`, `SkillTool`, `McpTool`, `McpRegistrar`, `McpContentMapper`, `Bounding` | `core`, `skill`, `permission`, `provider` |
| `skill`, `credential` | `SkillScanner`, `SkillParser`, `SkillCatalog`, `CredentialResolver`, `CredentialService` | `core`, `permission.rule`, `provider.contract`, `json` |
| `permission.engine` | `PermissionEngine`, `PendingApprovals` | `core`, `permission.rule`, `port.*`, `pattern` |
| `provider.client` | `HttpProviderAdapter`, `SseReader`, `EventTranslator`, `CatalogSource` | `core`, `provider.classify`, `provider.budget`, `provider.affinity`, `port.world`, `json` |
| `context` | `ContextBuilder`, `Prepared` | `core`, `tool.*`, `instruction`, `skill` |
| `step`, `compaction` | `Step`, `StepResult`, `Publisher`, `Compaction` | `core`, `context`, `provider.contract`, `tool.contract`, `port.store`, `error` |
| `session`, `delivery`, `revert` | `SessionService`, `Admission`, `Delivery`, `Revert` | `core`, `port.store`, `port.runtime`, `event`, `error` |
| `execution` | `SessionExecution`, `Coordinator`, `Recovery` | `step`, `session`, `port.store`, `port.runtime`, `port.world`, `event` |

> **`runtime` is the domain layer.** It contains orchestration, tools, permissions,
> provider client, context, step, session and execution – everything that is
> neither pure data nor HTTP.

### `engine.http` (S8)

| Logical modules | Public types | May import |
|---|---|---|
| `http`, `wiring` | `HttpApi`, `SseEndpoint`, `Engine`, `EngineConfig` | everything; contract: `betrieb/01-http-streaming.md` |

### `engine.adapter.*`

| Package | Implements | May be used by |
|---|---|---|
| `engine.adapter.sql` | all `port.store` | `wiring` only |
| `engine.adapter.fs` | `FileSystem`, `SnapshotStore` | `wiring` only |
| `engine.adapter.process` | `ProcessRunner` | `wiring` only |
| `engine.adapter.net` | `HttpTransport` | `wiring` only |
| `engine.adapter.time` | `Clock` | `wiring` only |

## 5. Visibility and construction

### 5.1 Three visibility levels

```java
// 1. Public: the interface
public interface Journal {
    Seq append(EventDraft draft);
    List<StoredEvent> history(String aggregate, Seq after);
}

// 2. Package-private: the implementation
final class InMemoryJournal implements Journal { … }

// 3. Public: the factory – the only way to reach the implementation
public interface Journal {
    …
    static Journal inMemory() { return new InMemoryJournal(); }
}
```

> **The factory sits on the interface, not on the class.** That makes the
> implementation class a pure implementation detail – the caller sees only
> `Journal`.

### 5.2 Who may construct what

| Type | Construction | Where |
|---|---|---|
| Value types (`core`) | `new SessionId("…")`, `SessionId.of(…)` | anywhere |
| Ports (fake) | `Journal.inMemory()` | tests |
| Ports (real) | `new SqlJournal(dataSource, projections)` | `wiring` only |
| Domain services | `PermissionEngine.create(gate, rules, …)` | `wiring` |
| Tool definitions | `ReadTool.definition(fileSystem, gate, bounding)` | `wiring` / `ToolRegistry` |

> **Domain services receive their dependencies in the constructor** – never
> via a static field, never via a service locator. That is the precondition
> for building them in tests without a container.

### 5.3 `package-info.java`

Every package gets a `package-info.java` with **one** line of purpose:

```java
/**
 * Permission evaluation: last rule wins, deny before ask before allow.
 * Stages S2/S3. May only use core and pattern.
 */
package de.augmentia.oc.engine.permission;
```

> **The comment is not documentation but a review template.** The stage
> note is there so a reviewer can read it next to the ArchUnit test – and so
> that a new package without a stage note stands out.

## 6. Ports and their packages

| Port | Package | Signature speaks |
|---|---|---|
| `Journal` | `core` | `EventDraft`, `StoredEvent` |
| `Conversation` | `core` | `Message` |
| `SessionReader` | `core` | `SessionInfo`, `InboxEntry` |
| `ClaimStore` | `core` | `Claim` |
| `ApprovalStore` | `core` | `SavedApproval` |
| `CredentialStore` | `core` | `Credential` |
| `InstructionStore` | `core` | `InstructionState`, `InstructionEntry`, `InstructionBlob` |
| `ToolOutputStore` | `core` | `Path` |
| `SnapshotStore` | `core` | `FileDiff` |
| `FileSystem` | `core` | `Path`, `Bytes` |
| `ProcessRunner` | `core` | `ProcessSpec`, `Exit` |
| `HttpTransport` | `core` | `HttpRequest`, `ResponseStream` |
| `ApprovalSource` | `core` | `ApprovalResult` |
| `PolicySource` | `core` | `PolicyStatement` |
| `Clock` | `core` | `Instant`, `Duration` |
| `SessionWake` | `core` | `SessionId` |

> **All ports live in `core`**, because their signatures touch only `engine.core`
> types and `java.*`. That is not an accident – it is the reason for the shared
> kernel.

## 7. Import rules (ArchUnit)

> **Binding in the build:** how the rules are wired, which CI job runs them,
> and how exceptions with an expiry date work is described in
> `qualitaet/02-architecture-pipeline.md`.

### 7.1 Cross-cutting rules

```java
@AnalyzeClasses(packages = "de.augmentia.oc.engine")
final class ArchitectureTest {

    // runtime may not import http (direction check).
    @ArchTest
    static final ArchRule runtime_kennt_kein_http =
            noClasses().that().resideInAPackage("..engine.runtime..")
                    .should().dependOnClassesThat().resideInAPackage("..engine.http..");

    // core may not import runtime or http.
    @ArchTest
    static final ArchRule core_kennt_kein_runtime =
            noClasses().that().resideInAPackage("..engine.core..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..engine.runtime..", "..engine.http..");

    // Tools write no events: under Option B tools live inside engine.runtime,
    // so this rule can no longer be expressed by package alone. Enforce by
    // naming convention (tool leaf classes must not import Journal) or move
    // tools back into a sub-package engine.runtime.tool.

    // SQL only in the SQL adapter.
    @ArchTest
    static final ArchRule sql_nur_im_adapter =
            noClasses().that().resideOutsideOfPackage("..engine.adapter.sql..")
                    .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..");

    // HTTP only in the net adapter.
    @ArchTest
    static final ArchRule http_nur_im_adapter =
            noClasses().that().resideOutsideOfPackage("..engine.adapter.net..")
                    .should().dependOnClassesThat().resideInAPackage("java.net.http..");

    // Time only through the Clock port.
    @ArchTest
    static final ArchRule zeit_nur_ueber_clock =
            noClasses().that().resideOutsideOfPackage("..engine.adapter.time..")
                    .should().callMethod(System.class, "currentTimeMillis")
                    .orShould().callMethod(Instant.class, "now");

    // No implementation class is used from outside.
    @ArchTest
    static final ArchRule keine_impl_importe =
            noClasses().that().resideOutsideOfPackage("..engine.adapter..")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Impl");

    // No cycles between the three top-level packages.
    @ArchTest
    static final ArchRule keine_zyklen = SlicesRuleDefinition.slices()
            .matching("de.augmentia.oc.engine.(*)..")
            .should().beFreeOfCycles();
}
```

> **Under Option B the stage order can no longer be checked at package
> level**, because `core` (S0–S2), `runtime` (S3–S7) and `http` (S8) each
> bundle several stages. The layering is therefore documented in
> `package-info.java` and reviewed manually. The cross-cutting rules above
> (SQL/HTTP only in adapters, time only via `Clock`, no `Impl` imports, no
> cycles) remain mechanically enforceable.

> **The most important manual rule:** `runtime` may not import `http`, and
> `core` may not import `runtime` or `http`. That is the only direction that
> matters for testability, and it is visible in the imports of every class.

### 7.2 Rules ArchUnit cannot check

| Rule | Why not checkable | Replacement |
|---|---|---|
| "Database access only inside a transaction" | runtime behaviour | code review + integration test |
| "Tool logs before the side effect" | runtime order | test with order assertion |
| "Port is wired with a real adapter only in `wiring`" | construction, not import | review of `EngineConfig` |
| "No `catch (Exception)`" | semantics | own ArchUnit rule type or review |

> **The last point is the most important:** the three error channels survive
> only if nobody writes `catch (Exception)`. That is the one rule worth a
> dedicated check – by grep in the build if necessary.

## 8. Naming conventions

| Kind | Pattern | Example |
|---|---|---|
| Port | noun, no suffix | `Journal`, `FileSystem`, `Clock` |
| Adapter | technology + port | `SqlJournal`, `NioFileSystem`, `JdkHttpTransport` |
| Fake | `InMemory*` / `Fake*` / `Scripted*` | `InMemoryJournal`, `FakeFileSystem`, `ScriptedHttp` |
| Domain service | noun + role | `PermissionEngine`, `ContextBuilder`, `OutputBudget` |
| Exception | state, no "Exception" | `Aborted`, `Denied`, `StoreFailure` |
| Event | past tense | `InboxEnqueued`, `ToolCalled`, `StepEnded` |
| Factory | `of`, `create`, `inMemory` | `Journal.inMemory()`, `PermissionEngine.create(...)` |

> **Exceptions carry no `Exception` in their name.** `Aborted` is a state, not
> an error case – and the name should say so. `PermissionDenied` is named that
> way because "denied" is the decision.

> **`Impl` is forbidden.** A type is called `SqlJournal`, not `JournalImpl` –
> the name says **what** it is, not that it is "the implementation". The
> ArchUnit rule in §7.1 checks this.

## 9. Test packages

```
src/test/java/de/augmentia/oc/engine
├── arch/            ArchitectureTest
├── contract/        JournalContract · ConversationContract · ClaimStoreContract …
├── testing/         InMemoryJournal · FakeFileSystem · ScriptedHttp · FixedClock …
├── core/            encoding/decoding, value equality, patterns, ports
├── runtime/         permissions · provider · tools · context · step · session · execution
├── http/            HttpApi · wiring
└── integration/     SqlJournal against PostgreSQL (Testcontainers)
```

**Three rules for test packages:**

1. **Fakes are `public`** in `engine.testing` – used by several test packages.
2. **Contract tests are parameterised**, not duplicated: **one** class
   `JournalContract`, two implementations.
3. **No mocking framework.** The fake is a real implementation.

> **`engine.testing` lives in `src/test`**, not in `src/main`. A fake in
> production code is an invitation to use it.

## 10. JPMS: no

| Question | Decision | Reason |
|---|---|---|
| `module-info.java`? | **no** | 4 packages need few `exports`/`opens`; Quarkus runs in classpath mode; JPMS cannot express stage order |
| Enforce layering | **ArchUnit** (§7) | checkable in the build, no classpath rebuild |
| Visibility | **`public` + package-private** | sufficient for interface hygiene |

> **JPMS would answer the wrong question.** It checks *who* exports a package –
> not *in which direction* dependencies point. Stage order is an architecture
> rule, and ArchUnit is the right tool for it.

## 11. Import chains as examples

**`tool.fs` (a leaf) inside `runtime`:**

```java
import de.augmentia.oc.engine.core.Content;
import de.augmentia.oc.engine.core.PermissionGate;
import de.augmentia.oc.engine.core.FileSystem;
import de.augmentia.oc.engine.core.ToolDefinition;
import de.augmentia.oc.engine.core.ToolResult;
import de.augmentia.oc.engine.core.Bounding;
```

No `session`, no `provider`, no `step`, no `adapter`.

**`session` inside `runtime`:**

```java
import de.augmentia.oc.engine.core.InboxEntry;
import de.augmentia.oc.engine.core.SessionId;
import de.augmentia.oc.engine.core.Clock;
import de.augmentia.oc.engine.core.SessionWake;
import de.augmentia.oc.engine.core.Journal;
import de.augmentia.oc.engine.core.SessionReader;
```

**`http` (outer skin):**

```java
import de.augmentia.oc.engine.core.*;       // everything from core
import de.augmentia.oc.engine.runtime.*;    // everything from runtime
```

> **The third list is the proof for §7.1:** `core` knows only storage, time
> and the wake-up call. With three fakes it is fully testable.

## 12. Open decisions

| # | Question | Impact | Proposal |
|---|---|---|---|
| **J-A** | `engine.core` as one package or split by domain (`core.session`, `core.tool`)? | One package is simple, grows; splitting creates more imports. | **one package**, until it exceeds 25 types |
| **J-B** | `adapter` inside `engine` or as `de.augmentia.oc.adapter`? | Inside keeps the root together; outside makes the split visible. | **inside** (default) |
| **J-C** | One package per tool or per family? | Per family (`tool`) is compact; per tool is finer. | **per family** (merged into `runtime`) |
| **J-D** | `http` and `wiring` in the same package? | `wiring` may do everything, `http` only S0–S7. Separate is the sharper rule. | **separate** (kept as `http`) |
| **J-E** | Should ports be `sealed`? | No – ports are implemented by adapters **and** fakes; `sealed` would forbid that. | **decided (2026-10-22): no** |