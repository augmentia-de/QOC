# resilience.md — Robuste Agenten-Infrastruktur für QOC

## 1. Zielsetzung

Deterministische Infrastruktur-Schicht ("Wrapper") um den probabilistischen LLM-Agenten.
Fängt Ausfälle ab (Netzwerk, Berechtigungen, Timeouts, Endlosschleifen), sichert den
Zustand persistent und eskaliert bei unlösbaren Fehlern automatisiert.

## 2. Architektur-Übersicht

Sechs Phasen, sequenziell bzw. überwacht:

1. **Pre-Flight Checks** — proaktive Validierung vor Task-Start
2. **State & Checkpoint Initialization** — Persistenz-Setup
3. **Execution Loop mit Timeouts & Watchdogs** — Laufzeitüberwachung
4. **Fehlerklassifizierung & Smart Retry** — transiente vs. permanente Fehler
5. **LLM-Fehleranalyse & Selbstheilung** — Triage-Schleife bei lösbaren Fehlern
6. **Eskalation, Alerting & HITL** — bei finalem Scheitern

## 3. Phasen im Detail

### Phase 0: Housekeeping (Bug-Fixes)

#### 0.1 idle_outcome setzen
- **Problem:** Keine Session hat `idle_outcome` gesetzt (immer NULL).
- **Fix:** In `Engine.runPrompt()`, nach TurnOutcome-Berechnung `sessions.put()` mit idle_outcome.
- **Dateien:** `Engine.java` (runPrompt, ~Zeile 1925)
- **Aufwand:** 0.5 Tag

#### 0.2 Subagent-Deduplizierung
- **Problem:** 7× Architecture, 4× Execution Subagents für einen Schritt.
- **Fix:** Vor `runner.run()` prüfen ob Session für Agent existiert; Prompt-Level-Regel im Orchestrator.
- **Dateien:** `SubagentTool.java`, `SubagentRunner.java`, Orchestrator-System-Prompt
- **Aufwand:** 1 Tag

#### 0.3 Leere Sessions auto-löschen
- **Problem:** Sessions mit 0 Messages bleiben in DB.
- **Fix:** Am Ende von `runPrompt()`: wenn `messageCount == 0`, `sessionRetention.delete(id)`.
- **Dateien:** `Engine.java`, `SessionRetention.java`
- **Aufwand:** 0.5 Tag

#### 0.4 Question-Resolution-Bug fixen
- **Problem:** REST-API-Antwort auf Frage resumed den blockierten Turn nicht.
- **Root Cause:** `DurableQuestionSource.ask()` blockt auf `webQuestionSource.ask()` (CompletableFuture). API ruft `resolve()` auf, aber der blockierende Thread wird nicht aufgeweckt.
- **Fix:** Callback-basierte Auflösung statt Polling; `WebQuestionSource.ask()` ohne Timeout, API-Resolution completen den Future korrekt.
- **Dateien:** `DurableQuestionSource.java`, `WebQuestionSource.java`, `HttpApi.java`
- **Aufwand:** 1–2 Tage

#### 0.5 Approval-Cache bereinigen
- **Problem:** 180 `pending_approval`-Einträge (alle DECIDED), keine Bereinigung.
- **Fix:** Periodisch (täglich): `DELETE FROM pending_approval WHERE state='DECIDED' AND created_at < NOW() - INTERVAL '7 days'`.
- **Dateien:** `SessionRetention.java` oder neues `ApprovalRetention.java`
- **Aufwand:** 0.5 Tag

#### 0.6 Tool-Result-Cache Eviction
- **Problem:** 1541 Einträge, keine Eviction.
- **Fix:** TTL oder LRU in `ToolResultCache`.
- **Dateien:** `SqliteToolResultCache.java`, `SqlToolResultCache.java`
- **Aufwand:** 0.5 Tag

#### 0.7 Snapshots aktivieren
- **Problem:** 0 Snapshots in DB (Feature deaktiviert).
- **Fix:** `ENGINE_REVERT_SNAPSHOT=true` in `.env` setzen.
- **Aufwand:** 0.5 Tag

### Phase 1: Core Resilience (Pre-Flight + Retry)

#### 1.1 PreflightChecker
- **Neue Klasse:** `engine.runtime.resilience.PreflightChecker`
- **Checks:** DNS-Lookup, HTTP Health, API-Key-Validierung, DB-Connectivity, Workspace-Writability
- **Config:** `ENGINE_PREFLIGHT_ENABLED` (default: true)

#### 1.2 ErrorClassifier
- **Neue Klasse:** `engine.runtime.resilience.ErrorClassifier`
- **Taxonomie:** TRANSIENT (Timeout, 429, 5xx), PERMANENT (403, 400), CORRECTABLE (KeyError, IllegalArgumentException), CRITICAL (Loop)
- **Config:** keine neuen (intern)

#### 1.3 RetryPolicy (Exponential Backoff + Jitter)
- **Neue Klasse:** `engine.runtime.resilience.RetryPolicy`
- **Formel:** `t = min(Tmax, Base × 2^attempt) + Jitter(0..JitterMax)`
- **Config:** `ENGINE_RETRY_BASE_DELAY_MS` (1000), `ENGINE_RETRY_MAX_DELAY_MS` (60000), `ENGINE_RETRY_JITTER_MS` (5000)

#### 1.4 Engine-Integration
- Preflight vor Run-Loop
- ErrorClassifier + RetryPolicy in Retry-Logik
- Config: `ENGINE_RETRY_MAX_ATTEMPTS` (3)

### Phase 2: Safety (Timeouts + Loop-Detection)

#### 2.1 Tool-Level Hard-Timeouts
- **Änderung:** `ToolRuntime.execute()` wrapt Tool-Ausführung in `Future.get(timeout)`
- **Config:** `ENGINE_STEP_TIMEOUT_SECONDS` (30)

#### 2.2 LoopDetector
- **Neue Klasse:** `engine.runtime.resilience.LoopDetector`
- **Erkennt:** gleicher Tool-Name + gleiche Input-Parameter N× in Folge
- **Config:** `ENGINE_LOOP_DETECTION_THRESHOLD` (5)

### Phase 3: Self-Healing (Triage)

#### 3.1 TriageService
- **Neue Klasse:** `engine.runtime.resilience.TriageService`
- **Prompt:** Stacktrace + Tool-Output + State → JSON-Korrekturvorschlag
- **Config:** `ENGINE_TRIAGE_ENABLED` (false), `ENGINE_TRIAGE_MAX_ATTEMPTS` (3), `ENGINE_TRIAGE_MODEL`

### Phase 4: Escalation (Webhook + HITL)

#### 4.1 WebhookAlerter
- **Neue Klasse:** `engine.adapter.alerting.WebhookAlerter`
- **Formate:** Slack, Telegram, Generic JSON
- **Config:** `ENGINE_WEBHOOK_ENABLED` (false), `ENGINE_WEBHOOK_URL`, `ENGINE_WEBHOOK_FORMAT`

#### 4.2 HITL-Aktions-Links
- "Berechtigung temporär erteilen" → `/api/approval/{token}/grant`
- "Task fortsetzen" → `/api/session/{id}/resume`
- "Task abbrechen" → `/api/session/{id}/interrupt`

## 4. Neue Config-Properties

```properties
# Phase 0
ENGINE_REVERT_SNAPSHOT=true

# Phase 1
ENGINE_PREFLIGHT_ENABLED=true
ENGINE_RETRY_MAX_ATTEMPTS=3
ENGINE_RETRY_BASE_DELAY_MS=1000
ENGINE_RETRY_MAX_DELAY_MS=60000
ENGINE_RETRY_JITTER_MS=5000

# Phase 2
ENGINE_STEP_TIMEOUT_SECONDS=30
ENGINE_LOOP_DETECTION_THRESHOLD=5

# Phase 3
ENGINE_TRIAGE_ENABLED=false
ENGINE_TRIAGE_MAX_ATTEMPTS=3
ENGINE_TRIAGE_MODEL=

# Phase 4
ENGINE_WEBHOOK_ENABLED=false
ENGINE_WEBHOOK_URL=
ENGINE_WEBHOOK_FORMAT=generic
```

## 5. Neue Komponenten

| Komponente | Paket | Beschreibung |
|---|---|---|
| `PreflightChecker` | `engine.runtime.resilience` | DNS, Health, Auth-Validierung |
| `ErrorClassifier` | `engine.runtime.resilience` | Fehler-Taxonomie |
| `RetryPolicy` | `engine.runtime.resilience` | Exponential Backoff + Jitter |
| `LoopDetector` | `engine.runtime.resilience` | Erkennt wiederholte Tool-Calls |
| `TriageService` | `engine.runtime.resilience` | LLM-basierte Fehleranalyse |
| `WebhookAlerter` | `engine.adapter.alerting` | Slack/Telegram/Webhook |
| `ApprovalRetention` | `engine.runtime.resilience` | Bereinigung entschiedener Approvals |

## 6. Aufwand & Reihenfolge

| Phase | Aufwand | Priorität |
|---|---|---|
| Phase 0 (Housekeeping) | 4–5 Tage | **Hoch** |
| Phase 1 (Core Resilience) | 3–5 Tage | **Hoch** |
| Phase 2 (Safety) | 2 Tage | **Mittel** |
| Phase 4 (Escalation) | 2–3 Tage | **Mittel** |
| Phase 3 (Self-Healing) | 3–5 Tage | **Niedrig** |

**Gesamt: ~15–20 Tage**

### Empfohlene Reihenfolge
1. Phase 0 (Bug-Fixes, sofortiger Mehrwert)
2. Phase 1 (Core Resilience)
3. Phase 2 (Safety)
4. Phase 4 (Escalation, einfach, hoher Mehrwert)
5. Phase 3 (Self-Healing, komplex, hoher Mehrwert)

## 7. Offene Entscheidungen

1. Soll Phase 5 (Checkpoint/Resume) entfallen? QOC hat bereits Session-Persistenz.
2. Soll der Triage-Service ein separates, schnelleres Modell nutzen?
3. Welches Webhook-Format hat Priorität (Slack/Telegram/Generic)?
4. Soll die Question-Resolution über WebSocket statt REST-Polling laufen?
5. Soll `ENGINE_REVERT_SNAPSHOT` default auf `true` gesetzt werden?

## 8. Verifikation

Nach jeder Phase:
- `mvn test` grün
- JaCoCo Coverage ≥ 80%
- ArchUnit-Regeln für neue Pakete
- Integrationstest: `qoc run --resilient "test prompt"` mit simuliertem Fehler
- DB-Prüfung: `idle_outcome` gesetzt, keine leeren Sessions, keine orphaned Approvals
path: /home/torsten/dev/my-projects/2oc/qoc/docs/resilience.md