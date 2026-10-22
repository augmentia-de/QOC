# Java package structure

> **Purpose:** The physical realisation of the [module plan](./module-plan.md) as
> Java packages under `de.augmentia.oc.engine` – including visibility rules,
> import rules, and the packages that **must not** be imported.
>
> **Option B (revised):** four top-level areas (`core`, `runtime`, `http`,
> `adapter`), each with **sub-packages** per logical module. This keeps the
> number of top-level areas small (fewer "core modules") while preserving
> per-module visibility and testability. The stage order (S0–S8) is
> documented in `package-info.java` and reviewed; cross-cutting rules (§7)
> remain mechanically checked.
>
> **Principle:** The module plan describes **logical** modules. A sub-package
> may bundle one logical module (or a conscious grouping). It may never skip a
> stage in its *imports*, even when the areas are merged.
>
> **Binding:** This file is the **leading** build form.
> `session-engine/Java-Impl.md` §4 maps its earlier module sketch onto this.
>
> **Status:** 2026-10-22

## 1. Five principles

| # | Principle | Verifiable by |
|---|---|---|
| 1 | **One sub-package = one logical module** (or a conscious grouping). | Package list in §4 |
| 2 | **Package name = role**, not stage number. | `engine.tool.fs`, not `engine.s4.tool` |
| 3 | **Only the interface is public**; implementation is package-private. | `*Impl` may not be imported anywhere |
| 4 | **Adapters live in `engine.adapter.*`** and are used only by `wiring`. | ArchUnit rule §7 |
| 5 | **No JPMS** – layering is enforced by ArchUnit (cross-cutting rules). | Decision §10 |

## 2. Root and naming scheme

```
de.augmentia.oc.engine.<area>.<module>
```

| Part | Meaning | Example |
|---|---|---|
| `de.augmentia.oc` | organisation + product | – |
| `engine` | the runtime (everything this spec describes) | – |
| `<area>` | top-level area (`core`, `runtime`, `http`, `adapter`) | – |
| `<module>` | logical module within the area | `model`, `fs`, `client` |

**Flat areas** are allowed when an area has only one module: `engine.http`,
`engine.adapter.sql` (still under `adapter`).

**No sub-package** contains more than ~15 types. If one grows beyond that, it
is either a missing module or a missing split.

## 3. The tree (Option B, revised)

```
de.augmentia.oc.engine
│
├── core                    S0–S2
│   ├── model               S0   identifiers, messages, event payloads, money, tokens
│   ├── error               S0   Defect · Aborted · StoreFailure
│   ├── json                S0   encode/decode against declarative schemata
│   ├── pattern             S0   Wildcard
│   ├── shell
│   │   └── scan            S0   Scanner, ScanResult, ScannedCommand
│   ├── port
│   │   ├── store           S0   Journal · Conversation · SessionReader · ClaimStore · …
│   │   ├── world           S0   FileSystem · ProcessRunner · HttpTransport
│   │   ├── human           S0   ApprovalSource · PolicySource
│   │   └── runtime         S0   Clock · SessionWake
│   ├── event               S1   Payload upgrade
│   ├── contract
│   │   ├── tool            S1   ToolDefinition · ToolResult · … · ToolSnapshot
│   │   ├── permission      S1   Rule · Effect · Request · Reply · …
│   │   └── provider        S1   ModelRequest · ModelEvent · … · ProviderAdapter
│   └── eval                S2   permission.rule · provider.classify/budget/affinity
│                                · tool.output · instruction
│
├── runtime                 S3–S7
│   ├── tool
│   │   ├── registry        S3   ToolRegistry · Registration
│   │   ├── runtime         S4   ToolRuntime (implements ToolExecutor)
│   │   ├── fs              S4   Read · Write · Edit · Patch · Glob · Grep
│   │   ├── shell           S4   ShellTool
│   │   ├── skill           S4   SkillTool
│   │   └── mcp             S4   McpTool · McpRegistrar
│   ├── permission
│   │   └── engine          S3   PermissionEngine · PendingApprovals
│   ├── provider
│   │   └── client          S3   HttpProviderAdapter · SseReader · EventTranslator
│   ├── skill               S3   SkillScanner · SkillParser · SkillCatalog
│   ├── credential          S3   CredentialResolver · CredentialService
│   ├── context             S3   ContextBuilder · Prepared
│   ├── step                S5   Step · StepResult · Publisher
│   ├── compaction          S5   Compaction
│   ├── session
│   │   ├── core            S6   SessionService · Admission
│   │   ├── delivery        S6   Delivery
│   │   └── revert          S6   Revert
│   └── execution           S7   SessionExecution · Coordinator · Recovery
│
├── http                    S8      HttpApi · SseEndpoint · Engine · EngineConfig
│
└── adapter                 –       implementations (outside the stages)
    ├── sql                 –       SqlJournal · SqlConversation · … · Migrations
    ├── fs                  –       NioFileSystem · GitSnapshotStore
    ├── process             –       JdkProcessRunner · PtyProcess
    ├── net                 –       JdkHttpTransport · JdkChannel
    └── time                –       SystemClock
```

> **`adapter` sits inside `engine` but outside the stages.** Intentional: the
> package root stays one unit, and `wiring` (in `http`) can wire everything
> without any domain package seeing an adapter.

## 4. Package by package

### `engine.core` (S0–S2)

| Sub-package | Logical modules | Public types (excerpt) | May import |
|---|---|---|---|
| `core.model` | `model` | `SessionId`, `ProjectId`, `MessageId`, `CallId`, `InboxId`, `PermissionRequestId`, `SnapshotId`, `AgentId`, `ProviderId`, `ModelId`, `ToolName`, `Seq`, `Money`, `TokenCount`, `Usage`, `ModelRef`, `Message`, `Content`, `ToolCard`, `SessionInfo`, `InboxEntry`, `Claim`, `SavedApproval`, `Credential`, `Connection`, `SkillSummary`, `InstructionState`, `InstructionEntry`, `InstructionBlob`, `FileDiff`, `EventDraft`, `StoredEvent`, `Payload` (sealed) | `java.*` |
| `core.error` | `error` | `Defect`, `Aborted`, `StoreFailure` | `java.*` |
| `core.json` | `json` | `Json`, `Schema`, `DecodeError` | `model`, `error` |
| `core.pattern` | `pattern` | `Wildcard` | `java.*` |
| `core.shell.scan` | `shell.scan` | `Scanner`, `ScanResult`, `ScannedCommand` | `model` |
| `core.port.store` | `port.store` | `Journal`, `Conversation`, `SessionReader`, `ClaimStore`, `ApprovalStore`, `CredentialStore`, `InstructionStore`, `ToolOutputStore`, `SnapshotStore` | `model`, `error`, `java.*` |
| `core.port.world` | `port.world` | `FileSystem`, `ProcessRunner`, `HttpTransport` | `model`, `java.*` |
| `core.port.human` | `port.human` | `ApprovalSource`, `PolicySource` | `model` |
| `core.port.runtime` | `port.runtime` | `Clock`, `SessionWake` | `model` |
| `core.event` | `event` | `EventCodec`, `PayloadRegistry` | `model`, `json`, `error` |
| `core.contract.tool` | `tool.contract` | `ToolDefinition`, `ToolResult`, `ToolContent`, `ToolFailure`, `ToolExecutor`, `ToolGate`, `ToolCall`, `ToolContext`, `ToolSnapshot`, `Hooks` | `model`, `error`, `json` |
| `core.contract.permission` | `permission.contract` | `Rule`, `Effect`, `PermissionRequest`, `Reply`, `PermissionGate`, `PermissionHook`, `PermissionDenied` | `model`, `error` |
| `core.contract.provider` | `provider.contract` | `ModelRequest`, `ModelEvent` (sealed), `ProviderFailure` (sealed), `FinishReason`, `OutcomePayload`, `ProviderAdapter` | `model`, `error`, `json` |
| `core.eval` | `permission.rule`, `provider.classify`, `provider.budget`, `provider.affinity`, `tool.output`, `instruction` | `model`, `json`, `pattern`, `port.*`, `contract.*` |

> **`engine.core` is the shared kernel.** It contains **data only** plus pure
> helpers and ports. No domain orchestration lives here, so the whole engine
> can be built and tested against fakes from this package alone.

> **`Payload` is `sealed`** and lives in `core.model`, not in a separate event
> package. Reason: `EventDraft` carries a payload, and `Journal` takes
> `EventDraft` – if `Payload` were in a later package, the port would have to
> reach upward.

> **`ToolDefinition` carries the execution function** – therefore S1 and not
> `core.model`. A definition without behaviour would be a data sheet, not a tool.

### `engine.runtime` (S3–S7)

| Sub-package | Logical modules | Public types (excerpt) | May import |
|---|---|---|---|
| `runtime.tool.registry` | `tool.registry` | `ToolRegistry`, `Registration` | `core`, `runtime.permission.engine` (port.human) |
| `runtime.tool.runtime` | `tool.runtime` | `ToolRuntime` | `core`, `runtime.tool.output`, `core.contract.permission`, `error` |
| `runtime.tool.fs` | `tool.fs` | `ReadTool`, `WriteTool`, `EditTool`, `PatchTool`, `GlobTool`, `GrepTool`, `FileAccess` | `core`, `core.contract.permission`, `runtime.tool.output`, `core.pattern`, `error` |
| `runtime.tool.shell` | `tool.shell` | `ShellTool` | `core`, `core.shell.scan`, `core.contract.permission`, `runtime.tool.output` |
| `runtime.tool.skill` | `tool.skill` | `SkillTool` | `core`, `core.contract.permission`, `runtime.skill` |
| `runtime.tool.mcp` | `tool.mcp` | `McpTool`, `McpRegistrar`, `McpContentMapper` | `core`, `runtime.tool.registry`, `core.contract.permission`, `core.port.world`, `core.port.store`, `core.json` |
| `runtime.permission.engine` | `permission.engine` | `PermissionEngine`, `PendingApprovals` | `core`, `core.contract.permission`, `core.pattern`, `core.port.*` |
| `runtime.provider.client` | `provider.client` | `HttpProviderAdapter`, `SseReader`, `EventTranslator`, `CatalogSource` | `core`, `core.contract.provider`, `core.eval` (classify/budget/affinity), `core.port.world`, `core.json` |
| `runtime.skill` | `skill` | `SkillScanner`, `SkillParser`, `SkillCatalog` | `core`, `core.port.world`, `core.contract.permission`, `core.json` |
| `runtime.credential` | `credential` | `CredentialResolver`, `CredentialService` | `core`, `core.port.store`, `core.port.world`, `core.port.runtime`, `core.contract.provider`, `core.json` |
| `runtime.context` | `context` | `ContextBuilder`, `Prepared` | `core`, `runtime.tool.*`, `core.eval` (instruction), `runtime.skill` |
| `runtime.step` | `step` | `Step`, `StepResult`, `Publisher` | `core`, `runtime.context`, `core.contract.provider`, `core.contract.tool`, `core.port.store`, `error` |
| `runtime.compaction` | `compaction` | `Compaction` | `core`, `runtime.context`, `core.contract.provider`, `core.port.store`, `core.eval` (instruction), `core.event` |
| `runtime.session.core` | `session.core` | `SessionService`, `Admission` | `core`, `core.port.store`, `core.port.runtime`, `core.event`, `error` |
| `runtime.session.delivery` | `session.delivery` | `Delivery` | `core`, `core.port.store`, `core.event` |
| `runtime.session.revert` | `session.revert` | `Revert` | `core`, `core.port.store`, `core.event` |
| `runtime.execution` | `execution` | `SessionExecution`, `Coordinator`, `Recovery` | `core`, `runtime.step`, `runtime.session.delivery`, `core.port.store`, `core.port.runtime`, `core.port.world`, `core.event` |

> **`engine.runtime` is the domain layer.** It contains orchestration, tools,
> permissions, provider client, context, step, session and execution –
> everything that is neither pure data nor HTTP.

### `engine.http` (S8)

| Sub-package | Logical modules | Public types | May import |
|---|---|---|---|
| `http` (flat) | `http`, `wiring` | `HttpApi`, `SseEndpoint`, `Engine`, `EngineConfig` | everything; contract: `betrieb/01-http-streaming.md` |

### `engine.adapter.*`

| Package | Implements | May be used by |
|---|---|---|
| `engine.adapter.sql` | all `core.port.store` | `wiring` (in `http`) only |
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
package de.augmentia.oc.engine.permission.engine;
```

> **The comment is not documentation but a review template.** The stage
> note is there so a reviewer can read it next to the ArchUnit test – and so
> that a new package without a stage note stands out.

## 6. Ports and their packages

| Port | Package | Signature speaks |
|---|---|---|
| `Journal` | `core.port.store` | `EventDraft`, `StoredEvent` |
| `Conversation` | `core.port.store` | `Message` |
| `SessionReader` | `core.port.store` | `SessionInfo`, `InboxEntry` |
| `ClaimStore` | `core.port.store` | `Claim` |
| `ApprovalStore` | `core.port.store` | `SavedApproval` |
| `CredentialStore` | `core.port.store` | `Credential` |
| `InstructionStore` | `core.port.store` | `InstructionState`, `InstructionEntry`, `InstructionBlob` |
| `ToolOutputStore` | `core.port.store` | `Path` |
| `SnapshotStore` | `core.port.store` | `FileDiff` |
| `FileSystem` | `core.port.world` | `Path`, `Bytes` |
| `ProcessRunner` | `core.port.world` | `ProcessSpec`, `Exit` |
| `HttpTransport` | `core.port.world` | `HttpRequest`, `ResponseStream` |
| `ApprovalSource` | `core.port.human` | `ApprovalResult` |
| `PolicySource` | `core.port.human` | `PolicyStatement` |
| `Clock` | `core.port.runtime` | `Instant`, `Duration` |
| `SessionWake` | `core.port.runtime` | `SessionId` |

> **All ports live in `core`**, because their signatures touch only `core.model`
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

    // core may not import runtime or http.
    @ArchTest
    static final ArchRule core_kennt_kein_runtime =
            noClasses().that().resideInAPackage("de.augmentia.oc.engine.core..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "de.augmentia.oc.engine.runtime..",
                            "de.augmentia.oc.engine.http..");

    // runtime may not import http.
    @ArchTest
    static final ArchRule runtime_kennt_kein_http =
            noClasses().that().resideInAPackage("de.augmentia.oc.engine.runtime..")
                    .should().dependOnClassesThat().resideInAPackage(
                            "de.augmentia.oc.engine.http..");

    // Tools (leaf classes) write no events: enforce by naming convention or
    // move tool leaves into engine.runtime.tool.leaf if the rule is to stay
    // mechanical. (Under Option B tools live inside runtime.)

    // SQL only in the SQL adapter.
    @ArchTest
    static final ArchRule sql_nur_im_adapter =
            noClasses().that().resideOutsideOfPackage("de.augmentia.oc.engine.adapter.sql..")
                    .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..");

    // HTTP only in the net adapter.
    @ArchTest
    static final ArchRule http_nur_im_adapter =
            noClasses().that().resideOutsideOfPackage("de.augmentia.oc.engine.adapter.net..")
                    .should().dependOnClassesThat().resideInAPackage("java.net.http..");

    // Time only through the Clock port.
    @ArchTest
    static final ArchRule zeit_nur_ueber_clock =
            noClasses().that().resideOutsideOfPackage("de.augmentia.oc.engine.adapter.time..")
                    .should().callMethod(System.class, "currentTimeMillis")
                    .orShould().callMethod(Instant.class, "now");

    // No implementation class is used from outside.
    @ArchTest
    static final ArchRule keine_impl_importe =
            noClasses().that().resideOutsideOfPackage("de.augmentia.oc.engine.adapter..")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Impl");

    // No cycles between top-level areas.
    @ArchTest
    static final ArchRule keine_zyklen = SlicesRuleDefinition.slices()
            .matching("de.augmentia.oc.engine.(*)..")
            .should().beFreeOfCycles();
}
```

> **Under Option B the stage order can no longer be checked at package level**,
> because `core` (S0–S2), `runtime` (S3–S7) and `http` (S8) each bundle
> several stages. The layering is therefore documented in `package-info.java`
> and reviewed manually. The cross-cutting rules above (core/runtime/http
> direction, SQL/HTTP only in adapters, time only via `Clock`, no `Impl`
> imports, no cycles) remain mechanically enforceable.

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
| `module-info.java`? | **no** | sub-packages need few `exports`/`opens`; Quarkus runs in classpath mode; JPMS cannot express stage order |
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
| **J-C** | One package per tool or per family? | Per family (`tool`) is compact; per tool is finer. | **per family** (sub-packages under `runtime.tool`) |
| **J-D** | `http` and `wiring` in the same package? | `wiring` may do everything, `http` only S0–S7. Separate is the sharper rule. | **separate** (kept as `http`) |
| **J-E** | Should ports be `sealed`? | No – ports are implemented by adapters **and** fakes; `sealed` would forbid that. | **decided (2026-10-22): no** |