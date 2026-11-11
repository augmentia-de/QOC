# Async-Use – asynchrone Agent-Aufrufe und Heartbeat

> **Zweck:** Detaillierte Analyse und Umsetzungsvarianten für asynchrone
> Agent-Aufrufe in QOC – inklusive Heartbeat, Statusmodell, Recovery,
> API-Oberfläche und Phasenplan.
>
> **Status:** 2026-11-09
>
> **Abgrenzung:** Dieses Dokument beschreibt Optionen und den aktuellen
> Ist-Stand. Es ersetzt keine Spec. Bei Widersprüchen gilt
> [`spec/session-engine/`](../spec/session-engine/README.md), insbesondere
> [`12-execution-model.md`](../spec/session-engine/12-execution-model.md).

## 1. Begriffe: Async auf welcher Ebene?

| Ebene | Bedeutung | Beispiel |
|---|---|---|
| **Tool-Async** | Ein Tool startet Hintergrundarbeit und liefert später ein Ergebnis. | `subagent(background=true)`, langer Build, Web-Scrape |
| **Step-Async** | Innerhalb eines Steps laufen Tool-Calls parallel. | `Step` führt bis zu 8 Tool-Calls gleichzeitig aus |
| **Execution-Async** | Eine Execution läuft auf einer virtuellen Thread; Wakes werden über die Doorbell coalesced. | `Coordinator.wake` |
| **Session-Async** | Mehrere Sessions laufen parallel, ohne sich zu blockieren. | `wake` pro Session |
| **Cross-Session-Async** | Eine Session reagiert auf Ergebnisse einer anderen (Eltern/Kind). | Subagent → Parent-Inbox |
| **Extern-Async** | Trigger von außen (Webhook, E-Mail, A2A, Scheduler). | A2A-Agent, Webhook |

**Leitregel:** Async ist kein Selbstzweck. Der Kern bleibt pro Step
**synchron** (ein Modell-Call); Asynchronität entsteht zwischen Steps,
Executions, Sessions und externen Triggern.

## 2. Ist-Stand in QOC

### 2.1 Background-Subagent (vorhanden)

| Baustein | Datei/Pfad | Verhalten |
|---|---|---|
| `BackgroundTasks` | `engine/.../runtime/tool/subagent/BackgroundTasks.java:22` | In-Memory-Registry pro Session; `create` (`:53`), `attach` (`:60`), `succeed` (`:68`), `fail` (`:80`), `cancel` (`:91`), `await` (`:115`), `closeSession` (`:130`), `closeAll` (`:144`). |
| `SubagentTool.executeBackground` | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java:164` | Startet Subagent auf einer virtuellen Thread (`:183`), gibt sofort `taskId` zurück. |
| `TaskTool` | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/TaskTool.java:32` | `status`/`wait`/`cancel`/`list`; `wait` empfiehlt Timeout ≥ 30 s (`:39`). |
| Engine-Wiring | `quarkus/.../Engine.java:2249` | `MAX_CONCURRENT_SUBAGENTS = 4`, Semaphore (`:2252`), `tryAcquire(5 min)` (`:2370`). |
| Task-Tracking | `Engine.java:2383-2384` | Auch synchrone Subagents werden als Task registriert (`create`/`attach`). |
| Live-Events | `Engine.java:2404`, `:2437`, `:2448`, `:2454` | `SubagentStarted`, `SubagentFinished`, `SubagentCancelled` über `EventBus.publishLive`. |
| HTTP-Surface | `HttpApi.java:513`, `:525` | `GET /api/session/{id}/tasks`, `POST /api/session/{id}/tasks/{taskId}/cancel`. |
| Tool-Timeout-Ausnahme | `ToolRuntime.java:98-99` | `subagent`/`task` sind von Step-Timeout ausgenommen (`longRunning`). |

### 2.2 Weitere Async-Mechanismen

| Mechanismus | Ort | Anmerkung |
|---|---|---|
| **Tool-Parallelität im Step** | `Step.java:35` (`MAX_PARALLEL_TOOLS=8`) | `question` wird bewusst sequenziell ausgeführt (`Step.java:128-131`). |
| **Execution-Doorbell** | `Coordinator.java:40-96` | Ein Wake pro Session; Coalescing; Follow-up-Execution. |
| **ExecutionRunner** | `ExecutionRunner.java` | Interface vorhanden; Drain-Loop im Kern noch nicht portiert. |
| **SessionWake** | `core/port/runtime/SessionWake.java` | Entkopplung Admission → Coordinator. |
| **A2A** | `quarkus/.../http/a2a/*` | Agent-zu-Agent-Protokoll, eigener Auth-Mechanismus. |
| **MCP** | `engine/.../runtime/tool/mcp/*` | Externe Tool-Server (JSON-RPC). |
| **SSE** | `HttpApi.java:304` (`/event`), `:326` (`/events`) | Live-Event-Stream; UI nutzt `fetch`-basiertes SSE (`index.html:1622`). |
| **UI-Orchestrator-Heartbeat** | `index.html:611` (`initOrchestratorHeartbeat`) | `BroadcastChannel('qoc-orchestrator')`; Parent/Subagent-View; 2 s Ping, 10 s Timeout (`:636`). |
| **UI-Polling** | `index.html:1181` (`checkHealth`), `:1331` (`taskPoll`), `:1750`/`:1752` (`permPoll`/`questionPoll`, 700 ms), `:1042` (`startQuestionsPoll`, 5 s) | Fallback, wo kein SSE genutzt wird. |

### 2.3 Bekannte Lücken

1. **Nicht durable:** `BackgroundTasks` ist in-memory; ein Neustart verliert
   alle laufenden Tasks und Ergebnisse (`BackgroundTasks.java:19-20`).
2. **Kein Server-Heartbeat:** Es gibt Live-Events zu Start/Ende, aber kein
   periodisches „Execution lebt noch"-Signal.
3. **Kein Task-Progress:** Langläufige Tools/Subagents melden keinen
   Zwischenstand.
4. **Kein Cross-Session-Wake bei Task-Ende:** Der Parent muss pollen
   (`task wait` oder UI-Polling), statt über die Inbox geweckt zu werden.
5. **Kein Scheduler/Webhook-Trigger:** Externe Ereignisse können keine
   Session starten (Webhook-Doku vorhanden, aber nicht als Async-Trigger
   verdrahtet).
6. **Kein Quota pro Session:** Nur das globale Subagent-Limit (4).

## 3. Wo async sinnvoll ist

| Szenario | Async-Ebene | Nutzen | Risiko |
|---|---|---|---|
| **Subagent-Delegation** (Research, Review) | Tool-/Cross-Session | Parent arbeitet weiter | verwaiste Tasks bei Restart |
| **Langläufige Tools** (Build, Test, Migration) | Tool | kein Step-Timeout, UI bleibt bedienbar | Ergebnis-Zustellung komplex |
| **A2A-Remote-Agents** | Extern/Cross-Session | andere Prozesse/Hosts | Auth, Netzwerk, Idempotenz |
| **MCP-Tool-Server** | Tool | I/O-Parallelität | Server-Ausfall |
| **Compaction/Title-Gen** | Execution-intern | nicht blockierend | – (bereits async) |
| **Webhooks/E-Mail-Trigger** | Extern | Agent reagiert auf Ereignisse | Auth, Replay, Duplikate |
| **Scheduled Runs** (Cron) | Extern | periodische Aufgaben | Überlappung, Claims |
| **Permission/Question-Wait** | Execution | blockiert nur die Session, nicht den Server | hängende Approvals |

## 4. Wo async NICHT sinnvoll ist

| Bereich | Grund |
|---|---|
| **Permission-Check** | Muss vor dem Side-Effect entscheiden (synchron, Deny-first). |
| **Tool-Input-Decode/Validierung** | Fail-fast, keine Nebenwirkungen. |
| **Admission/Delivery** | Transaktional und pro Session serialisiert (Inbox-Semantik). |
| **`ToolCalled`-Publikation** | G-03: vor dem Seiteneffekt durable. |
| **Auto-Compaction vor dem Request** | Muss vor dem Modell-Call abgeschlossen sein. |
| **Claim/Release** | Atomar; kein Fire-and-forget. |

## 5. Umsetzungsvarianten

### Variante A – In-Memory (Status quo, ausbauen)

**Idee:** `BackgroundTasks` bleibt die Registry; Ergänzung um Heartbeat und
Progress-Events, keine Persistenz.

| Pro | Contra |
|---|---|
| Kein Storage-Aufwand | Verlust bei Neustart |
| Einfach, schnell | Kein Multi-Node |
| Passt für kurzlebige Tasks | Kein Audit/Replay |

**Geeignet für:** UI-nahe, kurzlebige Subagents; Entwicklung.

### Variante B – Durable Task im Journal (empfohlen)

**Idee:** Jeder Background-Task wird als Aggregat im `Journal` geführt:
`TaskStarted`, `TaskHeartbeat`, `TaskProgress`, `TaskSucceeded`,
`TaskFailed`, `TaskCancelled`. Der Task-Zustand ist aus den Events
rekonstruierbar; `BackgroundTasks` wird zum Read-Model.

| Pro | Contra |
|---|---|
| Überlebt Neustart | mehr Schreiblast |
| Auditierbar | Recovery-Logik nötig |
| Passt zu Event-Sourcing | Task-IDs als Aggregate |
| Kein neuer Storage | – |

**Bausteine:**

```
TaskStarted   { taskId, parentSession, agent, promptHash, created }
TaskHeartbeat { taskId, at, phase }
TaskProgress  { taskId, at, message, percent? }
TaskSucceeded { taskId, at, childSession, text, usage }
TaskFailed    { taskId, at, error }
TaskCancelled { taskId, at, reason }
```

**Recovery:** Beim Start `TaskStarted` ohne Terminal-Event finden →
Status `UNKNOWN`/`STALLED`; je nach Policy erneut starten (idempotent) oder
als `FAILED(orphaned)` markieren.

### Variante C – Event-Driven über Inbox/Delivery

**Idee:** Das Task-Ergebnis wird als Inbox-Item (`steer`) in die
Parent-Session zugestellt. Der Parent wird geweckt, statt zu pollen.

| Pro | Contra |
|---|---|
| Passt zur bestehenden Inbox-/Delivery-Semantik | Task-Ergebnis muss als Nachricht modelliert werden |
| Kein Polling | Reihenfolge/Delivery-Modus beachten |
| Parent kann weiterarbeiten | Größe des Ergebnisses begrenzen (ArtifactRef) |

**Ablauf:**

```
Parent ── subagent(background) ──► TaskStarted
                                   │
                                   ▼
                            Child-Session läuft
                                   │
                     TaskSucceeded + InboxEnqueued(parent, result)
                                   │
                                   ▼
                     Parent-wake → Delivery → Step
```

### Variante D – Externe Queue (Multi-Node)

**Idee:** Tasks in Redis Streams, NATS JetStream oder SQS; Worker-Prozesse
konsumieren; Lease/Heartbeat im Queue-System.

| Pro | Contra |
|---|---|
| Skalierbar, multi-node | Betriebsaufwand |
| Retry/DLQ eingebaut | neue Infrastruktur |
| Entkopplung | Konsistenz mit Journal nötig |

**Erst sinnvoll, wenn** mehrere Instanzen oder hohe Task-Last bestehen.

### Variante E – Scheduler/Webhook-Trigger (extern)

**Idee:** Ein externer Trigger (Cron, Webhook, E-Mail) ruft
`Admission.admit(...)` + `wake(...)` auf; die Session läuft wie ein normaler
Prompt.

| Pro | Contra |
|---|---|
| Wiederverwendung der Admission-Pipeline | Auth/Replay-Schutz nötig |
| Kein neuer Ausführungspfad | Duplikate → Idempotency-Key |

## 6. Empfohlene Zielarchitektur

**Kombination: B + C + Heartbeat + SSE.**

```
┌────────────────────────────────────────────────────────────┐
│ Parent-Session                                             │
│  subagent(background=true)                                 │
│    └─ TaskService.start(...)                               │
│         ├─ Journal.append(TaskStarted)                     │
│         ├─ TaskRunner.submit(virtual thread)               │
│         └─ return taskId                                   │
│                                                            │
│  TaskRunner                                                │
│    ├─ HeartbeatPublisher (alle 5 s)                        │
│    ├─ Child-Session (runPrompt)                            │
│    ├─ Journal.append(TaskSucceeded/Failed)                 │
│    ├─ InboxEnqueued(parent, result)   ◄─ Variante C        │
│    └─ SessionWake.wake(parent)                             │
│                                                            │
│  Parent-Step                                               │
│    └─ Delivery → neuer Step mit Subagent-Ergebnis          │
└────────────────────────────────────────────────────────────┘
```

**Neue/erweiterte Bausteine:**

| Baustein | Typ | Aufgabe |
|---|---|---|
| `TaskService` | Runtime | Start/Stop/Status von Background-Tasks; nutzt `Journal` + `TaskRunner`. |
| `BackgroundTaskStore` | Port (optional) | Persistenz/Query der Tasks; kann auf `Journal`/`SessionStore` aufsetzen. |
| `TaskRunner` | Runtime | Virtuelle Threads, Quota, Interrupt, Heartbeat. |
| `HeartbeatPublisher` | Runtime | Periodische `TaskHeartbeat`/`ExecutionHeartbeat`-Events. |
| `TaskStatus` | Model | `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `STALLED`, `ORPHANED`. |
| `TaskResultDelivery` | Runtime | Ergebnis als Inbox-Item in den Parent. |

## 7. Heartbeat-Design

### 7.1 Heartbeat-Arten

| Art | Zweck | Intervall | Timeout | Event |
|---|---|---|---|---|
| **Execution-Heartbeat** | Läuft die Execution noch? | 10–30 s | 3× Intervall | `ExecutionHeartbeat` |
| **Task-Heartbeat** | Läuft der Background-Subagent? | 5–15 s | 3× Intervall | `TaskHeartbeat` |
| **Tool-Progress** | Fortschritt eines langen Tools | ereignisbasiert | – | `ToolProgress` |
| **Worker-Lease** | Multi-Node: Instanz hält Session | 10 s | 30 s (Lease-TTL) | `ClaimRenewed` |
| **Provider-Stream** | LLM-Stream hängt | SSE-Timeout | Konfiguration | `ProviderTimeout` |

### 7.2 Schwellen und Aktionen

| Zustand | Bedingung | Aktion |
|---|---|---|
| `RUNNING` | Heartbeat < 2× Intervall | normal |
| `SUSPECT` | Heartbeat > 2× Intervall | UI-Warnung, Metrik |
| `STALLED` | Heartbeat > 3× Intervall | Warnung + optional Interrupt |
| `ORPHANED` | Neustart, kein Terminal-Event | Recovery oder `FAILED(orphaned)` |
| `CANCELLED` | User/Shutdown | Interrupt + Terminal-Event |

### 7.3 UI-Verhalten (bestehend erweitern)

- **Orchestrator-Heartbeat** (`index.html:611`): bleibt für Parent/Subagent-View.
- **Task-Heartbeat** über SSE: ersetzt `taskPoll` (`index.html:1331`)
  schrittweise; Polling bleibt Fallback.
- **Stalled-Banner**: analog `updateFallbackBanner` (`index.html:636`).
- **Heartbeat-Indikator**: Punkt/Zeitstempel pro Task in der Task-Liste.

### 7.4 Heartbeat und Claims

- Ein Heartbeat verlängert **keine** Claim-Lease automatisch; das ist
  explizit Aufgabe von `ClaimStore.renew` (neu) bzw. der Worker-Lease.
- Bei `STALLED` darf **nicht** automatisch eine zweite Execution gestartet
  werden (Claim bleibt). Erst Recovery entscheidet.

## 8. API-Oberfläche

### 8.1 Tools

| Tool | Aktion | Status |
|---|---|---|
| `subagent` | `background=true` startet Task | ✅ vorhanden |
| `task` | `status`, `wait`, `cancel`, `list` | ✅ vorhanden |
| `task` (neu) | `progress`, `heartbeat` | ❌ geplant |

### 8.2 HTTP

| Methode | Pfad | Status |
|---|---|---|
| `GET` | `/api/session/{id}/tasks` | ✅ |
| `POST` | `/api/session/{id}/tasks/{taskId}/cancel` | ✅ |
| `GET` | `/api/session/{id}/tasks/{taskId}` | ❌ optional |
| `GET` | `/api/session/{id}/tasks/{taskId}/events` | ❌ optional (SSE) |

### 8.3 SSE-Events (Vorschlag)

```
TaskStarted   { taskId, parent, agent, childSession }
TaskHeartbeat { taskId, at, phase, progress? }
TaskProgress  { taskId, at, message, percent? }
TaskSucceeded { taskId, at, text, usage }
TaskFailed    { taskId, at, error }
TaskCancelled { taskId, at, reason }
ExecutionHeartbeat { session, at, step }
```

Bestehende Events (`SubagentStarted/Finished/Cancelled`,
`Engine.java:2404/2437/2448/2454`) können beibehalten oder in das neue
Schema überführt werden.

## 9. Failure- und Recovery-Semantik

| Fall | Verhalten |
|---|---|
| **Prozess-Crash während Task** | `TaskStarted` ohne Terminal → `ORPHANED`; Recovery entscheidet: neu starten (idempotent) oder `FAILED(orphaned)`. |
| **User-Cancel** | Interrupt + `TaskCancelled` + Claim/Child aufräumen. |
| **Shutdown** | Interrupt, aber Claim der Parent-Session bleibt (X-7); Tasks werden als `STALLED` markiert. |
| **Doppeltes Ergebnis** | Task-ID + Idempotenz: nur erstes Terminal-Event zählt. |
| **Child-Session weg** | `TaskFailed(orphaned)`; Parent wird informiert. |
| **Ergebnis zu groß** | Nur `ArtifactRef` in die Inbox; Volltext im `ToolOutputStore`. |
| **Parent beendet** | Task wird gecancelt (`closeSession`, `BackgroundTasks.java:130`) oder läuft als eigenständige Session weiter (Policy). |

**At-least-once:** Async-Tasks erben die Recovery-Semantik des Kerns.
Tools/Side-Effects müssen idempotent sein oder über `IdempotencyKey`
dedupliziert werden.

## 10. Quotas und Backpressure

| Limit | Vorschlag | Ort |
|---|---|---|
| Globale Subagent-Concurrency | 4 (aktuell), konfigurierbar | `Engine.java:2249` |
| Pro Session | z. B. 2–4 Background-Tasks | neu, `TaskService` |
| Pro Parent | max. Depth 2 (aktuell) | `SubagentTool.DEFAULT_MAX_DEPTH` |
| Task-Queue | bounded (z. B. 100), Ablehnung mit Fehler | `TaskService` |
| Task-Laufzeit | optionales Hard-Limit | `TaskRunner` |
| Heartbeat-Frequenz | 5–15 s, drosselbar | `HeartbeatPublisher` |

Backpressure entsteht **in der Inbox/Queue**, nicht in Threads.

## 11. Observability

| Metrik | Frage |
|---|---|
| `tasks_active` | Wie viele Background-Tasks laufen? |
| `tasks_by_state` | Verteilung `RUNNING`/`STALLED`/`FAILED`? |
| `task_duration` | Wie lange dauern Tasks? |
| `heartbeat_age` | Wie alt ist der letzte Heartbeat? |
| `tasks_orphaned_total` | Wie oft Recovery? |
| `task_cancel_total` | Cancels pro Grund |
| `subagent_slots_used` | Auslastung des Semaphors |

Logs: Task-ID und Parent-Session-ID in jeder Zeile.

## 12. Security und Permissions

- `subagent(background=true)` durchläuft denselben `PermissionGate`
  (`SubagentTool.java:170-180`).
- Task-Status/-Cancel nur für die eigene Parent-Session
  (`BackgroundTasks.entry` prüft `parent`, `:156-160`).
- HTTP-Endpunkte erben die OIDC/Basic-Auth des API-Skins.
- Ergebnisse können sensible Daten enthalten → gleiche Policy wie normale
  Tool-Outputs (Retention, Redaction).

## 13. Phasenplan

### Phase 1 – Heartbeat (P0, klein)

1. `HeartbeatPublisher` im `Engine.prompt`/`TaskRunner` (10–30 s).
2. SSE-Event `ExecutionHeartbeat`/`TaskHeartbeat` über `EventBus.publishLive`.
3. UI: Heartbeat-Anzeige + Stalled-Banner; Polling bleibt Fallback.
4. Tests: Heartbeat erscheint, Timeout markiert `SUSPECT`/`STALLED`.

### Phase 2 – Durable Tasks (P0/P1)

5. `TaskStarted`/`TaskHeartbeat`/`TaskSucceeded`/`TaskFailed`/`TaskCancelled`
   im `Journal`.
6. `BackgroundTasks` als Read-Model aus dem Journal; Recovery beim Start.
7. `TaskService` als zentrale API (start/status/cancel/list).
8. Tests: Neustart mitten im Task, Orphan-Erkennung, Cancel.

### Phase 3 – Ergebnis-Delivery (P1)

9. `TaskSucceeded` → `InboxEnqueued(parent, result)` + `SessionWake.wake`.
10. Parent-Step konsumiert das Ergebnis als Steer (kein Polling nötig).
11. Größenbegrenzung/ArtifactRef.

### Phase 4 – Tool-Async (P2)

12. `ToolRuntime`-Pfad für langläufige Tools: sofort `taskId`, Ergebnis via Inbox.
13. `ToolProgress`-Events.
14. Timeout-/Cancel-Semantik für externe Tools (MCP/A2A).

### Phase 5 – Externe Trigger/Multi-Node (P2)

15. Webhook/Scheduler → `Admission` + `wake`.
16. Optional externe Queue (Redis/NATS/SQS) und Worker-Lease.

## 14. Teststrategie

| Test | Prüft |
|---|---|
| Unit: `BackgroundTasks`-Zustandsmaschine | `RUNNING` → `SUCCEEDED`/`FAILED`/`CANCELLED`; late result ignoriert |
| Unit: Heartbeat | Intervall, Timeout, `SUSPECT`/`STALLED` |
| Integration: Restart | `TaskStarted` ohne Terminal → `ORPHANED`/Recovery |
| Integration: Cancel | Interrupt, Terminal-Event, Child-Cleanup |
| E2E: Parent wartet nicht | Parent-Step läuft weiter; Ergebnis kommt als Steer |
| E2E: UI | SSE-Heartbeat sichtbar; Polling-Fallback |
| Last: Quota | Max. Concurrency wird nie überschritten |

## 15. Offene Fragen

1. Soll ein Background-Task eine **eigene Session** sein (wie heute) oder ein
   Task-Aggregat ohne Session?
2. Soll der Parent bei Task-Ende **automatisch geweckt** werden (Variante C)
   oder explizit `task wait` aufrufen?
3. Wie lange sollen Task-Events/Ergebnisse aufbewahrt werden?
4. Soll ein `STALLED`-Task automatisch abgebrochen werden oder nur markiert?
5. Braucht es eine Task-Priorität/Queue-Reihenfolge?
6. Sollen Webhooks/Scheduler in Phase 1–5 oder separat betrachtet werden?

## 16. Referenzen (Code)

| Thema | Pfad:Zeile |
|---|---|
| `BackgroundTasks` | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/BackgroundTasks.java:22` |
| Background-Start | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java:164`, virtueller Thread `:183` |
| Task-Tool | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/TaskTool.java:32`, `:39` |
| Subagent-Limit | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java:2249`, `:2370` |
| Task-Registrierung | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java:2383` |
| Live-Events | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java:2404`, `:2437`, `:2448`, `:2454` |
| HTTP-Tasks | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java:513`, `:525` |
| Tool-Timeout-Ausnahme | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/runtime/ToolRuntime.java:98` |
| Step-Parallelität | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/step/Step.java:35`, `:128` |
| Coordinator/Doorbell | `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Coordinator.java:40` |
| SSE-Endpunkte | `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java:304`, `:326` |
| UI-Heartbeat | `qoc/quarkus/src/main/resources/META-INF/resources/index.html:611`, `:636` |
| UI-Polling | `qoc/quarkus/src/main/resources/META-INF/resources/index.html:1181`, `:1331`, `:1750`, `:1752`, `:1042` |
| SSE-Client | `qoc/quarkus/src/main/resources/META-INF/resources/index.html:1622`, `:1629`, `:1671` |
