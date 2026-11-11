# Task-Orchestration – Abhängigkeiten, gestufte Versionen, SAGA

> **Zweck:** Detaillierte Beschreibung, wie Tasks in QOC voneinander abhängig,
> gestuft, versioniert und als SAGA ausgeführt werden können – inklusive
> Durable Persistenz, Cross-Session-Wake und Task-Quota.
>
> **Status:** 2026-11-09 (Analyse / Planung, nicht implementiert)
>
> **Referenzen:** [`Async-use.md`](./Async-use.md),
> [`Core.md`](./Core.md), [`Storage-alternative.md`](./Storage-alternative.md).

## 1. Ausgangslage in QOC

Bereits vorhanden:

| Baustein | Datei | Was er kann |
|---|---|---|
| `BackgroundTasks` | `engine/.../runtime/tool/subagent/BackgroundTasks.java` | In-Memory-Registry pro Parent: `create`, `succeed`, `fail`, `cancel`, `await`, `list`, `get` |
| `SubagentTool` | `.../SubagentTool.java` | `background=true` → virtueller Thread; `tasks=[...]` → Batch (`executeBatch`, sequenziell, Fail-fast) |
| `SubagentSessionRegistry` | `.../SubagentSessionRegistry.java` | Parent→Child-Mapping |
| HTTP-Tasks | `HttpApi.java:513,525` | `GET /session/{id}/tasks`, `POST .../tasks/{taskId}/cancel` |
| SSE | `Engine.java:2404/2437/2448/2454` | `SubagentStarted/Finished/Cancelled` |

**Was fehlt:** Abhängigkeiten zwischen Tasks, Stufen (Research … Saga),
Versions-/Schemaversionierung, Kompensation bei Teilfehlern, Persistenz über
Neustart.

---

## 2. Task-Abhängigkeiten (`dependsOn`)

### 2.1 Modell

Ein Task kann eine Liste von Task-IDs angeben, die vor ihm abgeschlossen
sein müssen.

```java
public record TaskNode(
    String id,
    String agent,
    String prompt,
    List<String> dependsOn,          // Task-IDs
    TaskTier tier,                   // research | analyze | execute | saga
    int version,                     // Definitionsversion
    TaskState state,
    Option<String> result,
    Instant created,
    Instant finished
) {}
```

### 2.2 Semantik

- Ein Task mit `dependsOn` startet nicht sofort, sondern geht in den Zustand
  `WAITING`.
- Er wird `READY`, sobald **alle** Vorgänger `SUCCEEDED` sind.
- Schlägt ein Vorgänger fehl (`FAILED`/`CANCELLED`), wird der Nachfolger
  `BLOCKED` (oder `CANCELLED`, je nach Policy).
- Ein Task ohne `dependsOn` ist sofort `READY`.

**Warum sinnvoll:** Verhindert, dass Task B mit unvollständigen Daten von A
arbeitet; ermöglicht parallele Ausführung unabhängiger Tasks; macht den
Datenfluss explizit.

### 2.3 Konkret in QOC

- `TaskNode` bekommt `List<String> dependsOn`.
- `BackgroundTasks.create(parent, agent, dependsOn)` prüft, ob alle IDs
  existieren.
- `tryStartReady(parent)` startet alle Tasks, deren Vorgänger alle
  `SUCCEEDED` sind und ein Slot frei ist.

---

## 3. DAG-Scheduler

### 3.1 Konzept

Ein leichtgewichtiger Planer für gerichtete azyklische Graphen (DAG) von
Tasks. Tasks können mehrere Vorgänger (Diamond) und mehrere Nachfolger
(Fan-out) haben; keine Zyklen erlaubt.

### 3.2 Ablauf

```
1. Task wird erstellt → Vorgänger prüfen → WAITING oder READY
2. Scheduler sucht alle READY-Tasks (Vorgänger alle SUCCEEDED)
3. Startet so viele wie Slots frei sind (Quota)
4. Bei Terminal-Event eines Tasks:
   a. Vorgänger-Status aktualisieren
   b. tryStartReady() erneut ausführen
   c. Ggf. Nachfolger BLOCKED markieren
```

### 3.3 Vorteile gegenüber `executeBatch` (heute)

| `executeBatch` (heute) | DAG-Scheduler (vorgeschlagen) |
|---|---|
| Strikt sequenziell | Parallel, wo möglich |
| Fail-fast (erster Fehler bricht alles ab) | Konfigurierbar: block oder cancel |
| Keine Ergebnis-Weitergabe | Ergebnis-Interpolation |
| Keine Abhängigkeiten | Explizite `dependsOn` |
| Ein Tool-Call | Mehrere Tasks mit Quota |

### 3.4 Beispiel Diamond-DAG

```
        ┌─► Task B (analyze API) ─┐
Task A (research) ─┤                  ├─► Task D (implement)
        └─► Task C (analyze DB) ─┘
```

A läuft zuerst. B und C laufen parallel. D startet erst, wenn B und C
beide fertig sind.

---

## 4. Ergebnis-Interpolation

### 4.1 Was

Das Ergebnis eines Vorgänger-Tasks wird in den Prompt eines
Nachfolger-Tasks eingesetzt.

### 4.2 Mechanismen

| Mechanismus | Beschreibung | Eignung |
|---|---|---|
| **Text-Interpolation** | `{{task_<id>.result}}` im Prompt wird durch den Ergebnis-Text ersetzt | einfach, lesbar |
| **Strukturiert** | Ergebnis als JSON-Objekt in den Prompt-Kontext | typsicher, komplex |
| **Artifact-Referenz** | `artifact://task_<id>/result` → Nachfolger liest über `read`-Tool | für große Ergebnisse |

### 4.3 Beispiel

```
Task A (research): "Recherchiere REST-API-Best-Practices"
  → Ergebnis: "1. Pagination via cursor, 2. Rate-Limiting via Token-Bucket, ..."

Task B (analyze, dependsOn=[A]):
  Prompt: "Analysiere folgende Best-Practices auf Relevanz für QOC:
           {{task_A.result}}"
```

### 4.4 Grenzen

- Ergebnis-Größe: große Ergebnisse als Artifact referenzieren, nicht inline.
- Der Agent muss wissen, dass er mit einem interpolierten Ergebnis arbeitet.
- Bei `FAILED`-Vorgänger: Interpolation schlägt fehl → Nachfolger `BLOCKED`.

---

## 5. Tier-Modell (research / analyze / execute / saga)

### 5.1 Was

Vier Stufen, die den Charakter eines Tasks bestimmen. Jede Stufe hat eigene
Tool-Zugriffe, Step-Limits, Sandbox und Durability.

| Tier | Charakter | Tools | maxSteps | Sandbox | Durable |
|---|---|---|---|---|---|
| **research** | read-only, keine Side-Effects | read, glob, grep, webfetch, websearch | 5 | read-only | nein |
| **analyze** | Analyse, darf Entwürfe schreiben | research + write/edit (Scratch) | 10 | workspace-write | nein |
| **execute** | Vollzug mit Side-Effects | voller Katalog | 20 | konfigurierbar | ja |
| **saga** | Multi-Step, transaktional | voller Katalog + Kompensationen | 50+ | konfigurierbar | ja |

### 5.2 Warum Tiers?

- **Sicherheit:** Ein Research-Task kann nichts zerstören.
- **Kosten:** Research braucht weniger Steps und kein Durability.
- **Klarheit:** Der Aufrufer sagt, was er will („recherchiere" vs.
  „implementiere").
- **Quota:** Research-Tasks können aggressiver parallelisiert werden.

### 5.3 Umsetzung

```java
public enum TaskTier { RESEARCH, ANALYZE, EXECUTE, SAGA }

public record AgentProfile(..., TaskTier tier) { ... }
// Oder beim Aufruf:
// subagent(agent="researcher", prompt="...", tier="research")
```

Die Tier-Definition steuert: `maxSteps`, `tools.allow/deny`, `sandbox`,
`durable`, `compensations` (nur bei `saga`).

**Tier als Daten, nicht als Code:** JSON-Config oder Agent-Frontmatter, nicht
hartkodierte `if`-Ketten. Anpassung ohne Rebuild.

```json
{
  "tiers": {
    "research": { "maxSteps": 5, "tools": ["read","glob","grep","webfetch","websearch"], "sandbox": "read-only", "durable": false },
    "analyze":  { "maxSteps": 10, "tools": ["read","write","edit","glob","grep"], "sandbox": "workspace-write", "durable": false },
    "execute":  { "maxSteps": 20, "tools": "*", "sandbox": "workspace-write", "durable": true },
    "saga":     { "maxSteps": 50, "tools": "*", "sandbox": "workspace-write", "durable": true, "compensations": true }
  }
}
```

---

## 6. SAGA mit Kompensationen

### 6.1 Konzept

Ein SAGA-Task besteht aus mehreren **Schritten**, von denen jeder eine
**Kompensation** (Undo-Aktion) hat. Bei einem Fehler laufen die
Kompensationen in **umgekehrter Reihenfolge**.

```
Saga: [Step1, Step2, Step3]
  Step1 OK → Comp1 registriert
  Step2 OK → Comp2 registriert
  Step3 FAIL → Comp2 → Comp1 (rückwärts)
  → Saga FAILED (mit Compensations-Status)
```

### 6.2 Datenmodell

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

### 6.3 Kompensations-Regeln

1. Jeder abgeschlossene Step kann eine Kompensation haben.
2. Bei Fehler: Kompensationen in **umgekehrter** Reihenfolge.
3. Kompensationen müssen **idempotent** sein.
4. Kompensationen werden im Journal protokolliert
   (`SagaCompensationStarted/Ended`).
5. Wenn eine Kompensation fehlschlägt: `SagaCompensationFailed` →
   manuelle Intervention (Alert).

### 6.4 Journal-Events für SAGA

```
SagaStarted, SagaStepStarted, SagaStepCompleted, SagaStepFailed,
SagaCompensationStarted, SagaCompensationCompleted,
SagaCompensationFailed, SagaCompleted, SagaFailed, SagaCancelled
```

### 6.5 Persistenz

- SAGA-Tasks **müssen** durable sein (Journal + Claim).
- Bei Neustart: laufende SAGA → `ORPHANED`; Recovery entscheidet
  (Fortsetzen oder Kompensieren).
- Die `BackgroundTasks`-Registry allein reicht nicht; sie muss durch
  Journal-basierte Persistenz ergänzt werden.

---

## 7. Versions- und Schemaversionierung

### 7.1 Definitionsversion

- Jeder Task/Saga trägt `version` (Integer oder SemVer).
- Bei Änderung der Definition wird die Version erhöht.
- Laufende Tasks behalten ihre Version (kein Upgrade mid-flight).
- Ergebnisse tragen die Version mit (`TaskResult.metadata.version`).

### 7.2 Schemaversionierung

- Task-Payloads sind JSON mit optionalem `schemaVersion`-Feld.
- Fehlendes Feld → Default-Version annehmen.
- Migration: alte Payloads werden beim Laden normalisiert.

### 7.3 Agent-Konfiguration

- Agent-Definitionen haben bereits `hash` und `updatedAt`
  (`Engine.agent()`).
- Bei Task-Start wird der Agent-Hash gespeichert.
- Bei Resume wird geprüft, ob sich die Definition geändert hat
  (Warnung, kein automatisches Upgrade).

**Warum wichtig?** Ohne Versionierung bricht ein Update der
Agent-Definitionen alle laufenden Tasks. Mit Versionierung können alte
Definitionen weiterlaufen, während neue Tasks die neue Version nutzen.

---

## 8. Durable Task-Persistenz

### 8.1 Was

Tasks überleben Prozess-Neustarts. Aktuell ist die
`BackgroundTasks`-Registry **in-memory** und verliert alles.

### 8.2 Lösung (Journal-basiert)

```
TaskStarted   { taskId, parentSession, agent, promptHash, created }
TaskHeartbeat { taskId, at, phase }
TaskProgress  { taskId, at, message, percent? }
TaskSucceeded { taskId, at, childSession, text, usage }
TaskFailed    { taskId, at, error }
TaskCancelled { taskId, at, reason }
```

### 8.3 Ablauf

1. Task-Start: `Journal.append(TaskStarted)` (durable).
2. Heartbeat: periodisch `TaskHeartbeat` (durable oder nur in-memory).
3. Terminal: `TaskSucceeded/Failed/Cancelled` (durable).
4. `BackgroundTasks` wird zum **Read-Model** aus dem Journal.
5. Bei Neustart: `TaskStarted` ohne Terminal → `ORPHANED`; Recovery
   entscheidet (neu starten oder als `FAILED(orphaned)` markieren).

### 8.4 Claim-Interaktion

Ein durabler Task muss den Claim der Parent-Session halten, solange er
läuft. Bei Neustart wird der Claim als „offen" erkannt und die
Task-Recovery gestartet.

---

## 9. Cross-Session-Wake bei Task-Ende

### 9.1 Was

Wenn ein Background-Task (z. B. Subagent) beendet ist, wird die
Parent-Session **automatisch geweckt**, statt dass sie pollen muss.

### 9.2 Heute vs. Ziel

| Aspekt | Heute | Ziel |
|---|---|---|
| Mechanismus | `taskPoll` (`index.html:1331`), Polling alle 5 s | Event-Driven |
| Latenz | bis 5 s | sofort |
| Parent-Zustand | muss laufen | wird geweckt |
| Ergebnis-Zustellung | Abruf via `GET /tasks` | Inbox-Delivery (`steer`) |

### 9.3 Ablauf (Ziel)

```
Task beendet → Journal.append(TaskSucceeded)
            → InboxEnqueued(parent, result)
            → SessionWake.wake(parent)
            → Parent-Step: Delivery → neuer Step mit Ergebnis
```

### 9.4 Vorteile

- Kein Polling, keine Latenz.
- Parent kann in der Zwischenzeit andere Arbeit erledigen oder schlafen.
- Ergebnis kommt als `steer`-Item in die Inbox.

### 9.5 Grenzen

- Ergebnis-Größe: große Ergebnisse als `ArtifactRef` in die Inbox, nicht
  inline.
- Reihenfolge: Delivery happens at safe step boundaries.
- Parent muss laufen können, um das Ergebnis zu verarbeiten (oder wird beim
  nächsten Wake geweckt).

---

## 10. Task-Quota pro Session

### 10.1 Was

Begrenzung, wie viele Background-Tasks eine Session gleichzeitig haben darf.

### 10.2 Heute vs. Vorschlag

| Quota | Heute | Vorschlag |
|---|---|---|
| Global (alle Sessions) | 4 (`Engine.java:2249`) | konfigurierbar |
| Pro Session | – | 2 (neu) |
| Pro Parent (Depth) | max. 2 (`DEFAULT_MAX_DEPTH`) | bleibt |
| Pro Task-Queue | – | 100 (neu) |
| Task-Laufzeit (Hard-Limit) | – | konfigurierbar (neu) |

### 10.3 Enforcement

```java
public String create(SessionId parent, String agent, List<String> dependsOn) {
    int running = runningCount(parent);
    if (running >= perSessionQuota) {
        throw new TaskQuotaExceededException(parent, running, perSessionQuota);
    }
    ...
}
```

**Backpressure:** Wenn die Queue voll ist, wird der Aufruf abgelehnt
(`429`/`TaskQuotaExceeded`), nicht blockiert. Blockieren würde den
Execution-Thread belegen.

---

## 11. Umsetzung in QOC: was ist da, was fehlt

| Fähigkeit | Status | Datei |
|---|---|---|
| Background-Task-Registry (in-memory) | ✅ | `BackgroundTasks.java` |
| Virtual-Thread-Execution | ✅ | `SubagentTool.java:183` |
| Batch-Execution (sequenziell) | ✅ | `SubagentTool.executeBatch` |
| Task-Status/Wait/Cancel/List | ✅ | `TaskTool.java` |
| Parent/Child-Tracking | ✅ | `SubagentSessionRegistry` |
| Live-Events (Start/Finish/Cancel) | ✅ | `Engine.java:2404-2454` |
| HTTP-Tasks-Endpoints | ✅ | `HttpApi.java:513,525` |
| Task-Abhängigkeiten (`dependsOn`) | ❌ | neu |
| DAG-Scheduler | ❌ | neu |
| Ergebnis-Interpolation | ❌ | neu |
| Tier-Modell (research/analyze/execute/saga) | ❌ | neu |
| SAGA mit Kompensationen | ❌ | neu |
| Versions-/Schemaversionierung | ❌ | neu |
| Durable Task-Persistenz | ❌ | neu (siehe `Async-use.md`) |
| Cross-Session-Wake bei Task-Ende | ❌ | neu |
| Task-Quota pro Session | ❌ | neu |

---

## 12. Umsetzungsplan (phased)

1. **Task-Abhängigkeiten** (`dependsOn`, DAG-Scheduler, Ergebnis-Interpolation)
2. **Tier-Modell** (research/analyze/execute/saga)
3. **SAGA** mit Kompensationen
4. **Versions-/Schemaversionierung**
5. **Durable Task-Persistenz** (Journal)
6. **Cross-Session-Wake** (Inbox-Delivery)
7. **Task-Quota pro Session**

Jede Phase baut auf der vorherigen auf. Phase 1+2 sind relativ klein und
haben hohen Nutzen. Phase 3 (SAGA) ist die komplexeste und sollte erst nach
Durable Persistenz (Phase 5) implementiert werden.

---

## 13. Beispiel: Research → Analyze → Execute → Saga

```
Task A (research)   → recherchiert Fakten
  └─ Task B (analyze, dependsOn=[A]) → analysiert A's Ergebnis
       └─ Task C (execute, dependsOn=[B]) → implementiert
            └─ Task D (saga, dependsOn=[C]) → Multi-Step mit Kompensation
```

- A: read-only, 5 Steps, kein Durability.
- B: write in Scratch, 10 Steps.
- C: voller Katalog, 20 Steps, durable.
- D: 50 Steps, Kompensationen bei Fehler.

Bei Fehler in C: B und A bleiben (keine Kompensation nötig, da C noch nicht
abgeschlossen). Bei Fehler in D: Kompensationen für D's abgeschlossene Steps,
dann C's Kompensation (falls definiert).

---

## 14. Offene Fragen

1. Soll `dependsOn` auf Task-IDs oder auf Session-IDs verweisen?
2. Soll ein fehlgeschlagener Vorgänger den Nachfolger **blocken** oder
   **abbrechen** (Policy pro Task)?
3. Soll das Tier-Modell hart (Tool-Filter) oder weich (nur maxSteps) sein?
4. Wer definiert SAGA-Kompensationen – der Agent, der User, oder Code?
5. Soll ein Task-Ergebnis als `ArtifactRef` oder als Text in die Inbox?
6. Wie wird mit parallelen Vorgängern (`dependsOn=[A,B]`) umgegangen –
   beide müssen SUCCEEDED sein?
7. Soll es eine globale Task-Queue oder nur per-Session-Registries geben?

## 15. Referenzen

- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/BackgroundTasks.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/TaskTool.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentSessionRegistry.java`
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java` (Subagent-Wiring, Live-Events, Semaphore)
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java` (Tasks-Endpoints)
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Coordinator.java` (Claim, Doorbell)
- [`Async-use.md`](./Async-use.md) (Variante B/C, Heartbeat, Recovery)
- [`Core.md`](./Core.md) (Coordinator, Journal, Inbox)
- [`Storage-alternative.md`](./Storage-alternative.md) (Persistenz, Journal als Source of Truth)