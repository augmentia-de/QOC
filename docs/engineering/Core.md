# Core – der erweiterte Agent-Loop-Kern

> **Zweck:** Synthese und Nachschlagewerk für den *erweiterten Core* des
> Agent-Loops: Umfang, Komponenten, Ports, Invarianten, Ist-Stand der
> Java-Portierung und die Extraktionsgrenze zum Outer Skin.
>
> **Verhältnis zur Spec:** Dieses Dokument fasst zusammen und ordnet ein.
> Bei Widersprüchen gilt die Spec unter
> [`spec/session-engine/`](../spec/session-engine/README.md), insbesondere
> [`12-execution-model.md`](../spec/session-engine/12-execution-model.md) und
> [`04-algorithms.md`](../spec/session-engine/04-algorithms.md).
>
> **Stand:** 2026-11-09

## 1. Begriffe

| Begriff | Bedeutung |
|---|---|
| **Execution** | Ein „busy period" pro Session: Claim → Drain(s) → genau ein Terminal-Event. |
| **Drain** | Ein Durchlauf des Runners: solange Schritte ausführen, bis kein Input mehr da ist oder ein Kontroll-Element (Move/Compaction) die Grenze bildet. |
| **Step** | Genau ein Modell-Call plus die daraus folgenden Tool-Ausführungen. |
| **Attempt** | Ein einzelner Modell-Call innerhalb eines Steps (Retry/Continuation/Compaction erzeugen mehrere Attempts pro Step). |
| **Turn** | Fachlich: ein Prompt des Nutzers bis zur fertigen Antwort; technisch mehrere Steps. |
| **Minimaler Kern** | Ein einzelner Step: Kontext bauen → Modell rufen → Tools ausführen → Ergebnis. |
| **Erweiterter Kern** | Minimaler Kern **plus** Execution-Lifecycle: Admission, Delivery, Coordinator, Multi-Step-Drain, Compaction, Recovery. |

## 2. Umfang des erweiterten Cores

### 2.1 Minimaler Kern (A)

| Baustein | Rolle |
|---|---|
| **Step** | Ein Modell-Call; Stream konsumieren, Tool-Calls parallel ausführen, Ergebnis normalisieren. |
| **ContextBuilder** | Agent, Tools, Instructions, History und Modell zu einem `Prepared`-Request zusammenbauen. |
| **ProviderAdapter** | LLM-Schnittstelle (Streaming, Modell-Metadaten, Fehlerklassifikation). |
| **ToolRuntime / ToolAuthorizer** | Tool-Snapshot, Pre/Post-Hooks, Ausführung, Autorisierung am Leaf. |
| **PermissionEngine** | Regelkette: Deny → Allow → Ask; gespeicherte Approvals; Hook. |
| **CompactionService** | Auto-Compaction vor einem Request, wenn das Kontextfenster vollläuft. |
| **Resilienz** | `RetryPolicy`, `LoopDetector`, `ErrorClassifier`, `TriageService`, `PreflightChecker`. |

### 2.2 Erweiterter Kern (A + B)

| Baustein | Rolle |
|---|---|
| **Coordinator** | Genau eine Execution pro Session, Claim (Write-Ahead), Doorbell (Coalescing), Terminal-Event. |
| **ExecutionRunner / Drain** | Die eigentliche Schleife: `reach_boundary` → `run_step` → nächster Step, bis `Done`/`Moved`/`Reloaded`. |
| **Admission** | Idempotentes Enqueue in die Inbox, Hooks/Attachments, danach `wake`. |
| **Delivery** | Steers/Queue an sicheren Grenzen zustellen; nur Zugestelltes ist modell-sichtbar. |
| **Revert** | Offene Reverts vor neuer Arbeit committen. |
| **Recovery** | Claims nach Prozess-Abbruch einsammeln, Resume-Budget (max. 10), Synthetic-Message. |
| **SessionService / Retention** | Session-Lifecycle und Aufräumen. |
| **Ports** | Stores, Clock, Approval/Question/Policy-Quellen, Wake, Notification. |

## 3. Komponenten und Verantwortlichkeiten

### 3.1 Orchestrierung

| Klasse | Paket | Verantwortung |
|---|---|---|
| `Coordinator` | `runtime.execution` | `wake`/`interrupt`/`shutdown`; Claim erwerben; genau ein Terminal-Event; Follow-up-Execution über Doorbell. |
| `ExecutionRunner` | `runtime.execution` | Schnittstelle `run(session, execution, scope) → ExecutionOutcome`. **Die Drain-Logik lebt hier.** |
| `ExecutionScope` | `runtime.execution` | Scope-Merge (`Input` dominiert `Control`). |
| `SessionExecution` | `runtime.execution` | Laufzeit-Zustand einer Execution: Doorbell, Stop-Flag, Interrupt-Grund. |
| `ExecutionOutcome` | `runtime.execution` | `Succeeded` / `Failed` / `Interrupted`. |
| `Recovery` | `runtime.execution` | Offene Claims nach Neustart; Resume-Zähler; Synthetic-Message. |
| `Step` | `runtime.step` | Ein Modell-Call; Tool-Calls parallel (max. 8, FIFO); `Finished`/`Failed`/`NeedsCompaction`/`Paused`. |
| `StepResult` | `runtime.step` | Ergebnis-Typen eines Steps (`Finished`, `Failed`, `NeedsCompaction`, `Paused`). |

### 3.2 Kontext und Request

| Klasse | Paket | Verantwortung |
|---|---|---|
| `ContextBuilder` | `runtime.context` | `prepare(...)`: System-Prompt, Instructions, History, Tools, Tool-Choice, Header, Cache-Key. |
| `Prepared` | `runtime.context` | Eingefrorener Request für genau einen Step; `toRequest()`. |
| `AgentProfile` | `runtime.context` | Agent-Konfiguration (System, `maxSteps`, Permissions, Plan-Mode, Ask-Mode). |
| `ContextSize`, `ConversationFolding`, `TitleGenerator` | `runtime.context` | Kontextgröße, Folding, Titel-Generierung. |
| `ModelRequest`, `ModelMessage`, `GenerationOptions` | `core.contract.provider` | Provider-neutrales Request-Modell. |

### 3.3 Modell und Tools

| Klasse | Paket | Verantwortung |
|---|---|---|
| `HttpProviderAdapter` | `runtime.provider.client` | HTTP/SSE-Adapter, Event-Übersetzung, Stream-Validierung. |
| `OpenAiRequestEncoder`, `JsonEventTranslator`, `SseReader` | `runtime.provider.client` | OpenAI-kompatible Serialisierung/Deserialisierung. |
| `ToolRegistry` | `runtime.tool.registry` | Registrierung von Tool-Definitionen. |
| `ToolRuntime` | `runtime.tool.runtime` | Tool-Ausführung: Hooks, Decode, Authorizer, PermissionGate, Output-Normalisierung. |
| `ToolAuthorizer` | `runtime.tool.runtime` | Entscheidet, ob ein Tool im aktuellen Snapshot ausführbar ist. |
| `ToolCall`, `ToolOutcome`, `ToolDefinition`, `ToolContext` | `core.contract.tool` | Domänen-Typen der Tool-Schicht. |

### 3.4 Permissions

| Klasse | Paket | Verantwortung |
|---|---|---|
| `PermissionEngine` | `runtime.permission.engine` | `decide`/`assertAllowed`/`reply`; Ask-Mode; gespeicherte Approvals. |
| `PendingApprovals` | `runtime.permission.engine` | Offene Approval-Anfragen pro Session. |
| `PermissionCheck`, `PermissionDecision`, `PermissionRules` | `core.eval` | Regel-Auswertung, Deny-vor-Allow, Ask-Dominanz. |
| `ApprovalStore`, `ApprovalSource` | `core.port.store` / `core.port.human` | Persistenz und menschliche Antwortquelle. |

### 3.5 Compaction

| Klasse | Paket | Verantwortung |
|---|---|---|
| `CompactionService` | `runtime.compaction` | Summary erzeugen, Epoch setzen, Journal-Events. |
| `CompactionPolicy`, `CompactionWindow`, `CompactionContext` | `runtime.compaction` | Wann/wie viel zusammengefasst wird. |
| `OverflowCompaction` | `core.eval` | Erkennung „Kontextüberlauf → Compaction möglich". |

### 3.6 Admission und Delivery

| Klasse | Paket | Verantwortung |
|---|---|---|
| `Admission` | `runtime.session.core` | Idempotentes Enqueue, Hooks/Attachments, `wake`. |
| `AdmissionResult` | `runtime.session.core` | `admitted`, `new`, Fehlerfälle. |
| `Delivery` | `runtime.session.delivery` | Steers vor Queue, Control-Elemente zuerst; macht Input sichtbar. |
| `Revert` | `runtime.session.revert` | Offene Reverts vor neuem Input committen. |
| `SessionService`, `SessionRetention` | `runtime.session.core` | Lifecycle, Retention. |

### 3.7 Cross-Cutting

| Typ | Paket | Rolle |
|---|---|---|
| `EventBus`, `Journal`, `PayloadCodec` | `core.event` / `core.port.store` | Ereignisse, Durable Log, Serialisierung. |
| `Clock` | `core.port.runtime` | Zeitquelle (testbar). |
| `SessionWake` | `core.port.runtime` | Entkopplung Admission → Coordinator. |
| `ApprovalSource`, `QuestionSource`, `PolicySource`, `NotificationChannel` | `core.port.human` | Menschliche Interaktion. |
| `Json`, `JsonValue`, `Schema` | `core.json` | Wertmodell und Schema. |

## 4. Ablauf des erweiterten Cores

### 4.1 Execution (Coordinator)

```
wake(session, scope)
  ├─ läuft bereits?  → Doorbell := merge(Doorbell, scope); return
  └─ sonst: Claim erwerben
       ├─ Claim leer → Ende
       └─ ExecutionStarted publizieren
            → runner.run(session, execution, scope)   ← Drain
            → Terminal-Event (Succeeded/Failed/Interrupted)
            → Claim freigeben (außer Shutdown)
            → Doorbell gesetzt? → wake(...) (Follow-up)
```

### 4.2 Drain (Soll-Zustand laut Spec)

```
drain(session, scope, continuation)
  plugins.awaitActivation
  cleanOrphanedCompactions / cleanOrphanedToolCalls
  step := continuation.step ?? 1
  while true:
      r := reachBoundary(session, scope, step, entering, continuation)
      if r != Ready: return r
      continuation := r.continuation
      done := runStep(r.context, step)
      step += 1
      entering := false
```

`reachBoundary` entscheidet über:
- **Control-Elemente** (Compaction/Move) → Vorrang, ggf. neuer Kontext.
- **Delivery** (Steers sofort, Queue erst an ruhiger Grenze).
- **Abbruch**, wenn nichts mehr da ist (`Done`).
- **Step-Reset** bei neuem Input.

### 4.3 Step (implementiert in `Step`)

```
runStep(prepared, context)
  events := provider.stream(prepared.toRequest())
  toolCalls := []
  for e in events:
      publish(e)
      if e = ToolCallRequested: toolCalls += e
      if e = Finished: finish, usage
      if e = ProviderFailed:
          needsCompaction? → NeedsCompaction
          sonst → Failed
  if finish == null → Failed(INCOMPLETE_STREAM)
  if toolsDisabled && ToolCallLeak → Failed
  if toolsDisabled && toolCalls nicht leer → alle Calls Failed
  if hasQuestion → sequenziell ausführen
  else → parallel (max. 8) via ExecutorService
  return Finished(finish, usage, events, outcomes)
```

### 4.4 Attempt-/Retry-Grenze

- **Ein Step = genau ein Modell-Call** (G-06). Der nächste Step liest die History neu.
- **Retry** (gleicher Assistant-Message-ID) bei transienten Fehlern vor Output.
- **Continuation** bei Fehlern nach Output (Synthetic-Message, neue ID).
- **Compaction** bei Kontextüberlauf (neue ID, neuer Kontext).
- **OutputLimit** → Synthetic-Message „Split the work", neue ID.
- **Paused** (Approval/Question) beendet den Step typisiert und wird später fortgesetzt.

## 5. Ports und Adapter (hexagonale Grenze)

Der Kern kennt **nur Interfaces**. Konkrete Technik lebt in `engine.adapter.*`
und im Quarkus-Skin.

| Port | Paket | Adapter (Beispiele) |
|---|---|---|
| `SessionStore`, `SessionReader` | `core.port.store` | `adapter.sql.SqlSessions`, `adapter.sqlite.SqliteSessions` |
| `ConversationStore`, `Conversation` | `core.port.store` | `adapter.sql.SqlConversation` |
| `Journal` | `core.port.store` | `adapter.sql.SqlJournal`, `adapter.sqlite.SqliteJournal` |
| `ClaimStore` | `core.port.store` | SQL/SQLite-Implementierungen |
| `SnapshotStore` | `core.port.store` | `adapter.sql.SqlSnapshots` |
| `ApprovalStore` | `core.port.store` | `adapter.sql.SqlApprovals` |
| `ToolOutputStore`, `ToolResultCache` | `core.port.store` | SQL/SQLite |
| `InstructionStore` | `core.port.store` | SQL/SQLite |
| `CredentialStore` | `core.port.store` | SQL/SQLite |
| `FileSystem` | `core.port.world` | `adapter.fs.NioFileSystem`, `CachedFileSystem` |
| `ProcessRunner` | `core.port.world` | `adapter.process.JdkProcessRunner`, `SandboxedProcessRunner` |
| `RemoteShell` | `core.port.world` | `adapter.ssh.MinaSshRemoteShell` |
| `HttpTransport` | `core.port.world` | `adapter.net.JdkHttpTransport` |
| `InstructionLoader` | `core.port.world` | `adapter.fs.NioInstructionLoader` |
| `Clock` | `core.port.runtime` | `adapter.time` (System-Clock, Test-Clock) |
| `SessionWake` | `core.port.runtime` | Coordinator-Wake |
| `ApprovalSource`, `QuestionSource`, `PolicySource` | `core.port.human` | HTTP/UI-Adapter |
| `ProviderAdapter` | `core.contract.provider` | `runtime.provider.client.HttpProviderAdapter` |

**Regel:** `engine.core.*` bleibt frei von Quarkus, Jackson, CDI, HTTP und
Datenbanktreibern. Alles Technische gehört in `engine.adapter.*` oder in den
Quarkus-Skin.

## 6. Invarianten

### 6.1 Execution (X-Reihe)

| # | Invariante |
|---|---|
| X-1 | Höchstens eine aktive Execution pro Session; weitere Wakes setzen genau **eine** Doorbell. |
| X-2 | Eine Execution = eine virtuelle Thread. |
| X-3 | Tool-Calls eines Steps laufen parallel, begrenzt durch ein Quota (Default 8, konfigurierbar 1–32, FIFO). |
| X-4 | Kein blockierendes I/O unter einem Lock; `ReentrantLock`, kritischer Abschnitt nur State-Change. |
| X-5 | Kein `ThreadLocal` für Session-Kontext; Session-ID wird durchgereicht. |
| X-6 | Abort ist Interrupt **plus** eigener Kanal; nie als Fehler reinterpretiert. |
| X-7 | Shutdown lässt den Claim stehen (Recovery); User-Abbruch gibt ihn frei. |
| X-8 | Wartende Sessions belegen keinen Thread (Inbox). |

### 6.2 Ordnung (G-Reihe)

| # | Garantie |
|---|---|
| G-01 | Admission vor Execution: Inbox-Zeile existiert, bevor Arbeit geplant wird. |
| G-02 | Delivery vor Sichtbarkeit: nur `InboxDelivered` macht Input modell-sichtbar. |
| G-03 | `ToolCalled` vor dem Seiteneffekt. |
| G-04 | Terminal-Event nach dem Seiteneffekt. |
| G-05 | Tool-Calls parallel, **Publikation** in Call-Reihenfolge. |
| G-06 | Ein Step = ein Modell-Call; der nächste Step lädt die History neu. |
| G-07 | Terminal-Events nach dem Cleanup (bei Interrupt sofortige Bestätigung). |
| G-08 | Interrupt beendet laufende Tool-Calls, ohne geparkte Inputs zu verwerfen. |

### 6.3 Recovery

- Recovery ist **at-least-once**: Sie verhindert parallele Runs, nicht
  wiederholte Seiteneffekte nach einem Abbruch.
- Resume-Budget: **max. 10 Versuche** pro Session; danach
  `ExecutionFailed` und Claim-Freigabe.
- Vor dem Resume wird `SyntheticRaised` („Server wurde neu gestartet")
  publiziert.

## 7. Ist-Stand der Java-Portierung

### 7.1 Vorhanden (engine-Modul, reines Java)

| Bereich | Status |
|---|---|
| `Step` (ein Modell-Call, parallele Tools, Paused/Compaction/Failed) | ✅ implementiert |
| `ContextBuilder` + `Prepared` | ✅ implementiert |
| `ToolRuntime`, `ToolRegistry`, `ToolAuthorizer` | ✅ implementiert |
| `PermissionEngine`, `PendingApprovals` | ✅ implementiert |
| `CompactionService` | ✅ implementiert |
| `Admission`, `Delivery`, `Revert` | ✅ implementiert |
| `Coordinator`, `Recovery`, `SessionExecution` | ✅ implementiert |
| Ports (`core.port.*`) + SQL/SQLite/FS/Process/SSH-Adapter | ✅ implementiert |
| Provider-Client (`HttpProviderAdapter`) | ✅ implementiert |
| `TurnPolicy` | ⚠️ definiert, aber **nicht verwendet** |

### 7.2 Noch nicht im Kern

| Lücke | Details |
|---|---|
| **ExecutionRunner-Implementierung** | Nur das Interface existiert. Die Drain-Schleife (`advanceToStep`, `reach_boundary`, `Moved`/`Reloaded`) ist **nicht portiert**. |
| **Coordinator im Produktivpfad** | `Coordinator` wird derzeit nur in Tests instanziiert. Der produktive Loop läuft in `quarkus/.../Engine.prompt` (`while (true)` mit Step-Budget, Retry, Triage, Persistenz). |
| **Step-übergreifende Persistenz** | Tool-Ergebnisse/History werden im Quarkus-`Engine` persistiert, nicht im Kern-Runner. |
| **Multi-Step-Boundary** | Delivery/Compaction/Move als Step-Grenze ist in der Spec beschrieben, im Java-Kern aber noch nicht verdrahtet. |

> **Konsequenz:** Der „erweiterte Core" ist konzeptionell und in seinen
> Bausteinen vorhanden, aber derzeit **zweigeteilt**: Die Einzelteile liegen
> im `engine`-Modul, der orchestrierende Multi-Step-Loop noch im Quarkus-Skin
> (`Engine.prompt`). Eine echte Extraktion muss zuerst die Drain-Schleife in
> den Kern portieren.

## 8. Extraktionsgrenze

### 8.1 Was in den Kern gehört

```
agent-kernel/
  core/
    contract/      ProviderAdapter, ToolExecutor, ToolGate, Hooks, ModelRequest
    eval/          TurnPolicy, PermissionRules, OutputBounding, ProviderClassifier
    model/         SessionId, AgentId, Messages, Events, Fehler
    port/          Stores, Clock, Approval/Question/Policy, FileSystem, ProcessRunner
    event/         EventBus, Journal, PayloadCodec
    json/          Json, Schema
  runtime/
    execution/     Coordinator, ExecutionRunner, Recovery, SessionExecution
    session/       Admission, Delivery, Revert, SessionService
    context/       ContextBuilder, Prepared, AgentProfile
    step/          Step, StepResult
    tool/          ToolRuntime, ToolRegistry, ToolAuthorizer
    permission/    PermissionEngine, PendingApprovals
    compaction/    CompactionService, CompactionPolicy
    resilience/    RetryPolicy, LoopDetector, ErrorClassifier, TriageService
    provider/      HttpProviderAdapter (oder als Adapter belassen)
```

### 8.2 Was außerhalb bleibt

- `engine.adapter.*` – SQL/SQLite, FS, Process, SSH, Net, Logging
- `quarkus/` – HTTP-API, SSE, Security (OIDC/Basic), UI, Metrics, Native-Config
- `deploy/`, `cli/`, `tui/`, `test-client/` – Werkzeuge und Skins
- UI, Auth0, Deployment

### 8.3 Abhängigkeitsregeln

1. `core.*` darf **nichts** aus `runtime.*` oder `adapter.*` kennen.
2. `runtime.*` darf `core.*` und Ports kennen, aber keinen konkreten Adapter.
3. `adapter.*` implementiert Ports; darf `core.*` und `runtime.*` nicht nach
   unten abhängig machen.
4. Der Quarkus-Skin verdrahtet alles über CDI und ruft nur
   `ExecutionRunner`/`Coordinator`/`Admission` auf.
5. Keine `Impl`-Klassen außerhalb der Adapter-Schicht.

## 9. Offene Punkte / nächste Schritte

1. **Drain-Schleife portieren** (`ExecutionRunner`-Implementierung):
   `reachBoundary`, Delivery, Compaction, Move, Step-Reset.
2. **`Coordinator` in den Produktivpfad** bringen und `Engine.prompt` auf
   `Admission` + `Coordinator` umstellen.
3. **`TurnPolicy` aktivieren** (Step-/Tool-/Token-/Wall-Time-Budget zentral
   statt im Skin).
4. **Persistenz in den Runner ziehen**: Tool-Ergebnisse, Assistant-Messages,
   Step-Enden und Terminal-Events müssen aus dem Kern publiziert werden.
5. **ArchUnit-Regeln** für die Kern/Adapter-Grenze ergänzen.
6. **Lasttests** für X-2/X-3/X-8 (virtuelle Threads, Tool-Quota, 1.000
   wartende Sessions).

## 10. Referenzen

- [`spec/session-engine/README.md`](../spec/session-engine/README.md)
- [`spec/session-engine/04-algorithms.md`](../spec/session-engine/04-algorithms.md)
- [`spec/session-engine/12-execution-model.md`](../spec/session-engine/12-execution-model.md)
- [`spec/session-engine/07-invariants.md`](../spec/session-engine/07-invariants.md)
- [`spec/java-packages.md`](../spec/java-packages.md)
- [`analysis/flow-db-service/02-stufe-2-execution-runner.md`](../analysis/flow-db-service/02-stufe-2-execution-runner.md)
