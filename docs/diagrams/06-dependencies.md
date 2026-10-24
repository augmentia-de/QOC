# 06 – Abhängigkeiten und Architekturregeln

## Zweck

Dieses Diagramm zeigt die erlaubte Abhängigkeitsrichtung der Stages S0–S8 und
die Regeln, die per ArchUnit im Build geprüft werden. Es macht sichtbar, warum
der Kern testbar bleibt und wo Technik (SQL, HTTP, Zeit) hingehört.

## Stage-Abhängigkeiten

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
  S8["S8<br/>HttpApi · Engine · EventBus<br/>(aktuell in qoc/quarkus)"]:::mixed

  S0 --> S1 --> S2 --> S3 --> S4 --> S5 --> S6 --> S7 --> S8
  S5 --> S7

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef mixed fill:#fff9c4,stroke:#f9a825,stroke-width:1px,color:#111;
```

S3 ist überwiegend deterministisch. Der `provider.client` übersetzt Request und
Stream deterministisch; der entfernte LLM-Dienst ist nicht deterministisch und
wird deshalb als „mixed“ markiert.

## Adapter außerhalb der Stages

```mermaid
flowchart LR
  Domain["core + runtime<br/>kennt nur Ports"]:::det
  Ports["core.port.*<br/>Schnittstellen"]:::det
  Adapters["adapter.sql / sqlite<br/>adapter.fs<br/>adapter.process<br/>adapter.net<br/>adapter.time<br/>adapter.log"]:::ext
  Wiring["Wiring<br/>Engine / EngineConfig"]:::mixed

  Domain --> Ports
  Adapters -.->|"implementiert"| Ports
  Wiring --> Adapters
  Wiring --> Domain

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef mixed fill:#fff9c4,stroke:#f9a825,stroke-width:1px,color:#111;
  classDef ext fill:#eceff1,stroke:#546e7a,stroke-width:1px,color:#111;
```

## Erlaubte Importe

| Package | Darf importieren | Darf **nicht** importieren |
|---|---|---|
| `core.model`, `core.error`, `core.json`, `core.pattern`, `core.shell.scan`, `core.port.*` | `java.*` | `core.event`, `core.contract`, `core.eval`, `runtime`, `adapter` |
| `core.event`, `core.contract.*` | S0 | `core.eval`, `runtime`, `adapter` |
| `core.eval` | S0, S1 | `runtime`, `adapter` |
| `runtime.*` | S0–S2, eigene Runtime-Packages | `adapter.*`, `http`/Quarkus |
| `adapter.*` | S0-Ports, Technik | `runtime` |
| `quarkus` | alles | – (Outer Skin) |

## ArchUnit-Regeln (Ist-Stand)

| Regel | Prüfung |
|---|---|
| `s0DoesNotDependOnLaterStages` | S0 darf nicht auf S1/S2/Runtime/Adapter zugreifen |
| `s1DoesNotDependOnLaterStages` | S1 darf nicht auf S2/Runtime/Adapter zugreifen |
| `s2DoesNotDependOnLaterStages` | S2 darf nicht auf Runtime/Adapter zugreifen |
| `runtimeDoesNotDependOnAdapters` | Runtime kennt keine konkreten Adapter |
| `adaptersDoNotDependOnRuntime` | Adapter kennen keine Runtime |
| `adaptersAreOnlyUsedByWiring` | innerhalb der Engine importiert keine Klasse außerhalb `adapter.*` einen Adapter; die Wiring-Schicht im Quarkus-Modul instanziiert sie |
| `noCyclesBetweenPackages` | keine Package-Zyklen |
| `javaSqlOnlyInSqlAdapter` | `java.sql` nur in `adapter.sql` / `adapter.sqlite` |
| `engineStaysFreeOfQuarkusAndHttp` | Engine frei von Quarkus, CDI, JAX-RS, Jackson, SmallRye |
| `noImplSuffix` | keine Klassen mit `Impl`-Suffix |
| `noStandardStreams` | kein direkter Zugriff auf `System.out`/`System.err` |
| `noGenericExceptions` | keine generischen Exceptions |
| `portsAreInterfaces` | Top-Level-Typen in `core.port` sind Interfaces |

## Warum diese Richtung?

- **Testbarkeit:** S0–S2 lassen sich ohne DB, HTTP oder LLM testen.
- **Austauschbarkeit:** SQLite/PostgreSQL, JDK-HTTP und Prozessausführung sind
  Adapter, keine Domain.
- **Determinismus:** Der Kern trifft Entscheidungen; Technik führt nur aus.
- **Sicherheit:** SQL, HTTP und Zeit sind an klar benannte Adapter gebunden.

## Bekannte Abweichungen

1. Die Spec nennt `engine.http` als S8; im aktuellen Build liegt die
   HTTP-/Wiring-Schicht im Modul `quarkus`.
2. `runtime.execution.Coordinator` ist vorhanden, aber nicht im HTTP-Pfad
   verdrahtet.
3. Die Stage-Reihenfolge innerhalb von `runtime` wird nicht vollständig
   mechanisch geprüft; die package-info-Kommentare und Reviews ergänzen die
   ArchUnit-Regeln.

## Quellen

- `qoc/engine/src/test/java/de/augmentia/oc/engine/arch/ArchitectureTest.java`
- `qoc/docs/spec/module-plan.md`
- `qoc/docs/spec/java-packages.md`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/`
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/`
