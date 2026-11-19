# Plan: Subagent-Crash — exaktes Session-Resume

**Status:** Schritt 1 umgesetzt — 2026-11-19; Schritt 2 offen
**Bezug:** `docs/spec/learnings.md` (P0-02), `docs/engineering/Async-use.md` (§9),
`docs/spec/session-engine/04-algorithms.md`, `docs/plans/qoc-weaknesses-remediation.md`
**Auslöser:** Workflow-Test mit `subagent`-Tool-Call; nach einem Prozess-Crash
wurde die Child-Session nicht wieder aufgegriffen, sondern die Node erneut
ausgeführt und eine neue Child-Session erzeugt.

---

## 1. Befund

### 1.1 Was heute passiert

1. Der Parent (Workflow-Node oder Agent-Turn) ruft `subagent` auf.
   `SubagentTool.execute` delegiert an `Engine.runSubagent`.
2. `Engine.runSubagent` erzeugt eine Child-Session mit Parent-Link
   (`Engine.java:2351`), setzt den Claim, schreibt den Prompt in die
   Child-Conversation und startet den Agent-Loop (`Engine.java:2373`).
3. **Harter Prozess-Crash während des Child-Laufs:**
   - Die Assistant-Message des Parents mit dem Tool-Call ist nicht persistiert.
     `QocEngine` schreibt sie erst nach Ausführung aller Tool-Calls
     (`QocEngine.java:1143-1180`).
   - Die Child-Conversation ist bis zum letzten abgeschlossenen Schritt
     dauerhaft; der Child-Claim bleibt offen (`QocEngine.java:744-749`,
     `1400-1402`).
   - `BackgroundTasks` und `InMemorySubagentSessionRegistry` sind in-memory und
     verloren (`BackgroundTasks.java:17-21`).
4. **Neustart:**
   - `QocEngine.recover()` sieht den offenen Child-Claim. Das Child ist keine
     Workflow-Session und wird als `build` mit dem Prompt
     `server-restarted` gestartet (`QocEngine.java:518-566`). Der tatsächliche
     Agent (`SessionInfo.agent()`) wird ignoriert.
   - `WorkflowRecovery` resume't den Workflow (`WorkflowRecovery.java:193-222`).
     `WorkflowExecutor.resume` setzt `currentNodeId` auf die letzte
     **abgeschlossene** Node (`WorkflowExecutor.java:171-173`).
     `WorkflowStepProviderImpl` berechnet daraus die nächste Node
     (`WorkflowStepProviderImpl.java:175-202`) — also die unterbrochene Node.
     `DefaultNodeDispatcher.runAgent` erzeugt eine **neue** Child-Session
     (`DefaultNodeDispatcher.java:627-645`).
   - Ergebnis: doppelte Arbeit, zwei Child-Sessions, mögliche
     Workspace-Konflikte.

### 1.2 Zusätzliche Lücken

- **Child stirbt ohne Prozess-Crash:** `QocEngine.runPrompt` fängt die Exception
  und liefert `EngineResult.failed`. `Engine.runPrompt` macht daraus
  `TurnOutcome.Cancelled`. `Engine.runSubagent` prüft `PromptResult.completed()`
  nicht und gibt den Fehlertext als erfolgreiches `SubagentResult` zurück
  (`Engine.java:2369-2401`). Der Parent sieht `ToolOutcome.Ok`.
- **Idempotency Key greift nicht:** Der Key ist
  `idem_{session}_{callId}` (`IdempotencyKey.java:17-21`). Die `callId` kommt
  vom LLM (`Step.java:151-155`). Nach dem Crash erzeugt der neue LLM-Call eine
  neue `callId` und damit einen neuen Key. Der `ToolResultCache` wird außerdem
  erst nach erfolgreicher Ausführung gefüllt (`ToolRuntime.java:92-104`,
  `161-164`).
- **Intent/Result fehlen:** `docs/spec/learnings.md:285-317` (P0-02) fordert,
  den Intent vor dem Side-Effect zu persistieren und den Key über Approval,
  Ausführung und Recovery hinweg zu transportieren. Das ist nicht umgesetzt.
- **Active Node wird nicht genutzt:** `WorkflowRunJournal` erfasst
  `activeNodeId` (`WorkflowRunJournal.java:85-101`), aber
  `WorkflowExecutor.resume` verwendet nur `currentNodeId`.
- **Recovery läuft mit reduziertem Tool-Set:** `Engine.start` ruft
  `qocEngine.recover()` auf, bevor der Quarkus-`Step`, der echte
  `ToolSnapshot` und der `subagent`-Runner verdrahtet sind
  (`Engine.java:1022-1053`). Der Recovery-Pfad nutzt damit den Core-Snapshot
  und das No-Op-PermissionGate, nicht die Quarkus-Tools/Rechte.

### 1.3 Nicht-Ziel

- Keine Exactly-once-Garantie. Die Semantik bleibt **at-least-once**.
- Kein SAGA-/Compensating-Action-Framework in diesem Plan; das ist ein
  separates Thema.
- Kein Recovery im `memory`-Modus. Dort sind Session und Journal nach dem
  Restart weg (bereits in Phase 3 verifiziert).

---

## 2. Zielbild

Zwei Stufen, sequenziell:

| Stufe | Ziel | Aufwand |
|---|---|---|
| **1** | Kein falsches Resume, kein stiller Subagent-Fehler, keine doppelte Waise | S |
| **2** | Exaktes Resume derselben Child-Session inkl. Parent-Handoff | L |

Stufe 1 ist eine Sofort-Härtung und kann unabhängig gemerged werden.
Stufe 2 baut darauf auf und ändert die Persistenz- und Recovery-Semantik.

---

## 3. Schritt 1 — Sofort-Härtung

### 3.1 Subagent-Fehler propagieren

**Datei:** `quarkus/.../http/Engine.java` (`runSubagent`)

- Nach `runPrompt(child, ...)` das Ergebnis prüfen:
  - `result.outcome() instanceof TurnOutcome.Completed` → Erfolg.
  - `TurnOutcome.Paused` → als Fehler behandeln (Subagents dürfen nicht
    pausieren; `question` ist bereits gefiltert).
  - `TurnOutcome.Failed` / `Cancelled` / `Refused` → typisierten Fehler werfen.
- Fehlermeldung mit `finish`, `outcome` und gekürztem `text` (max. 500 Zeichen).
- Der bestehende `catch (Throwable t)`-Block in `runSubagent` setzt die
  `BackgroundTasks` auf `FAILED` und publiziert `SubagentFinished(state=failed)`.
- `SubagentTool.execute` fängt die `RuntimeException` und liefert
  `ReadTool.failure("subagent", ...)` → der Parent sieht `ToolOutcome.Failed`.

**Test:** `SubagentFailurePropagationTest`
- Fake-`PromptResult` mit `TurnOutcome.Failed` → `SubagentTool` liefert
  `ToolOutcome.Failed`.
- Fake-`PromptResult` mit `TurnOutcome.Cancelled` → ebenfalls `Failed`.
- Erfolgreicher `Completed`-Fall bleibt `Ok`.

### 3.2 Child-Sessions nicht mehr als `build` resümieren

**Datei:** `engine/.../core/model/SessionId.java`

- Neue Methode `Option<SessionId> parent()`:
  - Top-Level-ID (`ses_<hex>`) → `Option.none()`.
  - Child-ID (`ses_ses_<parentHex>_<childHex>`) → Parent parsen.
  - Grandchild (`ses_ses_ses_...`) → direktes Parent.
- Neue Methode `boolean isChild()` als Kurzform.
- Tests: Top-Level, Child, Grandchild, ungültige ID.

**Datei:** `engine/.../runtime/QocEngine.java` (`createSession`)

- Overload `createSession(String agentName, String user, String mode, SessionId parent)`.
- Bestehende Methode delegiert mit `null`.
- `SessionService.create(location, agent, model, at, parent)` wird bereits
  unterstützt; die Child-ID trägt dann den Parent.

**Datei:** `workflow-runner/.../runner/DefaultNodeDispatcher.java`

- `runAgent` erzeugt die Child-Session mit dem Workflow-Run als Parent:
  `engine.createSession(prefixedName, principal.id(), "workspace", parent)`,
  wobei `parent` aus `cacheKey` geparst wird, wenn `cacheKey` eine gültige
  `SessionId` ist; sonst `null`.
- `cacheKey` ist der `runId`/die Workflow-Session (`WorkflowExecutor.java:217`).
- Damit tragen auch Workflow-Node-Child-Sessions ihren Parent in der ID.

**Datei:** `engine/.../runtime/QocEngine.java` (`recover`)

- Vor dem Resume `SessionInfo info = stores.sessions().info(sessionId)` laden.
- Wenn `sessionId.isChild()`:
  - Kein `runPrompt(..., "build", ...)`.
  - Claim freigeben.
  - Ein neues `SessionEvent.ChildSessionOrphaned(sessionId, parentId, reason)`
    journalieren. Falls das zu viel Surface ist, ersatzweise
    `SessionEvent.ExecutionFailed` mit
    `SessionError.Unknown("recovery", "child session orphaned; parent must resume")`.
    Das neue Event ist sauberer, weil es den terminalen `ExecutionFailed`-Zustand
    vermeidet und in Stufe 2 in ein Resume übergehen kann.
  - Log: `child session <id> orphaned; parent <parent> owns recovery`.
- Wenn kein Child:
  - Agent-Name aus `info.agent()` über `agentResolver.nameOf(...)` auflösen;
    Fallback `"build"`.
  - Rest wie bisher.
- Wichtig: Stufe 1 resümiert das Child **nicht** selbst. Es wird nur verhindert,
  dass es falsch als `build` läuft. Das exakte Resume kommt in Stufe 2.

**Test:** `QocEngineRecoveryTest`
- Offener Claim auf einer Child-Session → `ChildSessionOrphaned` (oder
  `ExecutionFailed(recovery)` als Fallback), Claim frei, kein
  `ExecutionStarted`.
- Offener Claim auf einer normalen Session → weiterhin `SyntheticRaised` +
  `ExecutionStarted` (bestehendes Verhalten).
- Workflow-Node-Child hat eine Parent-kodierte ID.

### 3.3 Recovery-Wiring (Befund, vollständiger Fix in Stufe 2)

**Datei:** `quarkus/.../http/Engine.java` (`start`)

- `qocEngine.recover()` wird aktuell vor dem Verdrahten von `step`,
  `toolRuntime` und `subagentTool.setRunner(...)` aufgerufen
  (`Engine.java:1022-1053`).
- Das ist in Stufe 1 nur ein dokumentierter Nebenbefund: `QocEngine.recover()`
  ruft `this.runPrompt(...)` auf und nutzt damit den **QocEngine-internen**
  `Step`/`ToolSnapshot`, nicht den Quarkus-`Step`. Ein Verschieben des Aufrufs
  allein stellt die Tool-Parität daher nicht her.
- In Stufe 1 wird `recover()` ans Ende von `start()` verschoben, damit
  zumindest der `subagent`-Runner gesetzt ist. Das ist eine Verbesserung, aber
  keine vollständige Lösung.
- Die vollständige Korrektur (Recovery über den äußeren Engine mit
  `ExecutionOverrides`) ist Teil von Stufe 2 (siehe 4.6).

**Test:** Startup-Reihenfolge
- Ein Recovery-Lauf startet erst nach `subagentTool.setRunner(...)` (z. B. über
  einen Ordering-Flag/Fake-Runner).
- Tool-Parität wird erst in 4.6 getestet.

### 3.4 Akzeptanz Stufe 1

- `mvn test` in `qoc/` grün.
- Ein fehlgeschlagenes Child erscheint beim Parent als `ToolOutcome.Failed`,
  nicht als erfolgreicher Text.
- Eine verwaiste Child-Session wird nicht mehr als `build` gestartet.
- Bestehende Recovery-Tests bleiben grün.
- Keine Änderung an der fachlichen Spec.

---

## 4. Schritt 2 — Exaktes Resume derselben Child-Session

### 4.1 Durable Invocation-Record

**Ziel:** Vor dem Child-Start ist dauerhaft bekannt, welche Session, welcher
Agent und welcher Tool-Call zusammengehören.

**Option A (bevorzugt): Journal-Events im Parent**

Neue Events in `SessionEvent`:

```java
record SubagentInvocationStarted(
        SessionId parentSession,
        String invocationId,
        String callId,
        String agent,
        String promptHash,
        SessionId childSession,
        String nodeId,              // optional, Workflow-Node
        Instant at) implements SessionEvent {}

record SubagentInvocationFinished(
        SessionId parentSession,
        String invocationId,
        String status,              // SUCCEEDED | FAILED | CANCELLED | ORPHANED
        String resultRef,           // ToolOutputStore-Key oder Kurztext
        String error,
        Instant at) implements SessionEvent {}
```

- `EventCodec` und `Journal`-Serialisierung ergänzen.
- `invocationId` = `sub_{parentSession}_{callId}` oder UUID; muss stabil sein.
- `promptHash` = SHA-256 über `agent + "\n" + prompt`, erste 16 Hex-Zeichen.
- Der Record wird **vor** dem Child-Start geschrieben, nachdem die Child-ID
  bekannt ist.

**Option B (Fallback): Tabelle `subagent_invocation`**

Falls Events zu unhandlich sind: Flyway-Migration mit
`id, parent_session, invocation_id, call_id, agent, prompt_hash, child_session,
node_id, status, result_ref, error, created_at, finished_at`.
Port `SubagentInvocationStore` mit `start`, `finish`, `findOpen`,
`findBySemanticKey`.

**Empfehlung:** Option A, weil der Workflow-Runner bereits journalbasiert ist
und kein neues Schema braucht.

### 4.2 Child-Resume mit eigenem Agent

**Datei:** `quarkus/.../http/Engine.java` (neue Methode `resumeSubagent`)

- Input: `childSession`, `agentName`, `prompt` (optional), `principal`.
- Ablauf:
  1. `SessionInfo` laden; `agentName` aus `info.agent()` auflösen, falls nicht
     übergeben.
  2. Prüfen, ob die Child-Conversation bereits eine terminale Assistant-Message
     hat (`ExecutionSucceeded` im Child-Journal). Wenn ja: letzten Text lesen,
     kein LLM-Call.
  3. Sonst: Continue-Hinweis in den Child-Kontext bringen. Zwei Varianten:
     - **Bevorzugt:** neuer `Engine.runPrompt(..., boolean storePrompt)`-Overload,
       der den Hinweis „You were interrupted by a restart. Continue the task
       from the last completed step. Do not repeat completed side effects.“ als
       User-Message persistiert (`storePrompt=true`).
     - **Alternativ:** `QocEngine.runPrompt(..., storePrompt=false)` und ein
       `SyntheticRaised`-Event; dafür muss `ContextBuilder` die Synthetic-Events
       auswerten (heute tut er das nicht).
  4. Ergebnis als `SubagentResult` zurückgeben.
- Der Child läuft dabei mit seinem eigenen Agent-Profil, nicht mit `build`.
- `subagentDepths` und `sessionRoles` müssen für das Child wieder gesetzt
  werden (durable Markierung, z. B. über `SessionInfo`-Metadaten oder das
  Invocation-Record). Sonst kann das Child `question` aufrufen.

**Test:** `SubagentResumeTest`
- Child mit abgeschlossenem Lauf → Text wird ohne LLM-Call zurückgegeben.
- Child mit offenem Lauf → `runPrompt` wird mit eigenem Agent aufgerufen.
- `question`-Tool ist im Child nicht verfügbar.

### 4.3 Parent-Handoff

Es gibt zwei Varianten; beide sollten mittelfristig existieren.

#### 4.3a Semantic Dedupe (einfacher, deckt Workflow-Retry ab)

- Der `subagent`-Tool-Aufruf berechnet einen semantischen Key:
  `sem_{parentSession}_{agent}_{promptHash}` (optional `nodeId`).
- Vor dem Start:
  - `findBySemanticKey(...)` im Invocation-Record.
  - `SUCCEEDED` → Ergebnis aus `ToolOutputStore`/Record zurückgeben.
  - `STARTED`/`RUNNING` → Child resümieren und Ergebnis abwarten.
  - `FAILED`/`ORPHANED` → neu starten oder Fehler zurückgeben (Policy:
    `retryable=true` → neu starten, sonst Fehler).
- Nach dem Start: `SubagentInvocationStarted` schreiben.
- Nach dem Ende: `SubagentInvocationFinished` schreiben.
- Damit erzeugt ein Workflow-Node-Retry mit gleichem Agent/Prompt kein neues
  Child, obwohl die `callId` neu ist.

**Grenze:** Zwei absichtliche identische Aufrufe im selben Parent würden
dedupliziert. Deshalb optionaler Parameter `dedupe=false` am `subagent`-Tool
oder ein expliziter `dedupeKey`.

#### 4.3b Turn-Checkpoint (korrekt, deckt alle Tools ab)

**Ziel:** Die Assistant-Message mit den Tool-Calls wird **vor** der
Tool-Ausführung persistiert. Nach dem Crash können die offenen Calls gezielt
replayed werden.

**Dateien:**
- `engine/.../runtime/step/Step.java`: `run` in zwei Phasen teilen:
  - `plan(...)` → `ModelEvents` + `ToolCallRequested`-Liste, ohne Ausführung.
  - `execute(...)` → führt die Calls aus und liefert `StepResult.Finished`.
- `engine/.../runtime/QocEngine.java`:
  - Nach `plan(...)` ein `StepCheckpoint`-Event schreiben
    (`messageId`, `stepNo`, `text`, `toolCalls`, `idempotencyKeys`).
  - Assistant-Message mit `ToolState.Pending` persistieren.
  - Nach `execute(...)` die Tool-States auf `Completed`/`Failed` aktualisieren.
- `engine/.../core/contract/tool/ToolCall.java`:
  - `IdempotencyKey idempotencyKey` ergänzen (Spec P0-02).
- `engine/.../core/contract/tool/ToolDefinition.java`:
  - `boolean sideEffecting`, `boolean supportsIdempotency` ergänzen.
- Recovery:
  - `StepCheckpoint` ohne `StepEnded` → offene Calls erneut ausführen.
  - `readOnly`/`supportsIdempotency` → automatisch retryen.
  - `sideEffecting` ohne Dedupe → `ToolOutcome.Failed(UNKNOWN)` oder
    `NeedsReview`; kein stiller Retry.
- Der `subagent`-Call nutzt den Invocation-Record, um das vorhandene Child zu
  finden und zu resümieren, statt ein neues zu erzeugen.

**Aufwand:** M–L. Das ist die generalisierte P0-02-Umsetzung und löst
Teilausführung für **alle** Tools, nicht nur Subagents.

### 4.4 Workflow: Node → Child verlinken und Active Node resume

**Datei:** `engine/.../core/model/SessionEvent.java`

- Neues Event `WorkflowNodeChildLinked(sessionId, nodeId, childSessionId, agent, attempt)`.
- `WorkflowStepProviderImpl` schreibt es direkt nach der Child-Erzeugung
  (in `DefaultNodeDispatcher.runAgent`), also **vor** dem Node-Abschluss.
- `WorkflowRunJournal` liest es und ergänzt `WorkflowRunSnapshot` um
  `activeNodeChildSession`.
- `WorkflowExecutor.resume` übergibt `snapshot.activeNodeId()` und
  `snapshot.activeNodeChildSession()` an den `WorkflowStepProviderImpl`.
- `WorkflowStepProviderImpl`:
  - Beim ersten `provideStep` nach einem Resume, wenn `resumeNodeId` gesetzt
    ist: genau diese Node dispatchen, nicht `getNextNodeId`.
  - Nach erfolgreichem Dispatch `resumeNodeId` löschen.
- `DefaultNodeDispatcher.runAgent`:
  - Wenn `resumeChild` gesetzt ist: `engine.runPrompt(resumeChild, ...)` statt
    `engine.createSession(...)`.
  - Wenn das Child bereits `ExecutionSucceeded` hat: letzten Assistant-Text
    lesen und als `NodeResult` zurückgeben, ohne LLM-Call.
  - Sonst: Child mit „continue“-Prompt resümieren.

**Test:** `WorkflowCrashResumeTest`
- Crash während Node B (A abgeschlossen, B gestartet, Child existiert).
- Resume dispatcht B, verwendet dieselbe Child-ID, erzeugt keine neue.
- Crash nach Child-Erfolg, vor `WorkflowNodeCompleted` → Child-Ergebnis wird
  wiederverwendet.

### 4.5 Background-Tasks durable machen

**Datei:** `engine/.../runtime/tool/subagent/BackgroundTasks.java`

- Neues Record/Port `BackgroundTaskStore` (Journal-Event im Parent oder Tabelle).
- Status: `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `ORPHANED`.
- Beim Start: `TaskStarted` mit `taskId`, `parent`, `agent`, `childSession`.
- Beim Ende: `TaskFinished` mit Status/Ergebnis.
- Beim Neustart: `RUNNING` ohne `TaskFinished` → `ORPHANED`.
- `task(status)` liefert `ORPHANED` inkl. `childSession`.
- Policy:
  - Child resumierbar → `resumeSubagent(...)`.
  - Sonst → `FAILED(orphaned)`.
- In-Memory-Implementierung bleibt für `memory`-Modus und Tests.

**Test:** `BackgroundTaskRecoveryTest`
- TaskStarted ohne Terminal → `ORPHANED`.
- `task(status)` zeigt `ORPHANED`.
- Resume-Policy startet das Child oder markiert `FAILED`.

### 4.6 Recovery über den äußeren Engine führen

**Problem:** `QocEngine.recover()` nutzt den Core-`ToolSnapshot` und das
No-Op-PermissionGate. Ein resumed Child hat damit nicht die Quarkus-Tools
(MCP, `subagent`, …) und nicht die echten Rechte.

**Lösung:**
- `QocEngine` bekommt einen Callback
  `Function<SessionId, ExecutionOverrides> recoveryOverrides`.
- `Engine.start` setzt ihn nach dem Aufbau von `step`, `toolSnapshot`,
  `contextBuilder` und `subagentTool.setRunner(...)`.
- `QocEngine.recover` verwendet die Overrides, wenn vorhanden; sonst Fallback
  auf den Core-Pfad (framework-neutraler Modus).
- Alternativ: Recovery komplett in `Engine` orchestrieren und
  `QocEngine.recover` nur noch `openClaims()` liefern lassen.

**Test:** Recovery eines Child-Agenten, der ein MCP-Tool/subagent-Tool nutzt →
Tool ist im Snapshot vorhanden, PermissionGate wird aufgerufen.

### 4.7 Akzeptanz Stufe 2

- Crash während eines `subagent`-Calls → **dieselbe** Child-Session wird
  resumed; keine neue Child-ID.
- Crash nach Child-Erfolg, vor Parent-Handoff → Child-Ergebnis wird
  wiederverwendet; kein zweiter LLM-/Tool-Lauf.
- Crash während einer Workflow-Node → `activeNodeId` wird re-entered; die
  vorhandene Child-Session wird verwendet.
- `BackgroundTask` ist nach dem Restart als `ORPHANED` sichtbar.
- `IdempotencyKey` bleibt über den Resume stabil; `ToolResultCache`-Hit
  verhindert den zweiten Side-Effect.
- `mvn test` grün; JaCoCo ≥ 80 %.

---

## 5. Testmatrix

| Szenario | Stufe | Erwartung |
|---|---|---|
| Child-LLM-Fehler ohne Crash | 1 | `ToolOutcome.Failed`, kein stiller Erfolg |
| Orphan-Child nach Crash | 1 | `ChildSessionOrphaned`, Claim frei, kein `build`-Lauf |
| Workflow-Node-Child-ID | 1 | Parent-kodierte `SessionId` |
| Recovery-Wiring | 1 | `recover()` läuft nach der Runner-Verdrahtung |
| Crash während Child | 2 | gleiche Child-ID wird resumed |
| Crash nach Child-Erfolg | 2 | Ergebnis wird reused, kein neuer Lauf |
| Crash während Workflow-Node | 2 | `activeNodeId` + vorhandenes Child |
| Background-Task-Crash | 2 | `ORPHANED` sichtbar, Policy angewandt |
| Idempotency-Key über Resume | 2 | gleicher Key, Cache-Hit |
| Paralleler Doppel-Start | 2 | Claim verhindert zweite Ausführung |

---

## 6. Reihenfolge und Aufwand

1. **Stufe 1.1** Fehlerpropagation (S)
2. **Stufe 1.2** `SessionId.parent()` + Child-Erkennung + Recovery-Skip (S–M)
3. **Stufe 1.3** Recovery-Wiring (S)
4. **Stufe 1.4** Tests und Doku (S)
5. **Stufe 2.1** Invocation-Record (M)
6. **Stufe 2.2** Child-Resume mit eigenem Agent (M)
7. **Stufe 2.3a** Semantic Dedupe (M)
8. **Stufe 2.4** Workflow Node→Child + Active Node (M)
9. **Stufe 2.5** Background-Tasks durable (M)
10. **Stufe 2.6** Recovery über äußeren Engine (M)
11. **Stufe 2.3b** Turn-Checkpoint / P0-02 (L, optional danach)

**Empfehlung:** Stufe 1 komplett mergen, dann Stufe 2.1–2.4. Turn-Checkpoint
(2.3b) ist die generalisierte Lösung, aber nicht Voraussetzung für das
Subagent-Resume, wenn Semantic Dedupe vorhanden ist.

---

## 7. Risiken und Nicht-Ziele

- **Doppelte Side-Effects:** At-least-once bleibt. Auch mit Resume kann der
  letzte in-flight Tool-Call erneut laufen. Tools müssen idempotent sein oder
  Check-before-Retry unterstützen.
- **Semantic Dedupe:** Zwei absichtliche identische Subagent-Aufrufe könnten
  dedupliziert werden. Mitigation: `dedupe=false`/`dedupeKey` am Tool.
- **Conversation-Resume:** Ein Child, das mitten in einem Tool-Call starb, sieht
  beim Resume nur die abgeschlossenen Schritte. Der offene Call wird erneut
  vom LLM vorgeschlagen; das ist gewollt, aber side-effecting Tools brauchen
  Dedupe.
- **Turn-Checkpoint:** Ändert die Persistenz-Reihenfolge von Conversation und
  Tool-Ausführung. Bestehende Tests und UI-Projektionen müssen angepasst
  werden.
- **Memory-Modus:** Kein Resume; dokumentiertes Nicht-Ziel.
- **SAGA:** Nicht Teil dieses Plans. Compensating Actions für nicht-idempotente
  Schritte bleiben ein separates Vorhaben.

---

## 8. Umsetzungsstatus

### Schritt 1 — umgesetzt (2026-11-19)

- `SessionId.parent()` / `isChild()` ergänzt; Child-IDs tragen ihren Parent.
- `QocEngine.createSession(..., parent)` ergänzt; `DefaultNodeDispatcher`
  erzeugt Workflow-Node-Child-Sessions mit dem Run als Parent.
- `SessionEvent.ChildSessionOrphaned` ergänzt (`EventCodec`,
  `NativeReflectionConfig`).
- `QocEngine.recover()` resümiert Child-Sessions nicht mehr als `build`,
  journaliert `ChildSessionOrphaned` und gibt den Claim frei.
- Generische Recovery nutzt den tatsächlichen Agenten der Session
  (`AgentResolver.nameOf`) statt hartem `build`.
- `Engine.runSubagent` prüft `PromptResult.completed()` und propagiert
  Fehler/Abbrüche als Tool-Failure.
- Recovery-Aufruf in `Engine.start()` hinter die Verdrahtung von `step` und
  `SubagentRunner` verschoben.

**Tests:**
`SessionIdTest`, `QocEngineChildRecoveryTest`, `SubagentToolFailureTest`,
`EngineSubagentOutcomeTest`, `DefaultNodeDispatcherParentSessionTest`,
`EventCodecTest`.

**Verifikation:** `mvn test` grün; `mvn -pl engine verify` inkl.
JaCoCo-Gate (80 %) grün.

### Schritt 2 — offen

Siehe Abschnitte 4.1–4.7. Insbesondere fehlen noch der durable
Invocation-Record, das exakte Child-Resume mit Parent-Handoff, der
Turn-Checkpoint und die Recovery-Overrides für den äußeren Engine.
