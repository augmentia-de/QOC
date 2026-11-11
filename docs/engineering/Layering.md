# Layering – Agent-Runtime vs. Workflow-Orchestrierung

> **Zweck:** Festlegung der Schichtengrenze zwischen Agent-Runtime (Kern)
> und Workflow-Orchestrierung. Klärt, wo Subagents/Tools leben und wo
> Resilienz, SAGA, Abhängigkeiten und Cross-Session-Wake hingehören.
>
> **Status:** 2026-11-09 (Entscheidung)
>
> **Referenzen:** [`Core.md`](./Core.md),
> [`Task-Orchestration.md`](./Task-Orchestration.md),
> [`Async-use.md`](./Async-use.md),
> [`Storage-alternative.md`](./Storage-alternative.md).

## 1. Entscheidung

**Beide Schichten sind sinnvoll.** Subagents leben im **Kern**, aber nur
als **Helfer (Primitive)** – ohne Resilienz, Saga oder Abhängigkeiten.
Workflow-Orchestrierung liegt **darüber** und nutzt Subagents als **Worker**.

| Ebene | Frage | Kennt Subagents/Tools als … |
|---|---|---|
| **Agent-Runtime (Kern)** | „Wie führe ich **einen** Turn aus?" | **Tool** – `subagent` ist ein Tool, das der Agent aufrufen kann. |
| **Workflow-Schicht (oben)** | „Wie verteile ich **mehrere** Aufgaben auf mehrere Agenten?" | **Teilnehmer** – Subagents sind Worker, die der Workflow steuert. |

## 2. Was gehört in den Kern (Agent-Runtime)

Alles, was einen **einzelnen** Agent-Turn ausführt:

| Baustein | Rolle |
|---|---|
| `Step` | Ein Modell-Call + parallele Tool-Ausführung |
| `ContextBuilder` / `Prepared` | Kontext eines Turns |
| `ToolRuntime` / `ToolAuthorizer` | Tool-Ausführung |
| `PermissionEngine` | Autorisierung pro Turn |
| `CompactionService` | Kontext-Management |
| `Coordinator` (single-session) | Claim, Doorbell, Terminal-Event **einer** Session |
| `Admission` / `Delivery` | Inbox einer Session |
| `Recovery` (single-session) | Claim-basierte Wiederaufnahme |
| `ProviderAdapter` | LLM-Aufruf |

### 2.1 Subagents im Kern – nur als Helfer

Der Kern stellt die **Primitive** bereit, damit der Agent delegieren kann:

| Baustein | Rolle im Kern |
|---|---|
| `SubagentTool` | `ToolDefinition` im Snapshot – der Agent kann `subagent` aufrufen |
| `SubagentRunner` | Ausführungsprimitive: `run(session, agent, prompt)` |
| `BackgroundTasks` | Primitive Registry: Task-ID, Status, Ergebnis (in-memory) |
| `SubagentSessionRegistry` | Parent/Child-Tracking |
| `PermissionGate` | Autorisierung der Delegation |
| `ToolRuntime` | Hooks, Decode, Encode |

**Der Kern weiß nichts von:** Abhängigkeiten, DAGs, Tiers, Kompensationen,
durable Persistenz, Cross-Session-Wake, Quotas, Versionierung.

Der Kern führt einen `subagent`-Aufruf aus und liefert ein Ergebnis. Punkt.

## 3. Was gehört in die Workflow-Schicht (oben)

Orchestrierung von **mehreren** Agent-Turns:

| Baustein | Rolle |
|---|---|
| `TaskNode` + `dependsOn` | Task-Modell mit Abhängigkeiten |
| DAG-Scheduler | Reihenfolge, Parallelität, Fan-out/Fan-in |
| `TaskTier` (research/analyze/execute/saga) | Policy über Agent-Profile |
| `SagaRunner` + Kompensationen | Multi-Step-Transaktionen |
| `BackgroundTaskStore` (durable) | Persistenz über Neustart |
| `TaskResultDelivery` | Inbox-Steer an Parent |
| Task-Quota pro Session | Ressourcen-Governance |
| Versions-/Schemaversionierung | Lebenszyklus von Definitionen |
| Heartbeat (Task/Worker) | Observability über Sessions hinweg |
| Scheduler/Webhook-Trigger | Externe Einstiegspunkte |

### 3.1 Subagents in der Workflow-Schicht – als Worker

Hier sind Subagents keine Tools, sondern **Teilnehmer** in einem Plan:

- „Starte Research-Subagent A, dann Analysis-Subagent B, dann Execute C."
- „Führe A und B parallel aus, warte auf beide, dann C."
- „Wenn C fehlschlägt, kompensiere B und A."

Die Workflow-Schicht entscheidet:
- **Wann** ein Subagent startet (Abhängigkeiten).
- **Welchen Tier** er nutzt (research/analyze/execute/saga).
- **Ob** er durable sein muss.
- **Wie** das Ergebnis weitergegeben wird (Inbox-Delivery).

## 4. Kommunikation zwischen den Schichten

Die Workflow-Schicht nutzt den Kern **nur über Ports**, nie über Interna:

```
┌─────────────────────────────────────────────────────┐
│ Workflow-Schicht (runtime/workflow)                 │
│   TaskService, DAG-Scheduler, SagaRunner,           │
│   TaskStore, HeartbeatPublisher, Quota              │
│        │  Ports (SessionStore, Journal, Inbox,      │
│        │  ClaimStore, SessionWake, ToolRuntime)     │
└────────┼────────────────────────────────────────────┘
         │
┌────────┼────────────────────────────────────────────┐
│ Agent-Runtime (engine core)                        │
│   Session, Coordinator, Step, ContextBuilder,       │
│   ToolRuntime, PermissionEngine, Compaction         │
└────────────────────────────────────────────────────┘
```

| Workflow-Aktion | Kern-Port, der genutzt wird |
|---|---|
| Task startet Subagent | `SessionService.create` + `Coordinator.wake` |
| Task-Ergebnis an Parent | `Admission.admit` + `SessionWake.wake` |
| Task-Claim (durabler) | `ClaimStore.acquire/release` |
| Task-Events | `Journal.append` |
| Tool-Ausführung im Task | `ToolRuntime` (über `Step`) |
| Permission im Task | `PermissionEngine` |
| Heartbeat | `EventBus.publishLive` + Journal |
| Quota | Semaphore (Runtime-Primitive) |

**Regel:** Der Kern importiert **nichts** aus der Workflow-Schicht.
Die Abhängigkeit zeigt nur nach unten (Workflow → Core).

## 5. Aufteilung: was wo liegt

| Konzept | Kern (Runtime) | Workflow-Schicht |
|---|---|---|
| `SubagentTool` als Tool-Definition | ✅ | ❌ (nutzt es nur indirekt) |
| `SubagentRunner.run(session, agent, prompt)` | ✅ | ✅ (ruft es auf) |
| `BackgroundTasks` (Registry) | ✅ (primitive) | ✅ (steuert Lifecycle) |
| `SubagentSessionRegistry` | ✅ | ❌ |
| Task-Abhängigkeiten, DAG | ❌ | ✅ |
| Tier-Modell | ❌ | ✅ |
| SAGA + Kompensationen | ❌ | ✅ |
| Durable Task-Persistenz | ❌ | ✅ |
| Cross-Session-Wake | ❌ | ✅ (nutzt `SessionWake`) |
| Task-Quota pro Session | ❌ | ✅ |
| Versions-/Schemaversionierung | ❌ | ✅ |
| Heartbeat (Execution, single-session) | ✅ | ❌ |
| Heartbeat (Task/Worker) | ❌ | ✅ |
| Permission-Check für Subagent | ✅ | ❌ (delegiert an Kern) |
| Tool-Snapshot-Filterung | ✅ | ❌ (delegiert an Kern) |

## 6. Warum nicht nur eine Schicht?

| Ansatz | Problem |
|---|---|
| **Nur Kern** (Subagents als Tools, kein Workflow) | Keine Abhängigkeiten, keine SAGA, keine durable Tasks, kein Cross-Session-Wake. Agent muss alles selbst steuern → komplex, fehleranfällig. |
| **Nur Workflow** (alles orchestriert) | Workflow müsste Tool-Ausführung, Permissions, Kontext-Building, Compaction kennen → verletzt Single Responsibility, schwer testbar. |
| **Beide (Kern + Workflow)** | Klare Trennung: Kern = „ein Turn", Workflow = „viele Turns koordinieren". |

**Merksatz:** Der Kern stellt die **primitive** „führe Subagent X aus" bereit.
Die Workflow-Schicht **komponiert** daraus Pläne mit Abhängigkeiten, Tiers
und Kompensationen. Resilienz und Saga leben **nur** oben.

## 7. Aktueller Stand in QOC (Vermischung)

Im Code ist die Trennung noch nicht sauber:

| Baustein | Aktueller Ort | Sollte sein |
|---|---|---|
| `BackgroundTasks` | `runtime/tool/subagent/` | `runtime/workflow/` |
| `SubagentTool.executeBackground` | `runtime/tool/subagent/` | `runtime/workflow/` (ruft `SubagentRunner` auf) |
| `TaskTool` | `runtime/tool/subagent/` | `runtime/workflow/` (oder HTTP-API) |
| `SubagentSessionRegistry` | `runtime/tool/subagent/` | `runtime/workflow/` |
| `Engine` (Quarkus) | orchestriert Subagents + Live-Events | Workflow-Schicht nutzt `Engine` als Kern-API |
| `MAX_CONCURRENT_SUBAGENTS` | `Engine.java:2249` | `TaskQuota` in Workflow-Schicht |

## 8. Migrationsschritte

1. `BackgroundTasks` + `SubagentTool.executeBackground` + `TaskTool` +
   `SubagentSessionRegistry` aus `runtime/tool/subagent/` nach
   `runtime/workflow/` verschieben (reine Paket-Umbenennung).
2. `TaskNode` + `dependsOn` ergänzen; `TaskService` als Fassade.
3. DAG-Scheduler (`TaskScheduler`) implementieren.
4. Tier-Definitionen als Daten einführen.
5. `BackgroundTaskStore`-Port definieren; Memory- und SQL-Adapter.
6. Cross-Session-Wake über `Admission`/`SessionWake`.
7. SAGA + Kompensationen (durable, Journal-Events).
8. Heartbeat (`HeartbeatPublisher`) als Workflow-Komponente.
9. Quota pro Session im `TaskService`.
10. ArchUnit-Regeln für die Schichtgrenze.

## 9. Wann lohnt sich die Trennung, wann nicht?

| Kriterium | Trennung lohnt sich | Trennung nicht nötig |
|---|---|---|
| **Scope** | Multi-Session-Workflows, SAGA, durable Tasks | Nur einzelne Subagents, kein DAG |
| **Testbarkeit** | Workflow-Tests gegen Fake-Kern | Einfache Subagent-Calls |
| **Skalierung** | Workflow als eigener Service | Single-Process |
| **Team** | Getrennte Verantwortung Kern/Workflow | Kleines Team |
| **Komplexität** | Abhängigkeiten, Kompensationen, Versionen | Keine Abhängigkeiten |

**Empfehlung für QOC:** Die Trennung lohnt sich, weil:
- Die Bausteine existieren bereits und sind nur falsch platziert.
- Die Spec beschreibt bereits eine Schichtung (Coordinator = single-session,
  Workflow = multi-session).
- Durable Tasks und SAGA erfordern Persistenz, die der Kern nicht kennen
  sollte.
- Die Hexagonal-Architektur passt dazu: Workflow ist ein `runtime.*`-Modul,
  das `core.*` nutzt, aber nicht umgekehrt.

**Wenn du nur einfache Subagents brauchst** (kein DAG, kein SAGA, keine
Persistenz), reicht die aktuelle Struktur. Die Trennung wird relevant, sobald
du **Abhängigkeiten, Versionen oder Kompensationen** brauchst.

## 10. Referenzen

- [`Core.md`](./Core.md) §2 (Kernumfang), §8 (Extraktionsgrenze)
- [`Task-Orchestration.md`](./Task-Orchestration.md) (Task-Abhängigkeiten, Tiers, SAGA)
- [`Async-use.md`](./Async-use.md) (Variante B/C, Heartbeat, Recovery)
- [`Storage-alternative.md`](./Storage-alternative.md) (Persistenz, Journal als Source of Truth)
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/` (aktuelle, zu verschiebende Bausteine)
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java` (Subagent-Wiring, Live-Events)
- `qoc/docs/spec/session-engine/12-execution-model.md` (Execution-Modell)
- `qoc/docs/spec/session-engine/04-algorithms.md` (Algorithmen)