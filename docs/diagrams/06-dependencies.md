# 06 – Dependencies and Architecture Rules

## Purpose

This diagram shows the allowed dependency direction of stages S0–S8 and
the rules checked by ArchUnit in the build. It makes visible why
the core stays testable and where technology (SQL, HTTP, time) belongs.

## Stage Dependencies

```mermaid
flowchart TD
  S0["S0<br/>model · port.* · json · pattern · shell.scan"]:::det
  S1["S1<br/>event · contract.*"]:::det
  S2["S2<br/>eval"]:::det
  S3["S3<br/>tool.registry · permission.engine<br/>context · skill · credential<br/>provider.client (LLM)"]:::mixed
  S4["S4<br/>tool.runtime · tool.fs · tool.shell<br/>tool.meta · tool.skill · tool.mcp<br/>tool.web · tool.subagent"]:::mixed
  S5["S5<br/>step · compaction"]:::mixed
  S6["S6<br/>session.core · delivery · revert"]:::det
  S7["S7<br/>execution · Coordinator · Recovery"]:::det
  S8["S8<br/>HttpApi · Engine · EventBus<br/>(currently in quarkus)"]:::mixed

  S0 --> S1 --> S2 --> S3 --> S4 --> S5 --> S6 --> S7 --> S8
  S5 --> S7

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef mixed fill:#fff9c4,stroke:#f9a825,stroke-width:1px,color:#111;
```

S3 is predominantly deterministic. The `provider.client` translates request and
stream deterministically; the remote LLM service is not deterministic and is
therefore marked as "mixed".

## Adapters outside the stages

```mermaid
flowchart LR
  Domain["core + runtime<br/>knows only Ports"]:::det
  Ports["core.port.*<br/>Interfaces"]:::det
  Adapters["adapter.sql / sqlite<br/>adapter.fs<br/>adapter.process<br/>adapter.net<br/>adapter.time<br/>adapter.log"]:::ext
  Wiring["Wiring<br/>Engine / EngineConfig"]:::mixed

  Domain --> Ports
  Adapters -.->|"implements"| Ports
  Wiring --> Adapters
  Wiring --> Domain

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef mixed fill:#fff9c4,stroke:#f9a825,stroke-width:1px,color:#111;
  classDef ext fill:#eceff1,stroke:#546e7a,stroke-width:1px,color:#111;
```

## Allowed imports

| Package | May import | Must not import |
|---|---|---|
| `core.model`, `core.error`, `core.json`, `core.pattern`, `core.shell.scan`, `core.port.*` | `java.*` | `core.event`, `core.contract`, `core.eval`, `runtime`, `adapter` |
| `core.event`, `core.contract.*` | S0 | `core.eval`, `runtime`, `adapter` |
| `core.eval` | S0, S1 | `runtime`, `adapter` |
| `runtime.*` | S0–S2, own runtime packages | `adapter.*`, `http`/Quarkus |
| `adapter.*` | S0 ports, technology | `runtime` |
| `quarkus` | everything | – (outer skin) |

## ArchUnit rules (planned / not yet implemented)

| Rule | Check |
|---|---|
| `s0DoesNotDependOnLaterStages` | S0 must not access S1/S2/Runtime/Adapter |
| `s1DoesNotDependOnLaterStages` | S1 must not access S2/Runtime/Adapter |
| `s2DoesNotDependOnLaterStages` | S2 must not access Runtime/Adapter |
| `runtimeDoesNotDependOnAdapters` | Runtime knows no concrete adapters |
| `adaptersDoNotDependOnRuntime` | Adapters know no Runtime |
| `adaptersAreOnlyUsedByWiring` | within the Engine no class outside `adapter.*` imports an adapter; the wiring layer in the Quarkus module instantiates them |
| `noCyclesBetweenPackages` | no package cycles |
| `javaSqlOnlyInSqlAdapter` | `java.sql` only in `adapter.sql` / `adapter.sqlite` |
| `engineStaysFreeOfQuarkusAndHttp` | Engine free of Quarkus, CDI, JAX-RS, Jackson, SmallRye |
| `noImplSuffix` | no classes with `Impl` suffix |
| `noStandardStreams` | no direct access to `System.out`/`System.err` |
| `noGenericExceptions` | no generic exceptions |
| `portsAreInterfaces` | top-level types in `core.port` are interfaces |


> **Current state:** The dependency rules are documented and reviewed, but there
> is currently **no ArchUnit test** in the repository. Enforcement today comes
> from Maven module boundaries, package conventions and code review.

## Why this direction?

- **Testability:** S0–S2 can be tested without DB, HTTP, or LLM.
- **Replaceability:** SQLite/PostgreSQL, JDK-HTTP, and process execution are
  adapters, not domain.
- **Determinism:** The core makes decisions; technology only executes.
- **Security:** SQL, HTTP, and time are bound to clearly named adapters.

## Known deviations

1. The spec calls `engine.http` S8; in the current build the
   HTTP/wiring layer lives in the module `quarkus`.
2. `runtime.execution.Coordinator` exists but is not wired into the HTTP path.
3. The stage order within `runtime` is not fully checked mechanically; the
   package-info comments and reviews supplement the ArchUnit rules.

## Sources

- `engine/src/test/java/de/augmentia/oc/engine/arch/ArchitectureTest.java`
- `docs/spec/module-plan.md`
- `docs/spec/java-packages.md`
- `engine/src/main/java/de/augmentia/oc/engine/`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/`
