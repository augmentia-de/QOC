# 04 – Session-Lebenszyklus und Ausführungssteuerung

## Zweck

Dieses Diagramm zeigt den Lebenszyklus einer Session und die deterministische
Steuerung eines Laufs: Claim, Doorbell, genau ein aktiver Lauf pro Session und
ein terminales Ereignis.

> **Ist-Stand:** Die Zustände sind eine Lesart der vorhandenen Events,
> Projektionen und Bausteine. Es gibt keine separate `session_state`-Tabelle.
> `Coordinator`, `SessionExecution` und `Recovery` existieren und sind getestet,
> werden vom HTTP-Pfad aber noch nicht verwendet. `Engine` arbeitet aktuell mit
> einer eigenen `running`-Map und einem direkten Prompt-Loop.

## Session-Zustände

```mermaid
stateDiagram-v2
  [*] --> Created: SessionCreated
  Created --> Idle
  Idle --> Queued: InboxEnqueued
  Queued --> Delivered: InboxDelivered
  Delivered --> Running: Engine.prompt / runPrompt
  Running --> Running: Step + Tool-Calls
  Running --> Compacting: Context Overflow
  Compacting --> Running: CompactionEnded
  Running --> Completed: kein Tool-Call / maxSteps
  Running --> Interrupted: User Interrupt
  Running --> Failed: Provider-/Tool-Fehler
  Running --> Suspended: Shutdown
  Suspended --> Running: Recovery
  Completed --> Idle
  Interrupted --> Idle: Claim frei
  Failed --> Idle: Claim frei
  Idle --> Deleted: SessionDeleted / Retention
  Created --> Deleted: DELETE /api/session/{id}

  note right of Running
    Deterministischer Rahmen:
    Claim, ein aktiver Lauf pro Session,
    Doorbell, ein terminales Event.
    Das LLM entscheidet nur über Text
    und Tool-Calls innerhalb eines Steps.
  end note
```

## Claim, Doorbell und Recovery

```mermaid
flowchart TD
  Wake["wake(session)"]:::det --> Active{"Lauf aktiv?"}:::det
  Active -->|"ja"| Ring["Doorbell ring<br/>Scope mergen"]:::det
  Ring --> Coalesce["kein zweiter Lauf<br/>Wake wird zusammengefasst"]:::det
  Active -->|"nein"| Acquire["ClaimStore.acquire(session)"]:::det
  Acquire -->|"leer"| Busy["bereits geclaimt<br/>kein Lauf"]:::det
  Acquire -->|"ok"| Started["ExecutionStarted"]:::det
  Started --> Run["ExecutionRunner.run"]:::mixed
  Run --> Terminal{"terminal?"}:::det
  Terminal -->|"success"| Success["ExecutionSucceeded<br/>Claim release"]:::det
  Terminal -->|"failed"| Failed["ExecutionFailed<br/>Claim release"]:::det
  Terminal -->|"interrupted (user)"| Interrupted["ExecutionInterrupted<br/>Claim release"]:::det
  Terminal -->|"interrupted (shutdown)"| Keep["Claim bleibt<br/>Recovery"]:::det
  Success --> Follow{"Doorbell?"}:::det
  Failed --> Follow
  Interrupted --> Follow
  Follow -->|"ja"| Wake
  Follow -->|"nein"| Idle["idle"]:::det
  Keep --> Recovery["Recovery beim Neustart"]:::det
  Recovery --> Run

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef mixed fill:#fff9c4,stroke:#f9a825,stroke-width:1px,color:#111;
```

## Regeln

| Regel | Bedeutung |
|---|---|
| **Ein aktiver Lauf pro Session** | `Coordinator` hält `SessionExecution` pro Session; parallele Wakes werden über die Doorbell zusammengefasst. |
| **Claim überlebt den Prozess** | Ein Shutdown gibt den Claim nicht frei; beim Neustart übernimmt `Recovery`. |
| **Genau ein terminales Event** | `ExecutionSucceeded`, `ExecutionFailed` oder `ExecutionInterrupted`. |
| **Doorbell ist koaleszierend** | Mehrere Wakes während eines Laufs führen zu genau einem Folge-Lauf. |
| **Interrupt ist idempotent** | Ein Interrupt auf einer inaktiven Session ist ein No-op. |
| **User-Interrupt gibt Claim frei** | Shutdown behält ihn für Recovery. |

## Wo bleibt das LLM?

Der LLM-Anteil liegt **innerhalb** von `ExecutionRunner.run` bzw. `Step.run`:
Text und Tool-Calls. Die Zustandsübergänge, Claims, Doorbell und terminalen
Events sind deterministisch.

## Quellen

- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Coordinator.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/SessionExecution.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/ExecutionRunner.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Recovery.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/core/model/SessionEvent.java`
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java`
