# SAGA – Durable Multi-Step Execution mit Kompensationen

> **Zweck:** Erklärt, wie ein SAGA-Task in QOC ausgeführt, persistiert,
> wiederhergestellt und kompensiert wird. Basis sind die Konzepte aus
> [`Task-Orchestration.md`](./Task-Orchestration.md) §6 und die bestehende
> Recovery-Implementierung in `engine/runtime/execution/Recovery.java`.
>
> **Status:** 2026-11-10 – Konzept + Speicheranforderungen dokumentiert;
> `Recovery` ist verdrahtet, ein eigener `SagaStore` existiert noch nicht.
>
> **Code-Referenzen:**
> - `engine/runtime/execution/Recovery.java` – Crash-Recovery (Claims)
> - `engine/runtime/execution/Coordinator.java` – Claim-Acquire/Release
> - `engine/runtime/QocEngine.java` – `recover()`, Claim-Guard in `runPrompt`
> - `quarkus/.../http/Engine.java` – Aufruf von `qocEngine.recover()` beim Start
> - `temporal/.../workflow/SagaWorkflowImpl.java` – Temporal-Beispiel (toy)

---

## 1. Was ist eine SAGA in QOC?

Eine SAGA ist ein **Multi-Step-Task**, bei dem jeder Schritt eine
**Kompensation** (Undo-Aktion) haben kann. Schlägt ein Schritt fehl, werden
die Kompensationen der bereits abgeschlossenen Schritte in **umgekehrter
Reihenfolge** ausgeführt.

```
Saga: [Step1, Step2, Step3]
  Step1 OK  → Comp1 registriert
  Step2 OK  → Comp2 registriert
  Step3 FAIL → Comp2 → Comp1 (rückwärts)
  → Saga FAILED (mit Compensations-Status)
```

Eigenschaften:

- **Durable**: Zustand muss Neustart überleben.
- **At-least-once mit Idempotenz**: Schritte und Kompensationen dürfen bei
  Recovery erneut angestoßen werden, müssen aber idempotent sein.
- **Journal-basiert**: Jeder Übergang wird als Event protokolliert.
- **Claim-geschützt**: Genau eine aktive Execution pro Session/Saga.

## 2. Datenmodell

```java
public record SagaStep(
    String name, String agent, String prompt,
    String compensationAgent, String compensationPrompt) {}

public record SagaDefinition(
    String id, int version, List<SagaStep> steps, List<String> dependsOn) {}

public record SagaExecution(
    String sagaId, String taskId, int currentStep,
    List<String> completedSteps, List<String> compensationsRun, SagaState state) {}
```

Zustände (`SagaState`):

```
RUNNING → (Fehler) → COMPENSATING → (Kompensationen durch) → FAILED | COMPLETED
CANCELLED (manuell)
ORPHANED (nach Crash, vor Recovery)
MANUAL_INTERVENTION (Kompensation fehlgeschlagen)
```

## 3. Journal-Events für SAGA

Alle Übergänge werden als `SessionEvent`/`Payload` im Journal festgehalten:

```
SagaStarted
SagaStepStarted
SagaStepCompleted
SagaStepFailed
SagaCompensationStarted
SagaCompensationCompleted
SagaCompensationFailed
SagaCompleted
SagaFailed
SagaCancelled
```

Zusätzlich die normalen Execution-Events:

```
ExecutionStarted
ExecutionSucceeded
ExecutionFailed
ExecutionInterrupted
SyntheticRaised   (Recovery-Hinweis an das Modell)
```

Das Journal ist die **Quelle der Wahrheit**. Projektionen (Session, Conversation,
Saga-State) sind Read-Models.

## 4. Benötigte Storage-Stores

### P0 – vor jedem Side-Effect durable

| Store | Tabellen | Begründung |
|---|---|---|
| `journal` | `event`, `event_sequence` | Timeline, Replay, Recovery-Entscheidung |
| `claims` | `session_claim`, `resume_counter` | Genau eine aktive Execution; Crash-Erkennung |
| `sessions` + `sessionReader` | `session`, `session_inbox` | Identität, Agent, Location, Inbox |
| `conversation` | `session_message` | Kontext für jeden Step |
| **neuer `SagaStore`** | siehe §5 | Materialisierter Zustand für schnelle Recovery |
| **durablers `IdempotencyStore`** | siehe §6 | Exactly-once für Forward-/Compensation-Schritte |

### P1 – muss Neustart überleben

| Store | Tabellen | Begründung |
|---|---|---|
| `approvals` | `saved_approval`, `pending_approval` | HITL-Steps |
| `instructions` | `instruction_state`, `instruction_entry`, `instruction_blob` | Reproduzierbarkeit |
| `workspaces` | Spalten in `session` | Workspace pro Step, Pause-Zustand |
| `credentials` | `credential` | Externe Aufrufe |

### P2 – unkritisch / ableitbar

| Store | Tabellen | Begründung |
|---|---|---|
| `snapshots` | `snapshot`, `snapshot_file` | Datei-Kompensation / Revert |
| `tool-outputs` | `tool_output` | Evidenz, nicht Zustand |
| `tool-cache` | `tool_result_cache` | Performance; keine Dauer-Garantie |

## 5. Vorschlag: SagaStore-Schema

```sql
create table saga_definition (
  saga_id     text primary key,
  version     int  not null,
  definition  jsonb not null,
  created_at  timestamptz not null default now()
);

create table saga_execution (
  saga_id      text primary key,
  task_id      text not null,
  state        text not null,          -- RUNNING | COMPENSATING | COMPLETED | FAILED | CANCELLED | ORPHANED | MANUAL_INTERVENTION
  current_step int  not null default 0,
  version      int  not null,
  owner_id     text,
  updated_at   timestamptz not null default now()
);

create table saga_step (
  saga_id    text not null,
  step_no    int  not null,
  name       text not null,
  state      text not null,            -- PENDING | RUNNING | COMPLETED | FAILED | COMPENSATED
  attempts   int  not null default 0,
  idem_key   text not null,
  error      text,
  updated_at timestamptz not null default now(),
  primary key (saga_id, step_no)
);

create table saga_compensation (
  saga_id    text not null,
  step_no    int  not null,
  state      text not null,
  attempts   int  not null default 0,
  idem_key   text not null,
  error      text,
  updated_at timestamptz not null default now(),
  primary key (saga_id, step_no)
);
```

Das Journal bleibt die Quelle der Wahrheit; `saga_execution`/`saga_step` sind
materialisierte Read-Models für schnelle Recovery.

## 6. Idempotenz – nicht den Tool-Cache benutzen

`tool_result_cache` ist **keine** Dauer-Garantie:

- nur gefüllt, wenn ein `IdempotencyKey` gesetzt ist
- wird nach 1 Tag gelöscht (`RetentionService`)
- kann im Memory-Modus fehlen

Für SAGA brauchst du eine eigene Tabelle:

```sql
create table idempotency_record (
  scope       text not null,          -- saga | tool | compensation
  key         text not null,
  status      text not null,          -- STARTED | SUCCEEDED | FAILED
  result      jsonb,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  primary key (scope, key)
);
```

Regel: **Vor** jedem Side-Effect `STARTED` schreiben, danach
`SUCCEEDED`/`FAILED`. Bei Recovery wird `STARTED` gefunden und die Aktion
nicht blind wiederholt.

## 7. Kompensations-Regeln

1. Jeder abgeschlossene Step kann eine Kompensation haben.
2. Bei Fehler: Kompensationen in **umgekehrter** Reihenfolge.
3. Kompensationen müssen **idempotent** sein.
4. Kompensationen werden im Journal protokolliert
   (`SagaCompensationStarted/Ended`).
5. Wenn eine Kompensation fehlschlägt: `SagaCompensationFailed` →
   Zustand `MANUAL_INTERVENTION` + Alert.

## 8. Restart-/Recovery-Ablauf

### Claim-basierte Recovery (existiert)

`Recovery.recover()` iteriert über offene Claims:

1. `claims.open()` lesen.
2. Ist die Session in diesem Prozess aktiv → überspringen.
3. `resumeAttempts >= MAX_RESUME_ATTEMPTS` (10) → `ExecutionFailed`, Claim freigeben.
4. Sonst: `SyntheticRaised` („server was restarted“) appenden, Claim freigeben,
   dann `runPrompt` mit dem synthetischen Prompt ausführen.
5. `runPrompt` erwirbt einen neuen Claim und läuft normal.

### SAGA-Recovery (zu implementieren)

1. `select * from saga_execution where state in ('RUNNING','COMPENSATING')`.
2. Lease/Claim per CAS auf `owner_id` + Heartbeat erwerben.
3. Step-Status aus `saga_step` und Journal rekonstruieren.
4. `RUNNING` → ab `current_step` fortsetzen; vor jedem Step
   `SagaStepStarted` + Idempotency-Key persistieren.
5. Fehler → `state = COMPENSATING`; Kompensationen rückwärts; jede
   Kompensation durable protokollieren.
6. Kompensation fehlgeschlagen → `SagaCompensationFailed`,
   Zustand `MANUAL_INTERVENTION`, Alert.
7. Terminal: `SagaCompleted`/`SagaFailed`/`SagaCancelled`.

### Aktueller Stand

- `Recovery` ist verdrahtet (`QocEngine.recover()`, Aufruf in
  `Engine.wire()`), nutzt aber `stores.claims()` und `Coordinator`.
- `QocEngine.runPrompt` erwirbt/freigibt Claims; Execution-Events werden
  appendet (`ExecutionStarted`, `ExecutionSucceeded`, `ExecutionFailed`).
- `QocEngine.recover()` führt offene Claims mit synthetischem Prompt fort.
- Es gibt **keinen** `SagaStore` und **keine** Saga-Events in `SessionEvent`.
- SAGA ist nur als Temporal-Beispiel vorhanden (`SagaWorkflowImpl`).

## 9. Alternative: Temporal

Wenn SAGA über Temporal läuft, ist **Temporal selbst** der durable Store:

- Workflow-History, Retries, Timer, Signals, Activity-Ergebnisse liegen in
  Temporal.
- QOC braucht dann nur `journal` + `conversation` für Trace und Agent-Kontext.
- Kein QOC-`SagaStore` nötig.
- Der vorhandene `SagaWorkflowImpl` ist aber nur ein Beispiel und nicht an die
  QOC-Engine angebunden.

## 10. Konfiguration für durable Runs

```properties
ENGINE_STORAGE=postgres          # oder sqlite
ENGINE_RECOVERY_ENABLED=true
ENGINE_AUDIT_RETENTION_DAYS=0   # keine Event-Löschung
SESSION_RETENTION_DAYS=0        # keine Session-Löschung / kein Paused-Sweep
ENGINE_REVERT_SNAPSHOT=true
```

Hinweis: `SESSION_RETENTION_DAYS=0` deaktiviert den gesamten
`RetentionService`-Executor, also auch Audit-Purge und Paused-Sweep. Das ist
für SAGA/Dauerläufe gewünscht.

## 11. Risiken und Trade-offs

| Thema | Risiko | Gegenmaßnahme |
|---|---|---|
| At-least-once | doppelte Side-Effects | Idempotenz-Keys + `idempotency_record` |
| Claim-Leakage | Session nach Crash blockiert | `Recovery` + `MAX_RESUME_ATTEMPTS` |
| Kompensation fehlgeschlagen | manuelle Intervention | `SagaCompensationFailed` + Alert |
| Retention | Zustände werden gelöscht | Retention auf 0 |
| Multi-Node | SQLite nicht teilbar | Phase 4: Redis/etcd/Kafka |

## 12. Offene Fragen

1. Soll SAGA in QOC selbst oder in Temporal implementiert werden?
2. Wird `SagaStore` als eigener Port oder als Projektion aus dem Journal
   modelliert?
3. Wie werden Kompensationen definiert – durch den Agenten, den User oder
   Code?
4. Soll `IdempotencyStore` den `ToolResultCache` ersetzen oder ergänzen?
5. Sollen Snapshots für Datei-Kompensation genutzt werden oder explizite
   Compensation-Skripte?

## 13. Quellen

| Thema | Pfad |
|---|---|
| SAGA-Konzept | `qoc/docs/engineering/Task-Orchestration.md` §6 |
| Storage-Prioritäten | `qoc/docs/engineering/Storage-alternative.md` §4 |
| Recovery-Implementierung | `qoc/engine/.../execution/Recovery.java` |
| Coordinator/Claims | `qoc/engine/.../execution/Coordinator.java` |
| QocEngine-Recovery | `qoc/engine/.../runtime/QocEngine.java` (`recover()`) |
| Temporal-Saga | `qoc/temporal/.../workflow/SagaWorkflowImpl.java` |
| Retention | `qoc/quarkus/.../http/RetentionService.java` |