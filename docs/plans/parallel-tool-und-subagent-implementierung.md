# Implementierungsplan: Parallele Tool-Ausführung & parallele Subagent-Ausführung

> **Status:** Plan / Analyse, keine Implementierung  
> **Datum:** 2026-11-22  
> **Projekt:** `qoc`  
> **Bezug:** `docs/guides/Subagent-Delegation.md`, `docs/engineering/Async-use.md`,
> `docs/engineering/Task-Orchestration.md`, bestehender E2E-Plan
> `/home/torsten/.opencode/plan/e2e-subagent-parallel-tests.md`

---

## 1. Ziel

Dieser Plan beschreibt, wie

1. **Tool-Aufrufe innerhalb eines Model-Step parallel** ausgeführt werden und
2. **mehrere Agenten über das `subagent`-Tool parallel** laufen können,

ohne bestehende Invarianten zu brechen.

Wichtig: Punkt 1 ist im Engine-Kern **bereits umgesetzt**. Der Plan dokumentiert
den Ist-Zustand und konzentriert sich bei der Umsetzung auf die noch fehlende
parallele Batch-Ausführung im `subagent`-Tool sowie auf die dafür notwendige
Thread-Safety.

---

## 2. Ausgangslage (verifiziert im Code)

### 2.1 Parallele Tool-Ausführung

| Aspekt | Fundstelle | Verhalten |
|---|---|---|
| Paralleler Executor | `engine/.../runtime/step/Step.java:35` | `MAX_PARALLEL_TOOLS = 8` |
| Fan-out | `Step.java:143–181` | `Semaphore(8)` + `VirtualThreadPerTaskExecutor` + `invokeAll` |
| Ergebnisreihenfolge | `Step.java:168–181` | Ergebnisse werden nach Call-Index einsortiert (`G-05`) |
| Sonderfall `question` | `Step.java:128–131` | Alle Calls laufen sequentiell, wenn ein `question` dabei ist |
| Timeout-Ausnahme | `ToolRuntime.java:98–99` | `subagent`/`task` sind von der Step-Timeout-Begrenzung ausgenommen |
| Tests | `ParallelToolExecutionTest.java` | Parallelität, `question`-Sequenz, Cap bei 8 |

**Fazit:** Für die Basis-Parallelität ist keine Neuimplementierung nötig.
Optional kann der Cap konfigurierbar gemacht werden (siehe WP 5).

### 2.2 Parallele Agenten-Ausführung

| Weg | Fundstelle | Verhalten |
|---|---|---|
| Mehrere `subagent`-Calls in einem Step | `Step.java` + `SubagentParallelTest.twoSubagentCallsRunConcurrently…` | **Parallel**, begrenzt durch globales Semaphore |
| `subagent(tasks=[...])` | `SubagentTool.executeBatch` (`SubagentTool.java:136–162`) | **Sequentiell und fail-fast** |
| Globales Subagent-Limit | `Engine.java:2329–2333` | `MAX_CONCURRENT_SUBAGENTS = 4` |
| Depth-Limit | `Engine.java:2492–2495` | max. 2 |
| Background-Tasks | `BackgroundTasks.java` | In-Memory, virtual Threads, thread-safe |
| Parent-Token-Accumulation | `Engine.runSubagent` | Read-Modify-Write auf `SessionStore` – **nicht atomar** |

**Widerspruch:** `docs/guides/Subagent-Delegation.md` §9/§11 beschreibt
`tasks[]` als **parallel** mit Partial Results. Der Code und
`SubagentParallelTest.batchTasksRunSequentially…` sagen aktuell
**sequentiell, fail-fast**.

---

## 3. Zielbild

### 3.1 Leitentscheidungen

| ID | Entscheidung | Begründung |
|---|---|---|
| D1 | `tasks[]` bleibt **standardmäßig sequentiell** | Rückwärtskompatibilität, deterministisches Fail-fast |
| D2 | Paralleler Batch nur über **opt-in** `parallel=true` | Kein stilles Semantik-Change; bestehende Tests bleiben gültig |
| D3 | Paralleler Batch liefert **Partial Results** | Ein fehlgeschlagener Task soll die anderen nicht blockieren |
| D4 | Ausgabe bleibt **index-stabil** | Ergebnisreihenfolge unabhängig von Fertigstellungsreihenfolge |
| D5 | Fan-out wird durch das bestehende **globale Subagent-Semaphore (4)** begrenzt | Keine Überlastung; kein neues konkurrierendes Limit |
| D6 | Token-Accumulation wird **serialisiert** | Verhindert Lost Updates bei parallelen Child-Runs |
| D7 | `background=true` und `tasks[]` bleiben zunächst getrennt | Keine implizite Semantikänderung; background ist weiterhin Single-Task |

### 3.2 Neue Tool-Signatur (geplant)

```json
{
  "parallel": true,
  "tasks": [
    {"name": "source-1", "agent": "web-researcher", "prompt": "URL A"},
    {"name": "source-2", "agent": "web-researcher", "prompt": "URL B"}
  ]
}
```

- `parallel` ist optional, Default `false`.
- Ohne `tasks[]` bleibt `parallel` wirkungslos (Single-Task-Verhalten).
- `background` und `tasks[]` schließen sich weiterhin aus.

---

## 4. Arbeitspakete

### WP 1 – Token-Accumulation thread-sicher machen (Voraussetzung)

**Problem:** `Engine.runSubagent` liest `SessionInfo` des Parents, addiert
Tokens und ruft `sessions.put(...)`. Zwei parallele Subagents können sich
gegenseitig überschreiben.

**Änderung:**
- In `Engine` einen dedizierten `ReentrantLock` (oder eine schmale
  Lock-Struktur pro Parent) einführen.
- Helper `recordSubagentUsage(SessionId parent, int input, int output)`:
  - Lock acquire
  - `sessions.info(parent)`
  - neues `SessionInfo` mit addierten Tokens
  - `sessions.put(...)`
  - Lock release
- Kein `synchronized` auf `SessionId`, da `SessionId` kein kanonisches
  Instanz-Objekt pro Wert ist.

**Alternativ (sauberer, aber größer):** `SessionStore` um eine atomare
`addTokens(SessionId, TokenCount)`-Operation erweitern. Das erfordert
Anpassungen in `InMemorySessionStore`, `AbstractJdbcSessions` und ggf.
weiteren Implementierungen. Für den MVP reicht der Lock in `Engine`.

**Akzeptanz:** Zwei parallele Subagent-Runs addieren ihre Tokens korrekt.

---

### WP 2 – `SubagentTool` um opt-in parallelen Batch erweitern

**Datei:** `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java`

**2.1 Schema**
- `parallel` als optionales `bool` in `definition(...)` ergänzen.
- Bestehende Felder unverändert lassen.

**2.2 Ausführung**
- `executeBatch(...)` in zwei Pfade teilen:
  - `executeBatchSequential(...)` – exakt heutiges Verhalten.
  - `executeBatchParallel(...)` – neuer Pfad.
- Gemeinsame Hilfsmethode `runTask(runner, permissionGate, tasksRegistry, task, context, index)`,
  die einen einzelnen Task ausführt und ein `TaskOutcome` liefert:
  ```java
  record TaskOutcome(int index, String name, String agent,
                     ToolOutcome outcome, TokenCount usage) {}
  ```
- Im parallelen Pfad:
  - `ExecutorService` mit Virtual Threads (statisch oder pro Aufruf).
  - `Semaphore` mit `Math.min(tasks.size(), MAX_BATCH_PARALLEL)`.
  - `Future<TaskOutcome>` pro Task; `invokeAll` oder manuelles `submit`.
  - Ergebnisse in ein Array an Index-Position schreiben.
  - Bei `InterruptedException`: alle Futures canceln, Interrupt restaurieren,
    verbleibende Tasks als `cancelled` markieren.
- Im sequentiellen Pfad bleibt das bisherige Fail-fast erhalten.

**2.3 Ergebnisformat (parallel)**
```
source-1: ok
source-2: failed: <fehlermeldung>
```
- Metadata:
  - `tasks`: Anzahl gesamt
  - `succeeded`: Anzahl erfolgreich
  - `failed`: Anzahl fehlgeschlagen
  - `tokens`: Summe der Task-Tokens
- Rückgabe ist `ToolOutcome.Ok`, solange der Batch selbst ausgeführt werden
  konnte. Einzelne Task-Fehler stehen im Text und in der Metadata.
- Optional: `ToolOutcome.Failed`, wenn **alle** Tasks fehlschlagen (separate
  Entscheidung, siehe § 8).

**2.4 Permission**
- Jeder Task durchläuft weiterhin den Permission-Check mit `action=subagent`
  und `agent=<agent>`.
- Im parallelen Modus blockiert ein denied Task nur sich selbst; im
  sequentiellen Modus bleibt fail-fast.

**2.5 Cancellation**
- `Future.cancel(true)` für noch laufende Tasks.
- `BackgroundTasks`-Einträge werden bei Abbruch auf `CANCELLED` gesetzt.
- Dokumentieren: Ein laufender `SubagentRunner` muss Interrupts beachten;
  andernfalls bleibt der Thread bis zum natürlichen Ende aktiv.

---

### WP 3 – `Engine`-Wiring und Concurrency

**Datei:** `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java`

- Globales `subagentSemaphore` bleibt die harte Obergrenze (4).
- Paralleler Batch darf **nicht** zusätzlich mehr als 4 Subagents gleichzeitig
  starten. Deshalb:
  - Batch-Semaphore auf `min(tasks, MAX_CONCURRENT_SUBAGENTS)` setzen, oder
  - direkt das globale Semaphore verwenden (einfacher, aber weniger lokal
    begrenzt).
- Empfehlung: lokales `Semaphore` mit `MAX_BATCH_PARALLEL = 4` und zusätzlich
  das globale Semaphore in `runSubagent`. Doppelte Begrenzung ist bewusst:
  - lokal: begrenzt die Thread-Erzeugung im Batch,
  - global: schützt alle Sessions und verschachtelte Subagents.
- `MAX_CONCURRENT_SUBAGENTS` optional konfigurierbar machen
  (`engine.subagent.max-concurrency`), Default 4. Das ist optional und kann
  in einem späteren WP erfolgen.

---

### WP 4 – Tests

**Unit-Tests (`engine/src/test/.../subagent/`)**

| Test | Zweck |
|---|---|
| `SubagentToolParallelBatchTest.twoTasksRunConcurrently` | `maxActive == 2` bei `parallel=true` |
| `...preservesIndexOrder` | Ausgabe bleibt `source-1`, `source-2`, auch bei umgekehrter Fertigstellung |
| `...partialFailureDoesNotAbortOthers` | Task 1 wirft, Task 2 liefert trotzdem `ok` |
| `...permissionDeniedOnlyAffectsTask` | Ein denied Task, andere laufen weiter |
| `...sequentialRemainsDefault` | Ohne `parallel` weiterhin `maxActive == 1` und fail-fast |
| `...cancelsRemainingOnInterrupt` | Interrupt → verbleibende Tasks `cancelled` |
| `SubagentParallelTest` | bestehenden Sequential-Test behalten; neuen Parallel-Test ergänzen |

**Engine-Test**

| Test | Zweck |
|---|---|
| `EngineSubagentUsageTest.concurrentRunsAccumulateTokens` | Zwei parallele `runSubagent`-Aufrufe → Parent-Tokens = Summe |

**E2E-Tests**

- Bestehenden Plan `/home/torsten/.opencode/plan/e2e-subagent-parallel-tests.md`
  erweitern:
  - Variante B (Batch) einmal mit `parallel=false` (sequentiell, fail-fast)
  - Variante B (Batch) einmal mit `parallel=true` (parallel, Partial Results)
- FakeLlm muss `parallel` im Tool-Input korrekt durchreichen.

**Regression**
- Alle bestehenden Engine-Tests müssen grün bleiben.
- `SubagentParallelTest.batchTasksRunSequentially…` bleibt als Default-Test
  erhalten.

---

### WP 5 – Optionale Härtung der Tool-Parallelität

Diese Punkte sind **nicht Voraussetzung** für WP 1–4, aber sinnvoll:

1. **Konfigurierbarer Cap**
   - `MAX_PARALLEL_TOOLS` aus `Step` in `QocEngine.EngineConfig` oder
     `EngineConfig` verschieben (`engine.step.max-parallel-tools`, Default 8,
     Wertebereich 1–32).
   - `Step` bekommt den Wert per Konstruktor oder Konfigurationsobjekt.
2. **Metriken/Logging**
   - Anzahl paralleler Calls, Wartezeit auf Semaphore, Queue-Länge.
3. **Dokumentation**
   - `docs/engineering/Core.md` X-3 mit tatsächlichem Default und
     Konfigurationsweg aktualisieren.

---

## 5. Datei-Änderungsliste (geplant)

| Datei | Änderung |
|---|---|
| `engine/.../runtime/tool/subagent/SubagentTool.java` | `parallel`-Flag, Batch-Split, Parallel-Pfad, Partial Results |
| `quarkus/.../http/Engine.java` | Token-Accumulation-Lock, ggf. Batch-Limit, ggf. Config |
| `engine/.../runtime/step/Step.java` | nur falls WP 5 (konfigurierbarer Cap) umgesetzt wird |
| `engine/src/test/.../subagent/SubagentToolParallelBatchTest.java` | neu |
| `engine/src/test/.../subagent/SubagentParallelTest.java` | Default-Test behalten, Parallel-Test ergänzen |
| `quarkus/src/test/.../EngineSubagentUsageTest.java` | neu (Token-Race) |
| `docs/guides/Subagent-Delegation.md` | §9/§11 an tatsächliches Verhalten angleichen |
| `docs/engineering/Async-use.md` | Known Gaps / Status aktualisieren |
| `.ai/FACTS.md`, `.ai/ARCHITECTURE.md` | nach Umsetzung chronologisch ergänzen |

---

## 6. Rollout / Phasen

| Phase | Inhalt | Abhängigkeit |
|---|---|---|
| **Phase 0** | Baseline: `mvn test` grün, bestehende Tests erfassen | – |
| **Phase 1** | WP 1 (Token-Lock) + Test | unabhängig, risikoarm |
| **Phase 2** | WP 2 + WP 3 (opt-in paralleler Batch) + Unit-Tests | WP 1 |
| **Phase 3** | E2E-Tests, Doku-Angleichung | WP 2 |
| **Phase 4** | Optional: WP 5 (konfigurierbarer Cap), Metriken | WP 2 |

**Empfehlung:** `parallel=true` zunächst nicht als Default aktivieren.
Erst nach E2E- und Lasttests entscheiden, ob der Default geändert wird.

---

## 7. Risiken und Gegenmaßnahmen

| Risiko | Auswirkung | Gegenmaßnahme |
|---|---|---|
| Lost Update bei Parent-Tokens | Falsche Kosten/Usage | WP 1: Lock um Read-Modify-Write |
| Nicht thread-safer `SessionStore` | Korrupte Session-Infos | WP 1 + gezielter Concurrency-Test |
| Überlastung durch zu viele Subagents | Provider-Rate-Limits, Speicher | Globales Semaphore (4) + lokales Batch-Limit |
| Nicht-deterministische Fehlerreihenfolge | Verwirrende Tool-Ausgaben | Index-stabile Aggregation, Partial-Result-Format |
| Interrupt wird vom Runner ignoriert | Tasks laufen weiter | Dokumentieren; Runner muss kooperativ sein; `Future.cancel(true)` |
| Semantikänderung für bestehende Nutzer | Regression | Opt-in `parallel=false` als Default; bestehende Tests behalten |
| `background` + `tasks[]` | Unklare Semantik | Weiterhin ausschließen, klar dokumentieren |

---

## 8. Offene Entscheidungen

1. **Default von `parallel`:** `false` (empfohlen) oder `true`?
2. **Rückgabe bei komplettem Batch-Fehler:** `ToolOutcome.Ok` mit
   `failed=n` (empfohlen für Partial Results) oder `ToolOutcome.Failed`?
3. **Batch-Limit:** globales Semaphore (4) allein oder zusätzliches lokales
   Limit?
4. **Konfigurierbarkeit:** Sollen `MAX_PARALLEL_TOOLS` und
   `MAX_CONCURRENT_SUBAGENTS` in Phase 2 oder erst in Phase 4 konfigurierbar
   werden?
5. **DAG-Scheduler:** Wann wird `dependsOn`/Fan-in aus
   `Task-Orchestration.md` umgesetzt? Nicht Teil dieses Plans.

---

## 9. Akzeptanzkriterien

- [ ] `mvn test` in `qoc/` ist grün.
- [ ] Ohne `parallel` verhält sich `tasks[]` exakt wie bisher
      (sequentiell, fail-fast, stabile Reihenfolge).
- [ ] Mit `parallel=true` laufen unabhängige Tasks nachweislich gleichzeitig
      (`maxActive > 1`).
- [ ] Die Ausgabe bleibt index-stabil, unabhängig von der Fertigstellungsreihenfolge.
- [ ] Ein fehlgeschlagener Task bricht die anderen im parallelen Modus nicht ab.
- [ ] Das globale Subagent-Limit (4) wird nie überschritten.
- [ ] Parent-Tokens sind bei parallelen Runs korrekt summiert.
- [ ] Bestehende Tests und die 80-%-JaCoCo-Hürde bleiben erfüllt.
- [ ] Doku beschreibt Default- und Opt-in-Verhalten korrekt.

---

## 10. Nicht Teil dieses Plans

- DAG-Scheduler mit `dependsOn`, `WAITING/READY/BLOCKED`, Result-Interpolation.
- Durable Background-Tasks über Neustarts hinweg.
- Remote Worker Pool / MCP-Routing (`SubagentRouter`, `WorkerRegistry`).
- Änderungen an `oc/` (externer Referenz-Checkout).
- Implementierung selbst – dieser Plan beschreibt nur die Umsetzung.
