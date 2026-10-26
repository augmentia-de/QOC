# learnings.md – Gemeinsame Learnings für QOC

> **Status:** Synthese · 2026-10-26 · Entscheidungen D-01–D-14 dokumentiert
> **Quellen:**
> [`oc-open.md`](../analysis/oc-open.md) ·
> [`pi-learnings.md`](../analysis/pi-learnings.md) ·
> [`oh-learnings.md`](../analysis/oh-learnings.md) ·
> [`nessy-learnings.md`](../analysis/nessy-learnings.md)
>
> **Ziel:** Aus den vier Analysen eine **gemeinsame, umsetzbare Backlog-Liste**
> ableiten, die QOC besser, stabiler, schneller und production-ready macht.
>
> **Nicht das Ziel:** Feature-Parität mit OpenCode, pi, oh-my-pi oder Nessy.
> QOC ist eine Java-Engine mit eigenen Stärken (pure `engine.core`, append-only
> Journal, SQL-Persistenz, Rule-Chain, Spezifikation mit Invarianten). Kopieren
> ohne Architekturprüfung würde diese Stärken eher beschädigen.

---

## Inhalt

- [0. Kurzfazit](#0-kurzfazit)
- [1. Vergleich der vier Analysen](#1-vergleich-der-vier-analysen)
- [2. Zielbild: besser, stabiler, schneller, production-ready](#2-zielbild-besser-stabiler-schneller-production-ready)
- [3. Priorisierte Maßnahmen](#3-priorisierte-maßnahmen)
  - [P0 – Production Blocker](#p0--production-blocker)
  - [P1 – Reliability, Qualität und Ergonomie](#p1--reliability-qualität-und-ergonomie)
  - [P2 – Performance und spätere Differenzierung](#p2--performance-und-spätere-differenzierung)
- [4. Reihenfolge und Phasen](#4-reihenfolge-und-phasen)
- [5. Messbare Definition of Done](#5-messbare-definition-of-done)
- [6. Entscheidungslog (D-01–D-14)](#6-entscheidungslog-d-01d-14)
- [7. Quellen-Mapping und Abgrenzung](#7-quellen-mapping-und-abgrenzung)

---

## 0. Kurzfazit

QOC ist architektonisch bereits weiter als viele Referenzen: klare Schichten,
ein reines Core-Modul, ein append-only Event-Journal, SQLite/PostgreSQL,
Claim-basierte Ausführung, eine breite Tool-Palette und eine spezifizierte
Invariantenmenge. Die vier Analysen zeigen deshalb nicht „QOC ist unfertig“,
sondern **wo die nächsten Hebel liegen**.

Die wichtigste Erkenntnis aus dem Vergleich:

> **Die Mehrheit der Quellen optimiert Feature-Ergonomie. Nur Nessy optimiert
> Produktionskorrektheit. Für „production ready“ muss QOC zuerst die
> Nessy-Achse schließen – nicht die Feature-Parität.**

Die größten aktuellen Lücken:

1. **Dauerhafte Nebenwirkungen und Freigaben.** Offene Approvals und
   Tool-Ausführungen überleben keinen Neustart sauber (QOC-009, QOC-011).
2. **Kein explizites Turn-Outcome.** Busy, Refused, Failed und Terminated sind
   für Clients nicht unterscheidbar (QOC-013).
3. **Compaction ist noch nicht token- und cache-stabil.** Turn-Grenzen sind
   verbessert, aber Token-Budget, stabile Kapitel und Checkpoint-Platzierung
   fehlen (QOC-010 teilweise, QOC-012).
4. **Prompt- und Instruktions-Stabilität.** Kein `RULES.md`, keine
   Re-Injection nach Compaction/Revert, keine stabile Blockstruktur.
5. **Betrieb.** Health/Readiness, Metriken, Audit, Last-/Soak-Tests,
   Backup/Restore und Graceful Shutdown sind nicht als Produktionsgate
   definiert.
6. **Tool-Ergonomie.** Anchored Edits, Scan-Cache und Read-Selektoren fehlen;
   das kostet Präzision, Tokens und Zeit.
7. **Orchestrierung.** Typisierte Subagent-Batches, Worktree-Isolation und
   Export fehlen; die vorhandene Task-Registry ist ein guter Anfang.

### Top-10-Liste

| # | Maßnahme | Warum zuerst? |
|---|---|---|
| 1 | **Turn-Outcome-Algebra** (QOC-013) | Ohne sie sind Fehler, Busy und Abbruch für Clients nicht unterscheidbar. |
| 2 | **Idempotency Key + dauerhafter Tool-Call-Lifecycle** (QOC-011) | Verhindert doppelte Side-Effects nach Crash/Retry. |
| 3 | **Dauerhafte Approvals** (QOC-009) | Offene Freigaben dürfen einen Neustart nicht verlieren. |
| 4 | **Crash-Recovery und Reconciliation** | Claims, Tool-Intents und Approvals müssen beim Start konsistent werden. |
| 5 | **Token-aware Compaction + Turn-Integrität** | Stabiler Kontext, weniger Overflow, weniger Retries. |
| 6 | **Stabile Prompt-/Checkpoint-Struktur** (QOC-012) | Cache-Treffer und reproduzierbare Prompts. |
| 7 | **Retry-, Timeout-, Cancel- und Turn-Policy** | Keine unbegrenzten Turns, keine hängenden Prozesse. |
| 8 | **Limits, Backpressure, Graceful Shutdown** | Überlast muss kontrolliert degradieren, nicht crashen. |
| 9 | **Health, Metriken, Audit, Turn-Stats** | Production ohne Beobachtbarkeit ist Blindflug. |
| 10 | **Tool-Ergonomie: Scan-Cache, Read-Selektoren, Anchored Edits** | Schneller, präziser, weniger Token-Verbrauch. |

---

## 1. Vergleich der vier Analysen

### 1.1 Kurzprofile

| Quelle | Stärke | Wichtigste Lehre für QOC | Risiko beim blinden Kopieren |
|---|---|---|---|
| **OpenCode (`oc-open.md`)** | Breite Agent-Ergonomie: Kontextdateien, Commands, Skills, Session-Tree, Queue, Settings | Instruktionen und Sessions müssen benutzbar und wiederaufnehmbar sein | Plugin-/TS-Denken passt nicht in die Java-Engine; zuerst Mechanik, nicht Oberfläche |
| **pi (`pi-learnings.md`)** | Minimaler Kern, klare Compaction- und Prompt-Template-Mechanik | Token-Budget, Turn-Grenzen, Commands, Settings, Export | pi ist absichtlich minimal; QOC darf seine strikten Grenzen nicht aufweichen |
| **oh-my-pi (`oh-learnings.md`)** | Tool-Ergonomie und Kontext-Ökonomie: Hashline, Scan-Cache, LSP, Task-Batches, Approval-Tiers, Artefakte | Präzise Edits, ignorierte Trees, wiederverwendbare Tool-Ausgaben und typisierte Subagents | Sehr breite Produktoberfläche; nicht alles ist Kern der Engine |
| **Nessy (`nessy-learnings.md`)** | Durable Effects, Outcome-Algebra, Turn-Policy, Kapitel-Compaction, dauerhafte Approvals, Idempotency | Produktionskorrektheit: Arbeit ist eine dauerhafte Verpflichtung, nicht nur ein Step-Result | Agent-zentrisches Effect-Modell darf QOCs Tool-Snapshot und Rule-Chain nicht ersetzen |

### 1.2 Themenmatrix

Legende: ✅ = explizit behandelt, ◐ = teilweise/implizit, — = nicht im Fokus.

| Thema | oc | pi | oh-my-pi | Nessy | Konsens | QOC-Konsequenz |
|---|---:|---:|---:|---:|---|---|
| Kontextdateien / Prompt-Blöcke | ✅ | ✅ | ✅ | ✅ | **4/4** | Stabile Blöcke, `RULES.md`, Imports, Re-Injection |
| Compaction an Turn-Grenzen | ✅ | ✅ | ✅ | ✅ | **4/4** | Token-Budget + vollständige Turns + stabile Kapitel |
| Session-Tree / Fork / Export | ✅ | ✅ | ✅ | ◐ | **3/4** | Fork/Tree sind da; Export und Branch-Summary fehlen |
| Tool-Output begrenzen und zurücklesen | ✅ | ◐ | ✅ | ◐ | **3/4** | Artefakte sind da; Streaming/Retention vervollständigen |
| Stabile Cache-Prefixe | ✅ | ✅ | ✅ | ✅ | **4/4** | Checkpoint nicht an Index 0; statische Blöcke zuerst |
| Subagent-Orchestrierung | ◐ | ◐ | ✅ | ◐ | **3/4** | Typisierte Batches, Wait/Cancel, Isolation |
| Approval-Tiers / Modi | ✅ | ◐ | ✅ | ✅ | **3/4** | READ/WRITE/EXEC, target-aware Grants |
| Durable Approvals | — | — | ◐ | ✅ | **1/4, kritisch** | P0: überlebt Neustart, Deadline, Facts |
| Idempotency / durable Effects | — | — | — | ✅ | **1/4, kritisch** | P0: Key + Intent/Result + Reconciliation |
| Outcome-Algebra | ◐ | ◐ | ◐ | ✅ | **1/4, kritisch** | P0: Busy/Refused/Failed/Terminated |
| Turn-Policy | — | ◐ | ◐ | ✅ | **1/4, kritisch** | P0/P1: Continue/AnswerNow/FailTurn |
| Observability / Trajectory | ◐ | ◐ | ✅ | ✅ | **3/4** | Turn-Stats, Metriken, Audit, später Trajectory |
| Model-Rollen / Capabilities | — | ◐ | ✅ | ◐ | **2/4** | Erst mit zweitem Provider sinnvoll |
| Memory / Advisor / TTSR | — | — | ✅ | — | **1/4** | Später; zuerst `RULES.md` |
| LSP / IDE-Tiefe | — | — | ✅ | — | **1/4** | P2; hoher Aufwand, hoher Nutzen für Code-Qualität |
| Hashline / anchored Edits | — | — | ✅ | — | **1/4** | P1; hoher Qualitätshebel |
| Scan-Cache | — | — | ✅ | — | **1/4** | P1/P2; Performance-Hebel |

### 1.3 Was alle vier sagen

Diese Punkte haben echten Konsens und sollten als **Designprinzipien** gelten:

1. **Compaction darf keine Tool-Interaktion zerschneiden.**
   Immer ganze Turns behalten; Tool-Call und Tool-Result gehören zusammen.
2. **Der Kontext braucht eine stabile Struktur.**
   Statische System-/Instruktionsblöcke zuerst, projektbezogene Inhalte danach,
   aktiver Turn zuletzt. Das ist gut für Cache, Reproduzierbarkeit und Debugging.
3. **Zusammenfassungen sind eine Sicht, keine Wahrheit.**
   Das Rohlog bleibt append-only; die Compaction ist eine Projektion.
4. **Sessions müssen wiederaufnehmbar und verzweigbar sein.**
   Tree/Fork/Export sind keine Luxusfunktionen, sondern Recovery- und
   Debug-Werkzeuge.
5. **Tool-Ausgaben müssen begrenzt und trotzdem vollständig erreichbar sein.**
   Sichtbarer Prefix + stabiler Artifact-Verweis statt stiller Kürzung.
6. **Subagents brauchen klare Ergebnisse und Isolation.**
   Kein „fire and forget“, keine verschluckten Fehler, keine kollidierenden
   Worktrees.
7. **Berechtigungen brauchen eine explizite Kette.**
   `deny` vor gespeicherter Freigabe, `ask` vor `allow`, Abbruch getrennt von
   sichtbarem Tool-Fehler.

### 1.4 Was nur eine Quelle sagt – und trotzdem P0 ist

Die Konsensmatrix darf nicht als Abstimmung missverstanden werden. Diese
Punkte kommen fast nur aus Nessy, sind aber **Produktionsblocker**:

| Nessy-Lehre | QOC-Problem | Warum P0 |
|---|---|---|
| Durable Approvals mit Fakten und Deadline | QOC-009 | Ein Neustart darf keine offene Freigabe verlieren; sonst hängt der Claim. |
| Idempotency Key für Tool-Calls | QOC-011 | At-least-once-Ausführung braucht einen stabilen Korrelationsschlüssel. |
| Explizite Outcome-Algebra | QOC-013 | Clients können „busy“, „refused“, „failed“ und „terminated“ nicht unterscheiden. |
| Staged Turn Policy | `maxSteps` allein | Ein Turn kann durch Tool-Schleifen, Tokens, Zeit oder Kosten unbegrenzt werden. |
| Effect-/Outbox-Denken | Claims + Journal | Recovery muss wissen, was angekündigt, was ausgeführt und was offen ist. |
| Usage-/Trajectory-Projektionen | OTel + Token-Zähler | Betrieb und Kostenoptimierung brauchen Turn-Statistiken, nicht nur Summen. |

### 1.5 Was QOC bereits hat

Laut aktuellem QOC-Findings-Register und Code-Stand (Commit `e478f19`, teilweise
uncommittete Nacharbeiten):

| ID | Thema | Status | Konsequenz für diese Liste |
|---|---|---|---|
| QOC-001 | Shell-Output und Background-Prozesse | DONE | Nicht erneut priorisieren; höchstens Streaming/Prozessgruppen prüfen |
| QOC-002 | Compaction-Summary sicher aktualisieren | DONE | SHA-256 und Predecessor-Text sind vorhanden |
| QOC-003 | Background-Subagent-Fehler sichtbar | DONE | Task-Registry ist da; typisierte Batches fehlen weiterhin |
| QOC-004 | Search/Glob auf realen Trees | DONE | Ignore/Hidden/Binary/Timeout/Paging sind da; Scan-Cache fehlt |
| QOC-005 | Tool-Output als Artefakt | DONE | `ArtifactRef`, `artifact://`, Read/Grep-Auflösung vorhanden |
| QOC-006 | Session-Fork und Tree | DONE | Fork/Tree vorhanden; Export/Branch-Summary fehlen |
| QOC-007 | Tool-Katalog-Leak nach Compaction | DONE | Regressionstest im Auge behalten |
| QOC-008 | PermissionDenied als HTTP 500 | DONE | Domänenfehler-Mapping weiter absichern |
| QOC-009 | Ephemere Approvals | **OPEN** | **P0-03** |
| QOC-010 | Compaction zerschneidet Turn | DONE (Turn-Grenze) | Token-Budget und Kapitelstruktur bleiben offen → **P0-05** |
| QOC-011 | Kein Idempotency Key | **OPEN** | **P0-02 / P0-04** |
| QOC-012 | Checkpoint an Index 0 | **OPEN** | **P0-06** |
| QOC-013 | Keine Outcome-Algebra | **OPEN** | **P0-01** |

Zusätzlich vorhanden und zu erhalten:

- TLS/Basic Auth und öffentliche Erreichbarkeit über Nginx (Betrieb, nicht
  Engine-Feature).
- Native Binary und systemd-Service.
- Breite Tool-Palette inkl. MCP, Skills, Subagents, Worktrees, Web.
- Contract-Tests und ArchUnit-Regeln als Basis für weitere Härtung.
- JaCoCo-Gate von 80 % Instruction Coverage.

### 1.6 Widersprüche und Unsicherheiten

- **QOC-010:** Das Register sagt `DONE`, weil die Turn-Grenze repariert wurde.
  Token-Budget, Cache-Stabilität und kumulative Dateilisten sind aber noch
  offen. Diese Synthese behandelt QOC-010 deshalb als **teilweise erledigt**.
- **QOC-007/QOC-008:** In den Detailabschnitten sind teilweise noch
  Checkboxen offen, obwohl die Übersicht `DONE` sagt. Maßgeblich ist der
  Code-Stand; die Checkboxen sollten nachgezogen werden.
- **QOC-009/011/012/013:** Diese vier sind die klaren offenen Produktionslücken.
- **Referenzstände:** `oh-learnings.md` vergleicht oh-my-pi `0b5c27a` mit QOC
  `24f0273`; `nessy-learnings.md` vergleicht Nessy `0c26499`. QOC ist seitdem
  weitergelaufen. Aussagen sind als Muster zu lesen, nicht als exakte
  Dateiinventur.
- **`qoc-findings.md`:** Enthält uncommittete Änderungen. Diese Datei nicht
  überschreiben; die Synthese verlinkt nur.

---

## 2. Zielbild: besser, stabiler, schneller, production-ready

### 2.1 Vier Qualitätsachsen

| Achse | Konkretes Ziel | Messgröße |
|---|---|---|
| **Besser** | Weniger Retries, präzisere Edits, stabiler Kontext, klare Ergebnisse | Retry-Rate, Edit-Fehlerrate, Kontext-Overflows, Task-Erfolgsrate |
| **Stabiler** | Crash-sicher, idempotent, begrenzt, explizit fehlerhaft | Stuck Claims, doppelte Side-Effects, verlorene Approvals, 24h-Soak |
| **Schneller** | Kürzere Time-to-First-Token, weniger Tokens, weniger Scans | p95-Latenz, Token pro Turn, Scan-Zeit, Cache-Hit-Rate |
| **Production-ready** | Sicher, beobachtbar, wiederherstellbar, überlastbar | Health/Metrics, Backup-Restore, Lasttest, Security-Review |

### 2.2 Nicht-Ziele (bewusst nicht jetzt)

Diese Punkte aus den Referenzen sind attraktiv, aber keine aktuellen
Produktionsblocker. Sie gehören in P2/P3 oder bleiben dauerhaft draußen:

- Vollständiger Plugin-/Marketplace-Runtime (TypeScript/JS) – QOC ist Java;
  MCP und eine kleine Java-SPI sind die besseren Grenzen.
- 60+-Provider-Katalog und KDL-Kompatibilitätskorpus – erst mit echtem Bedarf
  und mehreren Adaptern.
- Snapcompact (Historie als Bilder) – providerabhängig, teuer, opak.
- Autonome Memory-Pipelines, Advisor-Modell und TTSR – zuerst `RULES.md` und
  manuell kuratierte Projektregeln.
- Persistente Shells/Services – Background-Jobs und Prozess-Registry reichen
  für den Serverbetrieb.
- Encrypted Share Relay / Collaboration – Export genügt.
- Exactly-once-Ausführung – die Spezifikation erlaubt at-least-once; QOC sollte
  das nicht überversprechen.
- Vollständiger Nessy-Effect-Umbau – zuerst minimale Reconciliation, dann
  entscheiden, ob eine echte Effect-Tabelle nötig ist.

---

## 3. Priorisierte Maßnahmen

Die IDs sind bewusst unabhängig von QOC-001…013, damit die Liste als Backlog
verwendet werden kann. Jede Maßnahme nennt Ziel, konkrete Änderungen,
Acceptance und Tests.

### P0 – Production Blocker

#### P0-01 – Turn-Outcome-Algebra (QOC-013)

**Warum:** `PromptResult` transportiert heute nur `finish`, Text, Usage und
optional Structured Output. Ein Client kann nicht unterscheiden, ob ein Turn
gar nicht lief (busy), vom Modell abgelehnt wurde, technisch fehlschlug oder
abgebrochen wurde. Das macht Retries und UI-Zustände unsicher.

**Konkret:**
- Versiegeltes `TurnOutcome` einführen:
  - `Completed(finish, usage, text, structured)`
  - `Busy(retryAfter)`
  - `Refused(reason, providerCode)`
  - `Failed(error, retryable)`
  - `Terminated(reason)`
  - `Cancelled`
- `Engine.runPrompt` und `PromptResult` darauf umstellen; Usage nur bei
  tatsächlich gelaufenem Turn.
- HTTP-Mapping explizit: 200 / 409 / 422 / 502–503 / 408–499; keine
  Domänenfehler als 500.
- SSE-Terminalframe (`turn.completed` / `turn.failed`) mit demselben Outcome.
- Refused darf **nicht** als modellseitiger Tool-Fehler erscheinen.

**Acceptance:**
- Busy, Refused, Failed, Terminated und Cancelled sind im API-Response und im
  SSE-Stream unterscheidbar.
- Ein abgelehnter Turn erzeugt keine erfundene Assistant-Nachricht.
- Retry-Entscheidungen sind pro Outcome dokumentiert.

**Tests:** busy session, terminated session, model refusal, provider error,
timeout, cancel, SSE-Terminalframe, HTTP-Status-Mapping.

**Abhängigkeiten:** keine; sollte zuerst kommen, weil alle anderen Fehlerpfade
davon profitieren.

**Aufwand/Risiko:** M / niedrig.

---

#### P0-02 – Idempotency Key und dauerhafter Tool-Call-Lifecycle (QOC-011)

**Warum:** QOC garantiert at-least-once. Nach einem Crash kann ein Tool erneut
ausgeführt werden. Ohne stabilen Schlüssel kann ein side-effecting Tool das
nicht erkennen. `ToolContext` hat heute nur Session-, Agent-, Message- und
Call-ID.

**Konkret:**
- `IdempotencyKey` als eigenes Value Object; erzeugt **bei der Ankündigung**
  des Tool-Calls, nicht erst bei der Ausführung.
- Key in `ToolContext`, `ToolCall`, Tool-Events, Approval-Request und
  Subagent-Auftrag transportieren.
- Vor dem Side-Effect ein Intent-Event persistieren; nach Abschluss ein
  Result-Event. Der Key verbindet beide.
- Tool-Vertrag ergänzen: `readOnly` / `sideEffecting` und optional
  `supportsIdempotency`.
- Recovery: Intent ohne Result erkennen; read-only sicher wiederholen;
  side-effecting nur mit Dedupe oder explizit `Unknown`/`NeedsReview` melden.
- Keine Exactly-once-Versprechung; die Semantik bleibt at-least-once.

**Acceptance:**
- Ein wiederhergestellter Tool-Call ist über den Key eindeutig identifizierbar.
- Ein side-effecting Tool kann anhand des Keys deduplizieren.
- Ein Crash zwischen Intent und Result erzeugt einen expliziten Zustand, keinen
  stillen Verlust.

**Tests:** Crash zwischen Intent/Result, doppelte Delivery, Retry, Key-Stabilität
über Resume, Approval und Tool-Call teilen denselben Key, read-only vs.
side-effecting.

**Abhängigkeiten:** P0-03 (Approvals) und P0-04 (Recovery) teilen den Key.

**Aufwand/Risiko:** M / mittel (Event-/Persistenzänderung).

---

#### P0-03 – Dauerhafte Approvals (QOC-009)

**Warum:** `PendingApprovals` ist ein In-Memory-Map. Ein Neustart bei offener
Freigabe verliert den Request, während der Execution-Claim weiterlebt. Das ist
ein echter Produktionsfehler.

**Konkret:**
- Tabelle `approval_request` (Flyway-Migration):
  `id`, `session_id`, `project_id`, `action`, `resource`, `tier`,
  `facts_json`, `status`, `created_at`, `deadline_at`, `decided_at`,
  `decided_by`, `decision`, `reason`, `idempotency_key`.
- Port `ApprovalRequestStore` mit `create`, `pending(session)`, `decide`,
  `expire`, `find`, `recover`.
- `PermissionEngine` schreibt den Request **vor** dem Warten; die
  `ApprovalSource` wartet auf Store/Doorbell statt auf eine rein lokale
  Future.
- Deadline: abgelaufene Requests werden deterministisch denied/aborted; kein
  Thread bleibt unbegrenzt blockiert.
- HTTP/SSE: `GET /api/session/{id}/approvals`,
  `POST /api/session/{id}/approvals/{id}`, Event bei Entscheidung.
- Recovery: offene Requests nach Neustart sichtbar machen; verwaiste Requests
  ablaufen lassen oder erneut veröffentlichen.

**Acceptance:**
- Eine offene Freigabe ist nach einem Neustart sichtbar.
- Deadline, Fakten und Entscheidung überleben den Neustart.
- Doppelte Entscheidungen sind idempotent; Entscheidung nach Ablauf ist
  explizit.
- Kein Claim bleibt wegen einer verlorenen Freigabe hängen.

**Tests:** Restart mit offener Freigabe, Deadline, doppelte Antwort,
Antwort nach Ablauf, Cross-Session-Isolation, Approval mit Idempotency Key.

**Abhängigkeiten:** P0-02 (Key), Migration, evtl. P0-08 (Parking statt Blocking).

**Aufwand/Risiko:** M–L / mittel.

---

#### P0-04 – Crash-Recovery und Reconciliation

**Warum:** QOC hat Claims und ein Journal, aber der Startpfad muss systematisch
klären: Welche Claims sind offen? Welche Tool-Calls wurden angekündigt, aber
nicht beendet? Welche Approvals sind offen? Welche Background-Tasks sind mit
dem Prozess verloren? Nessys Effect-Denken liefert die richtige Frage, auch
wenn QOC nicht sofort eine vollständige Effect-Tabelle braucht.

**Konkret:**
- Startup-Reihenfolge definieren und testen:
  1. Migrationen/DB-Verfügbarkeit prüfen.
  2. Offene Claims laden.
  3. Tool-Intents ohne Result reconcilen.
  4. Offene Approvals laden/ablaufen lassen.
  5. In-Memory-Background-Tasks als `LOST` markieren (sie überleben den
     Prozess nicht; das muss sichtbar sein).
  6. Prozess-Registry bereinigen.
  7. Warteschlangen/Doorbell neu aufbauen.
- Jeder Recovery-Fall endet in einem expliziten Zustand: `Resumed`,
  `Failed(reason)`, `NeedsReview`, `Expired`.
- Kein stilles Verschlucken; Recovery-Events im Journal.
- Vollständige Effect-/Outbox-Tabelle erst danach entscheiden (P1-08).

**Acceptance:**
- Crash an jedem definierten Punkt führt zu einem expliziten Ergebnis.
- Kein Claim bleibt dauerhaft `RUNNING`.
- Verlorene Background-Tasks sind sichtbar, nicht „ewig laufend“.
- Recovery ist idempotent (mehrfacher Start schadet nicht).

**Tests:** Fault-Injection an Intent/Result/Approval/Claim/Shutdown; doppelter
Start; DB weg während Start; verwaiste Prozesse.

**Abhängigkeiten:** P0-02, P0-03.

**Aufwand/Risiko:** M–L / hoch (Kernstabilität).

---

#### P0-05 – Token-aware Compaction und Turn-Integrität

**Warum:** QOC schneidet heute nach `keepMessages`, nicht nach Token-Budget.
QOC-010 hat die Turn-Grenze verbessert, aber ein einzelner großer Turn oder ein
riesiges Tool-Result kann weiterhin das Kontextfenster sprengen. Alle vier
Analysen sehen Compaction als zentrales Stabilitätsproblem.

**Konkret:**
- `CompactionPolicy` mit `reserveTokens`, `keepRecentTokens`,
  `triggerRatio`, `enabled`.
- Trigger **vor** dem Provider-Overflow: `contextTokens >
  contextWindow - reserveTokens`.
- Token-Schätzer pro Message/Tool-Result mit Sicherheitsmarge; exakte
  Tokenizer später.
- Cut nur zwischen vollständigen Turns; Tool-Call und Tool-Result bleiben
  zusammen; der letzte User-Turn bleibt vollständig.
- `firstKeptSeq`, `tokensBefore`, `summary`, kumulative Read-/Modified-Files
  speichern.
- Manuelle Compaction mit optionalen Instruktionen.
- Bei einem einzelnen überlangen Turn: Turn-Prefix-Summary + History-Summary
  (pi-Muster), nicht mitten im Tool-Exchange schneiden.

**Acceptance:**
- Kein Context-Overflow durch normale lange Sessions.
- Keine Compaction trennt Tool-Call und Result.
- Der letzte Turn bleibt vollständig.
- Die zweite Compaction sieht die erste Summary.
- Token-Budget und tatsächliche Usage sind nachvollziehbar.

**Tests:** langes Tool-Result, ein Turn größer als das Budget, wiederholte
Compaction, exakte Grenze, kumulative Dateilisten, manuelle Instruktionen.

**Abhängigkeiten:** P0-06 (Layout), QOC-002 (Summary-Update, bereits DONE).

**Aufwand/Risiko:** M / mittel.

---

#### P0-06 – Stabile Prompt-/Checkpoint-Struktur (QOC-012)

**Warum:** `ContextBuilder` fügt den Compaction-Checkpoint an Index 0 der
Message-Liste ein. Jede Compaction verändert damit den Anfang des
Modell-Prompts und kann den Cache-Prefix invalidieren. Nessy und oh-my-pi
zeigen die stabilere Struktur: statische Blöcke zuerst, Kapitel/Summary
append-only, aktiver Turn zuletzt.

**Konkret:**
- Prompt-Struktur explizit:
  1. statischer Base-Prompt,
  2. Skills-/Tool-Guidance,
  3. globale/sticky Regeln,
  4. Projektkontext,
  5. abgeschlossene Compaction-Kapitel (append-only),
  6. recent Tail,
  7. aktiver Turn.
- Checkpoint nicht mehr an Message-Index 0; als eigenes Kapitel bzw. eigener
  System-/Context-Block vor dem Tail.
- Alte Kapitel nicht bei jeder Compaction neu schreiben; neue Summary
  anhängen. Gelegentliches Merge ist ein bewusster Cache-Bruch.
- Datum/Zeit/cwd **am Ende** des Prompts, nicht am Anfang.
- Cache-Affinity-Header und Prompt-Cache-Key beibehalten; Prefix-Stabilität
  messbar machen.

**Acceptance:**
- Eine Compaction ändert den statischen Prefix nicht.
- Der Checkpoint liegt nicht an Index 0.
- Ein Request-Capture zeigt die erwartete Blockreihenfolge.
- Cache-Hit-Rate wird vor/nach Compaction gemessen.

**Tests:** Request-Capture vor/nach Compaction, zwei Compactions, leerer
Kontext, Fork, verschiedene Workspaces.

**Abhängigkeiten:** P0-05.

**Aufwand/Risiko:** M / mittel (Prompt-Layout ist semantisch heikel).

---

#### P0-07 – Retry-, Timeout-, Cancel- und Turn-Policy

**Warum:** `maxSteps` ist die einzige Begrenzung. Ein Turn kann durch
Tool-Schleifen, langsame Provider, hängende Prozesse oder hohe Kosten
unbegrenzt werden. pi liefert konkrete Retry-Defaults; Nessy liefert die
Turn-Policy.

**Konkret:**
- `RetryPolicy`: klassifizierte Fehler, exponentielle Backoff mit Jitter,
  Max-Retries, Max-Delay, `Retry-After` respektieren. Nicht retryen, wenn
  bereits sichtbarer Output committed wurde oder der Fehler nicht retrybar ist.
- `TurnPolicy`: `Continue`, `AnswerNow`, `FailTurn` anhand von
  `maxSteps`, `maxTokens`, `maxWallTime`, `maxCost`.
- Timeouts pro Tool, Shell, HTTP, MCP und Subagent; harte Obergrenzen.
- Cancellation-Propagation: Session-Interrupt → Provider-Stream abbrechen →
  Prozessgruppe killen → Subagent canceln → Ressourcen freigeben.
- Kein Thread-Leak, kein hängender Prozess, kein „ewig laufender“ Turn.

**Acceptance:**
- Ein Turn endet deterministisch mit Continue/AnswerNow/FailTurn.
- Ein Abbruch beendet Provider-Stream, Tool-Prozess und Subagent.
- Retry-After über dem Cap führt zu schnellem Fehlschlag statt langem Warten.
- Keine Retries nach bereits sichtbarem Output.

**Tests:** Provider-Timeout, Retry-After, partieller Stream, Cancel während
Tool, Cancel während Shell, Subagent-Cancel, Kostenlimit.

**Abhängigkeiten:** P0-01 (Outcome), P0-08 (Limits).

**Aufwand/Risiko:** M / mittel.

---

#### P0-08 – Limits, Backpressure und Graceful Shutdown

**Warum:** Ohne harte Limits kann ein einzelner Client oder eine Schleife den
Prozess, die DB oder den Provider erschöpfen. Production-ready heißt:
Überlast degradiert kontrolliert.

**Konkret:**
- Konfigurierbare Limits: max. Sessions, max. Steps, max. Tool-Calls/Turn,
  max. Subagents, max. Request-Bytes, max. Output, max. Context, max. Kosten,
  max. Wall-Time.
- Admission Control: 429 mit `Retry-After` bei Sättigung; bounded Queues;
  faire Reihenfolge.
- DB-Pool, HTTP-Connection-Pool und Virtual-Thread-Nutzung begrenzen.
- Graceful Shutdown: keine neuen Requests, laufende Turns drainen oder
  abbrechen, Prozesse killen, Events flushen, Claims gemäß Spezifikation
  behandeln, Registry schließen.
- Kein `OutOfMemoryError` durch unbegrenzte Artefakte/Queues.

**Acceptance:**
- Überlast erzeugt 429/Backpressure, keinen Crash.
- Shutdown hinterlässt keinen hängenden Prozess und keinen unklaren Claim.
- Speicher wächst im 24h-Soak nicht unbegrenzt.
- Queue-Tiefe und Pool-Auslastung sind sichtbar.

**Tests:** Lasttest, Sättigung, Shutdown während Turn/Tool, Prozess-Cleanup,
Queue-Limits, Speicher-Soak.

**Abhängigkeiten:** P0-07.

**Aufwand/Risiko:** M / mittel.

---

#### P0-09 – Health, Metriken, Audit und Turn-Stats

**Warum:** Production-ready ohne Beobachtbarkeit ist Blindflug. QOC hat
OpenTelemetry-Spans und Token-Zähler, aber keine klaren Health-/Readiness-
Verträge und keine Turn-Projektion.

**Konkret:**
- `GET /health/live` (Prozess) und `GET /health/ready` (DB, Migrationen,
  Workspace, Provider-Key, Disk, Queue).
- Metriken: Requests, Sessions, Turn-Dauer, Tokens, Kosten, Tool-Calls,
  Fehler nach Outcome, Retries, Queue-Tiefe, offene Approvals, Compaction-
  Dauer, DB-Pool.
- Strukturierte Logs mit `sessionId`, `turnId`, `toolCallId`,
  `idempotencyKey`; Secret-Redaction.
- Audit-Events für Permission-Entscheidungen, Tool-Ausführung und
  Approval-Änderungen.
- `TurnStats`-Projektion (Nessy): Steps, Tool-Calls, Tokens, Kosten, Dauer,
  Retries, Outcome. Trajectory später.

**Acceptance:**
- Readiness schlägt fehl, wenn DB/Migration/Workspace nicht bereit sind.
- Jeder Turn ist über IDs in Logs und Metriken auffindbar.
- Keine Secrets in Logs.
- Offene Approvals und Queue-Tiefe sind alarmierbar.

**Tests:** Health bei DB-Ausfall, Migration fehlt, Workspace fehlt; Log-
Redaction; Metrik-Endpunkt; Trace-Korrelation.

**Abhängigkeiten:** P0-01 (Outcome), P0-08 (Limits).

**Aufwand/Risiko:** M / niedrig–mittel.

---

### P1 – Reliability, Qualität und Ergonomie

#### P1-01 – Prompt- und Instruktions-Stabilität

**Warum:** Alle vier Analysen behandeln Kontextdateien. QOC lädt `AGENTS.md`,
`SYSTEM.md` und `APPEND_SYSTEM.md`, aber es gibt keinen Ledger für
Re-Injection nach Compaction/Revert, kein `RULES.md`, keine `@path`-Imports
und keine stabile Blockstruktur.

**Konkret:**
- `RULES.md` als sticky Regel, die bei **jedem** Request mitgeht.
- `@path`-Imports: relativ zum importierenden File, `~/` und absolut,
  max. 5 Hops, Zyklen überspringen, Code-Fences nicht expandieren.
- Re-Injection nach Compaction/Revert über synthetische Messages mit
  Metadaten-Ledger (oc) oder äquivalente persistente Instruction-Entries.
- Projektkontext in einen trailing Block; statische Blöcke davor.
- Datum/Zeit/cwd am Ende.
- Deterministische Reihenfolge und Dedupe.

**Acceptance:**
- Instruktionen sind nach Compaction/Revert wieder sichtbar.
- Import-Zyklen und Code-Fences sind getestet.
- `RULES.md` überlebt lange Sessions.
- Blockreihenfolge ist deterministisch.

**Tests:** Re-Injection, Import-Zyklus, Code-Fence, `RULES.md` über
Compaction, Reihenfolge, Dedupe.

**Abhängigkeiten:** P0-06.

**Aufwand/Risiko:** M / mittel.

---

#### P1-02 – Session-Export und Branch-Summary

**Status (D-11):** Der Export ist **zurückgestellt**. Es gibt aktuell keinen
Export; die Redaction-Policy ist dokumentiert, die Implementierung folgt erst
bei konkretem Bedarf. Die Branch-Summary kann unabhängig davon umgesetzt
werden.

**Warum:** Fork/Tree sind vorhanden, aber es gibt keinen Export. Ohne Export
sind Debugging, Übergabe und Audit schwach. Branch-Summaries fehlen, wenn ein
Tree-Pfad verlassen wird.

**Konkret:**
- `GET /api/session/{id}/export?format=markdown|json` mit:
  System-Prompt, Tool-Definitionen, Transcript, Tool-Cards, Usage, Artefakt-
  Referenzen, Redaction von Secrets.
- Branch-Summary beim Fork/Tree-Wechsel (Ziel, Entscheidungen, Dateien,
  offene Fragen).
- Export-Tests für große Sessions und redigierte Felder.

**Acceptance:**
- Markdown/JSON-Export ist vollständig und redigiert.
- Ein Fork kann eine Branch-Summary erzeugen.
- Artefakte bleiben referenzierbar.

**Tests:** Export mit Secrets, großen Tool-Ausgaben, Fork, Branch-Summary.

**Abhängigkeiten:** QOC-006 (DONE), P1-01.

**Aufwand/Risiko:** S–M / niedrig.

---

#### P1-03 – Typisierte Subagent-Batches und Worktree-Isolation

**Warum:** Die Background-Task-Registry ist da (QOC-003), aber `subagent`
liefert weiterhin nur Text; es gibt keine `tasks[]`-Batches, keine
`outputSchema`-Validierung und keine Isolation. oh-my-pi zeigt den Nutzen:
parallele, typisierte, konfliktfreie Arbeit.

**Konkret:**
- `tasks[]` mit `name`, `agent`, `task`, `outputSchema`, `tools`, `cwd`,
  `isolated`.
- `SubagentResult` mit `text`, optional `structured`, Usage, Child-Session.
- `SubmitResult`-Mechanik für strukturierte Child-Ergebnisse wiederverwenden.
- Worktree nur bei Git-Repo; Ergebnis mit Pfad und Diff zurückgeben; **kein**
  Auto-Merge.
- Depth-Limit und Semaphore beibehalten; Fehler sichtbar machen.

**Acceptance:**
- Ein Batch mit mehreren Tasks liefert typisierte Ergebnisse.
- Ein isolierter Task arbeitet in einem eigenen Worktree.
- Fehler/Cancel sind sichtbar; keine stillen Verluste.
- Depth- und Concurrency-Limits greifen.

**Tests:** Schema-Validierung, ein Task scheitert, Cancel, Worktree-Cleanup,
parallele Batches, Depth-Limit.

**Abhängigkeiten:** QOC-003 (DONE), P0-01.

**Aufwand/Risiko:** M–L / mittel.

---

#### P1-04 – Approval-Tiers, Modi und target-aware Grants

**Warum:** QOC hat eine gute Rule-Chain, aber `ToolOptions.permission` ist ein
freier String, es gibt keine READ/WRITE/EXEC-Tiers, keine Modi und keine
target-aware Grants. oh-my-pi und Nessy zeigen, wie man Freigaben präziser und
sicherer macht.

**Konkret:**
- `ApprovalTier { READ, WRITE, EXEC }` an Tool-Definitionen.
- `ApprovalMode` (`ALWAYS_ASK`, `ALWAYS_ASK_CACHED`, `WRITE`, `YOLO`);
  QOC-Default bleibt `ALWAYS_ASK`.
- `SavedApproval` um Tier und Target-Scope erweitern; Migration.
- Datei-Grants nach resolved Path und Selector; Tree-Grant für rekursive
  Suche.
- Shell-Pattern-Policy (allow/deny/prompt) für kritische Kommandos.
- Approval nach Hook-Input-Änderung erneut auflösen; ohne interaktiven Kanal
  fail-closed.

**Acceptance:**
- Ein Read-Grant erlaubt kein Write/Exec.
- Ein Write-Grant deckt Reads desselben Targets ab.
- Kritisches Shell-Kommando ist ohne Freigabe blockiert.
- Hook-Änderung löst erneute Prüfung aus.

**Tests:** Tier-Hierarchie, Target-Scope, Shell-Pattern, Hook-Reevaluation,
headless fail-closed.

**Abhängigkeiten:** P0-03.

**Aufwand/Risiko:** M / mittel.

---

#### P1-05 – Scan-Cache, Read-Selektoren und Such-Ergonomie

**Warum:** QOC-004 hat Ignore/Hidden/Binary/Timeout/Paging gelöst. Es fehlt
weiterhin ein Scan-Cache, und `read` hat keine Range-/Raw-Selektoren oder
große-Datei-Zusammenfassung. Das kostet Zeit und Tokens.

**Konkret:**
- `CachedFileSystem`-Decorator für Directory-Walks:
  Key = Root + effektive Walk-Options; TTL ~1 s; 16 Einträge; Invalidation
  bei Write/Delete/Rename aus der Engine.
- `read` mit explizitem `range`/`raw`-Feld (kein Pfad-Suffix-Zwang wegen
  Doppelpunkt in Dateinamen).
- Einfache zeilenbasierte Zusammenfassung für große Dateien; AST-Summary
  später.
- Paging-Metadaten statt stillem 100-Hit-Cap (bereits verbessert).
- Tool-Ausgaben mit klarer „partial/timed out“-Kennzeichnung.

**Acceptance:**
- Wiederholte Glob/Grep-Läufe im selben Turn sind messbar schneller.
- Shell-erzeugte Dateien werden spätestens nach TTL sichtbar.
- Read-Range liefert exakt die gewünschten Zeilen.
- Große Dateien werden nicht ungebremst in den Kontext gezogen.

**Tests:** Cache-Hit, TTL, Invalidation, Shell-Datei, Range, Raw, große Datei.

**Abhängigkeiten:** QOC-004 (DONE).

**Aufwand/Risiko:** S–M / niedrig.

---

#### P1-06 – Anchored Edits / Hashline (opt-in)

**Warum:** `EditTool` ist exakte `old`-String-Ersetzung. Bei wiederholten
Snippets, verschobenen Zeilen und großen Refactorings kostet das Retries.
oh-my-pi zeigt mit Hashline den größten Qualitätshebel: Content-Tag,
Seen-Ranges, Multi-Hunk, Stale-Erkennung.

**Konkret:**
- `EditStore` (session-scoped, zunächst in-memory): Snapshot, Content-Hash,
  Seen-Ranges.
- `read`/`grep` liefern `[path#tag]`-Header und zeichnen emittierte Ranges auf.
- Parser für `PUT N.=M`, `PUT <N`, `PUT >N`, `PUT >$`, `CUT N.=M`, `REM`,
  `MV`, benannte Register.
- Alle Sections vor dem Schreiben validieren; überlappende Ranges ablehnen;
  Stale-Tag mit aktuellem Kontext melden.
- `edit.mode` mit konservativem Default `replace`; Hashline opt-in pro
  Agent/Modell.
- Kein Tree-sitter-Block `N*` in Version 1; klare Fehlermeldung statt Raten.

**Acceptance:**
- Ein veralteter Tag wird abgelehnt und liefert den aktuellen Tag.
- Mehrere Hunks in einer Datei funktionieren ohne Zeilenverschiebung.
- Multi-File-Edit ist atomar vorbereitet.
- `replace` bleibt als Fallback verfügbar.

**Tests:** Stale-Tag, Multi-Hunk, wiederholter Anchor, Indentation, CRLF,
Binary/Symlink, Multi-File, Überlappung.

**Abhängigkeiten:** P1-05 (Seen-Ranges).

**Aufwand/Risiko:** L / mittel–hoch.

---

#### P1-07 – Security-Härtung

**Warum:** Production-ready heißt nicht nur TLS. Die Referenzen zeigen
Angriffsflächen: SSRF über `webfetch`/MCP, Path Traversal, Prompt Injection
über Tool-Ausgaben, Secret-Leaks in Logs, zu breite Shell-Rechte.

**Konkret:**
- SSRF-Schutz für `webfetch` und MCP: Allow-/Deny-Listen, private IPs,
  Redirects, DNS-Rebinding, Größen-/Timeout-Limits.
- Path-Traversal- und Symlink-Prüfung für alle FS-Tools und Artefakte.
- Prompt-Injection-Härtung: Tool-Ausgaben klar delimitieren, keine
  Instruktionen aus Tool-Output als Systeminhalt behandeln.
- Secret-Redaction in Logs, Traces, Export und Fehlermeldungen.
- Rate Limits und Request-Size-Limits.
- Approval fail-closed ohne UI-Kanal.
- Dependency-/Container-Scan und SBOM.

**Acceptance:**
- SSRF- und Traversal-Tests schlagen nicht durch.
- Keine Secrets in Logs/Export.
- Ein fehlender Approval-Kanal blockiert statt zu erlauben.
- Rate Limits greifen.

**Tests:** SSRF-URLs, Redirect-Ketten, `../`, Symlink, Secret im Prompt,
Export-Redaction, Rate-Limit.

**Abhängigkeiten:** P0-08, P1-04.

**Aufwand/Risiko:** M / hoch (Sicherheit braucht Review).

---

#### P1-08 – Vollständige Effect-/Outbox-Tabelle (bedingt)

**Warum:** Nessys zentrales Muster ist eine dauerhafte Effect-Tabelle mit
Deadlines, Retries und gefenced Completion. QOC braucht das **vielleicht**
nicht sofort; P0-04 deckt die akute Recovery-Lücke. Wenn Background-Jobs,
Approvals und Side-Effects wachsen, wird eine echte Outbox sinnvoll.

**Konkret (nur bei Bedarf):**
- Tabelle `effect`: `id`, `kind`, `session_id`, `state`, `attempt`,
  `available_at`, `deadline_at`, `payload`, `last_error`,
  `idempotency_key`.
- Claim mit `SKIP LOCKED` (Postgres) bzw. Single-Writer (SQLite).
- Fenced Completion; Erfolg löscht die Row; Fehler speichert Attempt + Error.
- Deadlines/Parking statt blockierter Threads.
- Backlog-Coalescing für Wakeups.

**Acceptance:**
- Ein Effekt überlebt Neustart und wird genau einmal abgeschlossen
  (first-wins).
- Fehlversuche sind sichtbar; Deadlines greifen.
- Kein doppelter Side-Effect bei gleichem Idempotency Key.

**Tests:** Crash vor/nach Claim, Deadline, Retry, fenced Completion,
Coalescing.

**Abhängigkeiten:** P0-02, P0-04.

**Aufwand/Risiko:** L / hoch; erst nach P0 evaluieren.

---

#### P1-09 – Model-Capabilities, Rollen und Fallback

**Status (D-08):** Die Rollen-Map ist **zurückgestellt** (TODO). Capability-
Gates können unabhängig davon umgesetzt werden. Die Rollen-Frage wird erst bei
einem zweiten Adapter oder einem messbaren Kostenhebel neu geprüft.

**Warum:** QOC hat einen OpenAI-Adapter und per-Agent-Model. Für mehrere
Provider/Modelle fehlen Capability-Gates und Rollen (Titel, Compaction,
Summary). oh-my-pi zeigt den Nutzen, aber ein Rollensystem mit nur einem
Provider ist Konfiguration ohne Gewinn.

**Konkret:**
- `ModelCapabilities` explizit: Tools, strikte Tool-Schemas, Vision,
  Reasoning, Streaming, Kontext-/Output-Limits.
- Tool-Snapshot und Prompt an Capabilities anpassen.
- `ModelRole`-Map erst mit zweitem Adapter: `default`, `smol`, `plan`,
  `summary`.
- Fallback-Chain pro Rolle, mit klaren Retry-/Kostenregeln.
- Kein KDL; JSON/YAML.

**Acceptance:**
- Ein Modell ohne Tool-Support bekommt keine Tool-Definitionen.
- Titel/Compaction können ein günstigeres Modell nutzen.
- Fallback greift nur bei klassifizierten Fehlern.

**Tests:** Capability-Gating, Fallback, Rollenauflösung, Kostenlimit.

**Abhängigkeiten:** P1-01, P0-07.

**Aufwand/Risiko:** M / mittel.

---

#### P1-10 – Hook-SPI und Lifecycle-Observer

**Warum:** QOC hat nur `beforeToolCall`, `afterToolCall` und
`evaluatePermission`. Für Integrationen, Audit und Tests fehlen Lifecycle-
und Context-Hooks. Ohne SPI bleibt jede Erweiterung ein Fork.

**Konkret:**
- Java-SPI in `engine.core`:
  - `ToolHook` (bestehend, verfeinern),
  - `SessionLifecycleHook` (created/forked/archived),
  - `ContextHook` (nur additive Blöcke, keine beliebige Mutation),
  - `CompactionHook` (before/after),
  - `StepObserver` (read-only Metriken).
- Implementierungen im Quarkus-Adapter via CDI/ServiceLoader.
- Hooks fail-closed; Approval nach Input-Änderung neu prüfen.
- Read-only Event-Stream für Telemetrie; Journal bleibt unveränderlich.

**Acceptance:**
- Ein Hook kann Kontext additiv erweitern, aber keine Sicherheitsentscheidung
  umgehen.
- Ein werfender Hook blockiert sicher.
- Audit kann Tool-/Session-Events beobachten.

**Tests:** Hook-Reihenfolge, Exception fail-closed, Approval-Reevaluation,
Observer-Mutation verhindert.

**Abhängigkeiten:** P0-09.

**Aufwand/Risiko:** M / mittel.

---

#### P1-11 – Commands, Skills, Slash-Befehle und Queue-UI

**Warum:** oc/pi zeigen viele kleine Ergonomie-Lücken, die einzeln billig sind:
Command-Macros, `/reload`, `/export`, `/session`, `/name`, `/tree`, `/fork`,
`/skill:<id>`, `/copy`, Skill-Validierung, Watcher und eine sichtbare
Message-Queue.

**Konkret:**
- `CommandLoader` für `command/*.md` und `.opencode/command/`, `$ARGUMENTS`.
- `/reload` für Skills/Instructions/Settings.
- Skill-Validierung (Name, Description) als Warnungen; Ignore-Files und
  Git-Root-Walk.
- Watcher oder Polling für Skills/Instructions.
- Queue: `delivery` im Prompt-Request, UI-Anzeige, Steer/Follow-up.
- Slash-Befehle gemäß oc/pi-Liste.

**Acceptance:**
- Ein Command-Macro expandiert korrekt.
- `/reload` lädt Skills/Instructions neu.
- Queue zeigt anstehende Nachrichten; Steer unterbricht sauber.
- Skill-Warnungen sind sichtbar, blockieren aber nicht.

**Tests:** Command-Substitution, Reload, Skill-Validierung, Queue/Steer,
Slash-Endpunkte.

**Abhängigkeiten:** P1-01.

**Aufwand/Risiko:** M / niedrig.

---

#### P1-12 – Bilder und Attachments

**Warum:** `ModelContent.Media` existiert, aber UI und `ReadTool` sind
textorientiert. Für Screenshots/Design-Arbeit ist das eine Lücke; sie ist aber
nicht production-kritisch.

**Konkret:**
- Paste/Drag → Base64/Blob; MIME-Allow-List; Größen-/Dimensionslimit;
  Auto-Resize.
- `images.blockImages` als Setting.
- Blob-Store mit SHA-256; Session-Referenz.
- Read-Tool kann Bilder als MediaContent liefern.

**Acceptance:**
- Ein Bild wird validiert, verkleinert und als MediaContent gesendet.
- Blockieren von Bildern funktioniert.
- Kein unbegrenztes Bild im Kontext.

**Tests:** MIME, Größe, Resize, Block, Blob-Referenz.

**Abhängigkeiten:** P1-05.

**Aufwand/Risiko:** M / niedrig–mittel.

---

### P2 – Performance und spätere Differenzierung

#### P2-01 – LSP-Tool

**Warum:** QOC kann aktuell nicht „wer ruft das auf?“, „ist das korrekt?“ oder
„benenne das Symbol um“ beantworten. oh-my-pi zeigt den Wert, aber LSP ist ein
großer Adapter und nur eine Quelle.

**Konkret:**
- `LanguageServer`-Port in `engine.core`; LSP4J oder minimaler JSON-RPC-
  Client im Adapter.
- Aktionen: `diagnostics`, `definition`, `references`, `symbols`, `hover`,
  `rename`, `rename_file`, `code_actions`, `status`, `reload`.
- Diagnostics nach Edit als Warnung, kein Auto-Rollback.
- Fake-LSP-Server für stabile Tests; echte Server nur in Integration.
- Approval: Navigation = READ; Rename/Code-Action = WRITE.

**Acceptance:**
- Diagnostics/Definition/References funktionieren mit Fake-Server.
- Rename liefert WorkspaceEdit; Anwenden ist explizit.
- Kein Test hängt von einem installierten Language Server ab.

**Tests:** Fake-LSP, Diagnose, Rename, Timeout, fehlender Server.

**Abhängigkeiten:** P1-06 (für Rename-Integration).

**Aufwand/Risiko:** L / mittel–hoch.

---

#### P2-02 – Memory, Advisor und TTSR (später)

**Warum:** oh-my-pi zeigt mächtige, aber risikoreiche Mechanismen:
autonome Memory-Extraktion, Reviewer-Modell, Stream-Regeln. Für QOC ist zuerst
`RULES.md` (P1-01) der bessere 80/20-Hebel.

**Konkret:**
- Memory: manuell kuratierte Projekt-Memory + `RULES.md`; autonome Extraktion
  erst nach Privacy-/Retention-Entscheidung.
- Advisor: separater read-only Subagent mit eigenem Modell und Note-Channel;
  keine Approval-Rechte.
- TTSR: Provider-Abort/Retry + Regelsprache + Audit; hoher Komplexitätsgrad.

**Acceptance:**
- Memory ist löschbar, redigiert und token-begrenzt.
- Advisor kann blockieren, aber nicht freigeben.
- TTSR-Schleifen sind begrenzt und auditierbar.

**Tests:** Retention, Redaction, Advisor-Note, TTSR-Abort/Retry.

**Abhängigkeiten:** P1-10, P1-09.

**Aufwand/Risiko:** L / hoch; bewusst später.

---

#### P2-03 – Snapcompact / Vision-Compaction

**Warum:** Nur sinnvoll mit Vision-Modell und klarem Kostenmodell. Für QOC
derzeit kein Produktionshebel.

**Konkret:** Historie als Bitmap archivieren, nur wenn ein Vision-Provider
konfiguriert ist; sonst nicht.

**Acceptance:** Opt-in, kostenbegrenzt, nicht Standard.

**Aufwand/Risiko:** L / hoch.

---

#### P2-04 – Multi-Provider-Katalog und Capability-Policy

**Warum:** QOC hat einen OpenAI-Adapter. Ein großer Katalog ist ein Produkt
für sich; erst mit konkretem Bedarf.

**Konkret:** Provider-Adapter inkrementell, Capabilities (P1-09), JSON/YAML-
Policy, keine KDL-Abhängigkeit.

**Acceptance:** Ein zweiter Adapter besteht dieselben Contract-Tests.

**Aufwand/Risiko:** L / mittel.

---

#### P2-05 – Persistente Shells / Managed Services

**Warum:** Nur nötig, wenn QOC interaktive Terminals oder langlebige Dienste
wirklich anbieten will. Für den Serverbetrieb reichen Background-Jobs,
Prozess-Registry und Artefakte.

**Konkret:** Prozess-Registry erweitern, Output-Sink, Readiness-Probe,
Session-Logs; PTY nur mit UI.

**Acceptance:** Jobs sind beobachtbar, killbar und überleben keine Session
ohne explizite Policy.

**Aufwand/Risiko:** M–L / mittel.

---

#### P2-06 – Pure sealed AgentState (inkrementell)

**Warum:** Nessys reiner Zustandskern ist attraktiv, aber ein großer Umbau.
QOC ist bereits event-sourced; ein Rewrite wäre riskant.

**Konkret:** Schrittweise Invarianten in einem reinen Evaluator kapseln;
Replay-Tests; keine Big-Bang-Migration.

**Acceptance:** Replay ist deterministisch; Event-Reihenfolge wird zentral
geprüft.

**Aufwand/Risiko:** L / hoch; nur nach P0/P1.

---

## 4. Reihenfolge und Phasen

Die Reihenfolge folgt nicht der Feature-Parität, sondern der
Produktionskorrektheit. P0-01/02/03/04 sind die Basis; P0-05/06 und P0-07/08/09
können nach P0-02 parallel laufen.

### Phase 0a – Korrektheit der API und Ausführung

1. **P0-01** Turn-Outcome-Algebra.
2. **P0-02** Idempotency Key + Tool-Call-Lifecycle.
3. **P0-03** Durable Approvals.
4. **P0-04** Crash-Recovery/Reconciliation.

**Gate:** Crash-Matrix grün; keine verlorenen Approvals; keine doppelten
Side-Effects; Busy/Refused/Failed/Terminated unterscheidbar; keine 500 für
Domänenfehler.

### Phase 0b – Kontext und Laufzeit

5. **P0-05** Token-aware Compaction.
6. **P0-06** Stabile Prompt-/Checkpoint-Struktur.
7. **P0-07** Retry/Timeout/Cancel/Turn-Policy.
8. **P0-08** Limits/Backpressure/Graceful Shutdown.
9. **P0-09** Health/Metriken/Audit.

**Gate:** Kein Context-Overflow in Langtests; keine zerschnittenen Turns;
Cache-Prefix stabil; 24h-Soak; Lasttest; Health/Metriken; Restore getestet.

### Phase 1 – Kontext, Sessions, Subagents

10. **P1-01** Prompt-/Instruktions-Stabilität.
11. **P1-02** Export + Branch-Summary.
12. **P1-03** Typisierte Subagent-Batches + Isolation.
13. **P1-04** Approval-Tiers/Modi.
14. **P1-05** Scan-Cache/Read-Selektoren.

**Gate:** Instruktionen überleben Compaction/Revert; Export redigiert; Batches
typisiert; Tier-Grants korrekt; Scan-Cache messbar.

### Phase 2 – Tool-Qualität und Sicherheit

15. **P1-06** Anchored Edits.
16. **P1-07** Security-Härtung.
17. **P1-08** Effect/Outbox (nur falls nötig).
18. **P1-09** Model-Capabilities/Rollen.
19. **P1-10** Hook-SPI.
20. **P1-11** Commands/Skills/Queue.
21. **P1-12** Bilder.

**Gate:** Edit-Fehlerrate sinkt; Security-Review; Hooks fail-closed;
Commands/Queue funktionieren.

### Phase 3 – Differenzierung

22. **P2-01** LSP.
23. **P2-02** Memory/Advisor/TTSR.
24. **P2-03** Snapcompact.
25. **P2-04** Multi-Provider.
26. **P2-05** Persistente Shells.
27. **P2-06** Reiner Zustandskern.

**Gate:** Nur starten, wenn ein konkreter Produktbedarf und ein
Kosten-/Sicherheitsmodell existieren.

---

## 5. Messbare Definition of Done

### 5.1 Reliability

| Metrik | Ziel | Nachweis |
|---|---|---|
| Stuck Claims nach Crash | 0 | Fault-Injection-Matrix |
| Verlorene offene Approvals | 0 | Restart-Test |
| Doppelte Side-Effects mit gleichem Key | 0 | Dedupe-Test |
| Domänenfehler als HTTP 500 | 0 | HTTP-Contract-Tests |
| Nicht unterscheidbare Turn-Outcomes | 0 | API-/SSE-Tests |
| Recovery ohne expliziten Zustand | 0 | Recovery-Events |

### 5.2 Stabilität

| Metrik | Ziel | Nachweis |
|---|---|---|
| 24h-Soak | stabil, kein OOM, kein Thread-Leak | Last-/Soak-Test |
| Queue-Tiefe | bounded | Metrik/Alarm |
| Speicherwachstum | < definierte Schwelle | Soak |
| Context-Overflow-Rate | < 0,1 % | Provider-Fehler + Compaction |
| Compaction zerschneidet Turn | 0 | Property-/Regressionstests |
| Graceful Shutdown | keine hängenden Prozesse | Shutdown-Test |

### 5.3 Speed

| Metrik | Ziel | Nachweis |
|---|---|---|
| p95 Time-to-First-Token | definierter SLO | Metrik |
| p95 Turn-Dauer | definierter SLO | Metrik |
| Token pro Turn | sinkend durch Compaction/Pruning | Usage-Projektion |
| Scan-Zeit bei großen Trees | sinkend durch Cache | Benchmark |
| Cache-Hit-Rate | steigend, messbar | Provider-Header/Usage |
| Edit-Retry-Rate | sinkend durch Anchored Edits | Tool-Statistik |

### 5.4 Production-Readiness

| Kriterium | Nachweis |
|---|---|
| Health/Readiness | `/health/live`, `/health/ready` |
| Metriken/Alarme | Prometheus/Micrometer, Dashboards |
| Audit | Permission-/Tool-/Approval-Events |
| Backup/Restore | dokumentiert und getestet |
| Migration/Rollback | Flyway-Test, Restore-Test |
| Security | SSRF/Traversal/Secret-Review |
| Last/Soak | dokumentierte Ergebnisse |
| Deployment/Rollback | idempotent, versioniert, Health-Check |
| Doku | Runbook, SLOs, Incident-Pfad |

Bestehende Gates bleiben:

- `mvn test` grün.
- JaCoCo ≥ 80 % Instructions.
- Contract-Tests auf PostgreSQL/Testcontainers.
- ArchUnit-Regeln grün.
- Keine Tests aus dem Workspace-Root.

---

## 6. Entscheidungslog (D-01–D-14)

> **Stand:** 2026-10-26. Die Entscheidungen sind als Defaults für die
> Umsetzung dokumentiert. D-08 und D-11 sind bewusst zurückgestellt und als
> TODO markiert.

| ID | Thema | Entscheidung | Konsequenz |
|---|---|---|---|
| **D-01** | Dauerhafte Arbeit | **Reconciliation zuerst**, keine vollständige Outbox jetzt | P0-04 wird zuerst gebaut; P1-08 nur bei Trigger |
| **D-02** | Approval-Waiting | **Parken mit Doorbell**; Zustand `WaitingApproval` | P0-03/P0-04; kein blockierter Thread; Deadline + Recovery |
| **D-03** | Checkpoint-Modell | **Append-only Kapitel**; Merge bei 8 Kapiteln oder 20 % Kontext | P0-06; QOC-012; Cache-Prefix bleibt stabil |
| **D-04** | Token-Schätzer | **Heuristik + 10–15 % Marge**, mit Usage kalibriert | P0-05; Tokenizer später nur bei Bedarf |
| **D-05** | Hashline | **Opt-in**; `replace` bleibt Default | P1-06; Default erst nach Metriken |
| **D-06** | Tag-Breite | **8 Hex + SHA-256 im EditStore**; Tag ist Index | P1-06; 12 Hex später bei sehr großen Workspaces |
| **D-07** | LSP | **LSP4J im Adapter**; Core bleibt frei von LSP-Details | P2-01; Fake-Server-Tests |
| **D-08** | Model-Rollen | **Zurückgestellt / TODO**; keine Rollen-Map jetzt | P1-09 bleibt Backlog; Trigger siehe unten |
| **D-09** | Multi-Tenancy | **Single-Tenant explizit**; `tenant_id`-fähig designen | Authz/Isolation; vor Mehrbenutzerbetrieb neu bewerten |
| **D-10** | Retention/RPO/RTO | **Production-Defaults**, pro Projekt konfigurierbar | P0-09/P1-07; Testumgebung 24h RPO / 2h RTO |
| **D-11** | Export | **Kein Export aktuell**; Redaction-Pflicht dokumentieren | P1-02 bleibt Backlog; künftiger Export default-on redigiert |
| **D-12** | Worktree-Isolation | **Diff/Review**, kein Auto-Merge | P1-03; Parent prüft und wendet an |
| **D-13** | Exactly-once | **At-least-once + Idempotency Key** | P0-02; Tools dokumentieren Idempotenz |
| **D-14** | Persistente Shells | **Nein**, Background-Jobs reichen | P2-05 optional; nur mit UI und Freigabe |

### D-01 – Reconciliation zuerst, Outbox später

**Entscheidung:** P0-04 baut eine Startup-Reconciliation über Journal,
Claims, Tool-Intents, Approvals und Background-Tasks. Eine vollständige
Nessy-Outbox wird **nicht** jetzt eingeführt.

**Begründung:** QOC hat bereits Journal und Claim-Store. Eine zusätzliche
Effect-Tabelle wäre zunächst eine zweite Quelle der Wahrheit und ein großer
Umbau. Die akute Lücke ist nicht „keine Outbox“, sondern „offene Arbeit wird
beim Start nicht systematisch abgeglichen“.

**Trigger für Neubewertung:** mehrere Hintergrund-Arbeitstypen, echte
Cross-Process-Worker, geplante Retries mit Deadlines oder Approval-Parking in
größerem Umfang. Dann P1-08 (Hybrid/Outbox) evaluieren.

### D-02 – Approvals parken mit Doorbell

**Entscheidung:** Ein Turn, der auf eine Freigabe wartet, wechselt in einen
expliziten Zustand `WaitingApproval`. Die Approval-Anfrage wird persistiert;
die Ausführung wird per Doorbell geweckt. Kein Thread blockiert unbegrenzt.

**Begründung:** QOC-009 verlangt Neustartfestigkeit. Ein blockierter Virtual
Thread ist billig, aber nicht durable und schlecht zu drainen. Parken passt zu
Graceful Shutdown, Deadline und Recovery.

**Konsequenz:** P0-03 und P0-04 müssen gemeinsam gedacht werden; der Claim
bleibt erhalten, aber der Zustand ist sichtbar und wiederaufnehmbar.

### D-03 – Compaction als append-only Kapitel

**Entscheidung:** Compaction-Summaries werden als Kapitel angehängt; der
statische Prompt-Prefix bleibt stabil. Ein Merge erfolgt nur bei einer
Schwelle (Start: 8 Kapitel oder 20 % des Kontextbudgets).

**Begründung:** Ein Checkpoint an Index 0 invalidiert den Cache-Prefix bei
jeder Compaction. Kapitel sind auditierbar, cache-freundlicher und passen zu
„Compaction ist eine Sicht, keine Wahrheit“.

**Konsequenz:** P0-06 ersetzt QOC-012; QOC-012 kann nach Umsetzung geschlossen
werden.

### D-04 – Token-Schätzung per Heuristik

**Entscheidung:** Preflight-Schätzung mit UTF-8-Zeichen / 3,5–4 plus 10–15 %
Sicherheitsmarge. Die tatsächliche Provider-Usage kalibriert die Schätzung.
Ein exakter Tokenizer wird erst bei nachgewiesener Fehlschätzung ergänzt.

**Begründung:** Die Entscheidung „Compaction nötig?“ braucht keine
zeichengenaue Tokenizer-Genauigkeit. Eine konservative Heuristik ist
dependency-frei und ausreichend; der Tokenizer bleibt ein Adapter.

**Konsequenz:** P0-05 dokumentiert die Formel und die Kalibrierung.

### D-05 – Hashline opt-in

**Entscheidung:** `edit.mode=replace` bleibt Default. Hashline wird pro Agent
oder Modell aktiviert. Ein Default-Wechsel erfolgt erst, wenn Metriken
(Edit-Retry-Rate, Fehlerrate) den Nutzen belegen.

**Begründung:** Hashline ist ein neues Prompt- und Tool-Vertragsformat. Es kann
die Qualität stark verbessern, erhöht aber die Prompt-Fläche und kann kleinere
Modelle überfordern.

**Konsequenz:** P1-06 implementiert den Modus, ohne den Default zu destabilisieren.

### D-06 – 8-Hex-Tags mit Store-Identität

**Entscheidung:** Der sichtbare Tag hat 8 Hex-Zeichen. Die Identität liegt im
`EditStore` mit vollständigem SHA-256; der Tag allein ist **kein**
Inhaltsbeweis. Bei sehr großen Workspaces kann später auf 12 Hex erhöht werden.

**Begründung:** 4 Hex hat nur 65.536 Werte und ist als Content-Address zu
schwach. 8 Hex ist ein guter Kompromiss; die Auflösung erfolgt immer über den
Store.

**Konsequenz:** P1-06 testet Kollisionen und stale Tags explizit.

### D-07 – LSP4J im Adapter

**Entscheidung:** Der LSP-Adapter nutzt LSP4J; `engine.core` sieht nur einen
neutralen `LanguageServer`-Port. LSP bleibt P2 und wird mit einem Fake-Server
getestet.

**Begründung:** LSP umfasst Capability-Negotiation, Document Sync, Diagnostics,
WorkspaceEdits und Lifecycle. LSP4J ist etabliert und hält die Kernschicht
sauber. Ein minimaler Client nur, wenn Dependency-Größe oder Audit-Anforderungen
dagegen sprechen.

**Konsequenz:** P2-01; echte Language Server nur in Integrationstests.

### D-08 – Model-Rollen: zurückgestellt (TODO)

**Entscheidung:** Es wird **jetzt keine** vollständige Model-Rollen-Map
eingeführt. Der Punkt bleibt als TODO dokumentiert.

**Begründung:** Mit nur einem Provider ist eine große Rollen-Map Konfiguration
ohne echten Hebel. Capability-Gates sind davon unabhängig nützlich, aber die
Rollen-Frage wird erst mit einem zweiten Adapter oder einem messbaren
Kostenhebel (Summary/Titel) entschieden.

**Trigger für erneute Prüfung:**
- ein zweiter Provider-Adapter existiert,
- eine Summary-/Titel-Rolle spart nachweislich Kosten,
- per-Agent-Modellwahl reicht nicht mehr aus.

**Konsequenz:** P1-09 bleibt im Backlog; keine vorzeitige Rollen-Konfiguration.

### D-09 – Single-Tenant explizit

**Entscheidung:** QOC ist zunächst **single-tenant**. Die Datenmodelle werden
so gestaltet, dass `tenant_id` später ergänzt werden kann; die aktuelle
Isolation bleibt ein Workspace/Owner.

**Begründung:** Die vier Analysen decken Multi-Tenancy nicht ab. Für den
aktuellen Basic-Auth-Betrieb ist Single-Tenant wahrscheinlich korrekt. Ein
implizites „vielleicht später“ wäre riskant, weil Sessions, Artefakte,
Approvals und Backups alle Tenant-Grenzen brauchen.

**Trigger für Neubewertung:** Mehrbenutzerbetrieb, mehrere Kunden, getrennte
Datenhaltung oder Quotas pro Nutzer.

**Konsequenz:** Authz/Isolation wird dokumentiert und getestet; Multi-Tenant
ist kein stilles Feature, sondern eine eigene Migration.

### D-10 – Production-Retention und Recovery-Defaults

**Entscheidung:** Konfigurierbare Defaults:

| Daten | Default |
|---|---|
| Sessions/Events | Bis explizite Löschung |
| DB-Backups | Täglich voll, 14 Tage; zusätzlich WAL/stündlich |
| Artefakte | 30 Tage nach Session-Ende oder explizit |
| Logs | 14 Tage |
| LLM-Traces | 7 Tage |
| Exporte | On-demand, nicht dauerhaft gespeichert |
| **RPO** | Produktion 1 h; Test 24 h |
| **RTO** | Produktion 1 h; Test 2 h |

**Begründung:** QOC speichert Prompts, Tool-Ausgaben, Logs und Credentials.
Retention ist eine Sicherheits- und Kostenentscheidung, kein Storage-Detail.

**Konsequenz:** P0-09/P1-07 dokumentieren die Defaults und machen sie pro
Projekt konfigurierbar.

### D-11 – Export: aktuell nicht implementiert, Redaction-Pflicht dokumentiert

**Entscheidung:** Es wird **kein Export** als Teil der aktuellen P0-Arbeit
gebaut. Die Anforderung wird dokumentiert: Ein künftiger Export (P1-02)
redigiert standardmäßig Secrets; Roh-Export nur als Admin-Aktion mit Audit.

**Begründung:** Aktuell existiert kein Export. Eine Redaction-Implementierung
ohne Export wäre spekulativ; die Policy muss aber vor der ersten
Export-Implementierung feststehen.

**Konsequenz:** P1-02 bleibt Backlog. Die erste Export-Implementierung muss
die Redaction-Tests aus P1-07 mitbringen.

### D-12 – Worktree-Isolation mit Diff/Review

**Entscheidung:** Ein isolierter Subagent gibt Worktree-Pfad, Diff und
Testergebnis zurück. Der Parent prüft und wendet an. **Kein Auto-Merge.**

**Begründung:** Parallele Agenten erzeugen schnell Konflikte; ein Auto-Merge
kann kaputten Code ungeprüft übernehmen. Review passt zu QOCs Approval-Modell.

**Konsequenz:** P1-03; Auto-Merge nur später mit expliziter Freigabe und
sauberen Tests.

### D-13 – At-least-once plus Idempotency Key

**Entscheidung:** QOC verspricht **at-least-once**, nicht exactly-once. Jeder
Tool-Call erhält einen stabilen Idempotency Key; Tools dokumentieren, ob sie
idempotent sind und den Key zur Dedupe nutzen.

**Begründung:** Exactly-once ist über Crash-Grenzen und externe Side-Effects
ohne transaktionale Fremdsysteme nicht ehrlich versprechbar. Die Spezifikation
lehnt es bereits ab.

**Konsequenz:** P0-02; Tool-Vertrag und Recovery müssen den Key tragen.

### D-14 – Keine persistenten Shells

**Entscheidung:** QOC führt **keine persistenten Shells** ein. Background-Jobs,
Prozess-Registry und Artefakte decken den Serverbetrieb. Optionale
Session-Shells oder Managed Services erst bei konkretem Produktbedarf.

**Begründung:** Persistente Shells bringen Zustand, Sicherheitsrisiken und
Cleanup-Probleme. Der aktuelle Background-Job-Ansatz ist sicherer und
wartbarer.

**Konsequenz:** P2-05 bleibt optional; keine P0/P1-Abhängigkeit.

### 6.1 Konsequenzen für die Phasen

| Phase | Entscheidungen |
|---|---|
| **Phase 0a** | D-01, D-02, D-13 |
| **Phase 0b** | D-03, D-04, D-10 |
| **Phase 1** | D-05, D-06, D-09, D-12 |
| **Phase 2** | D-07 |
| **Zurückgestellt** | D-08, D-11 |

### 6.2 Noch zu konkretisieren

- **D-08:** TODO „Model-Rollen prüfen“ mit Trigger zweiter Provider /
  Kostenhebel.
- **D-11:** TODO „Export-Redaction“ bei Start von P1-02.
- **D-10:** konkrete Backup-/WAL-Konfiguration für SQLite und PostgreSQL.
- **D-09:** Authz-/Isolations dokumentieren, auch wenn Single-Tenant.

---

## 7. Quellen-Mapping und Abgrenzung

### 7.1 Mapping zu QOC-Findings

| QOC-ID | Status | Diese Liste |
|---|---|---|
| QOC-001 | DONE | Keine erneute Priorisierung; P2-05 optional |
| QOC-002 | DONE | Grundlage für P0-05 |
| QOC-003 | DONE | Grundlage für P1-03 |
| QOC-004 | DONE | Grundlage für P1-05 |
| QOC-005 | DONE | Grundlage für P1-02 |
| QOC-006 | DONE | Grundlage für P1-02 |
| QOC-007 | DONE | Guardrail; Regressionstest behalten |
| QOC-008 | DONE | Guardrail; Outcome-Mapping P0-01 |
| QOC-009 | OPEN | **P0-03** |
| QOC-010 | DONE (Turn-Grenze) | **P0-05** für Token-Budget/Kapitel |
| QOC-011 | OPEN | **P0-02 / P0-04** |
| QOC-012 | OPEN | **P0-06** |
| QOC-013 | OPEN | **P0-01** |

### 7.2 Was die vier Analysen nicht abdecken

Diese Punkte sind für „production ready“ wichtig, stehen aber nicht im Fokus
der Referenzanalysen:

- Multi-Tenancy, Authz jenseits von Basic Auth, Quotas.
- SLOs/SLIs, Alarmierung, Runbooks, Incident-Prozess.
- Backup/Restore, RPO/RTO, Migration-Rollback.
- Last-, Soak- und Chaos-Tests.
- Supply-Chain-Sicherheit, SBOM, Dependency-Scan.
- Datenretention, Löschkonzept, Datenschutz.
- Kostenkontrolle über Turn-/Session-Budgets.
- Deployment-Rollback und Canary/Blue-Green.

Diese Themen sind bewusst als **Production-Gate** in P0-08/P0-09 und in der
Definition of Done verankert, nicht als Feature-Backlog.

### 7.3 Abgrenzung

- Diese Datei ist eine **Synthese und Backlog-Vorlage**, keine
  Implementierungsfreigabe.
- Sie überschreibt weder `qoc-findings.md` noch die Spezifikation.
- Sie ändert keine Architekturregeln: `engine.core` bleibt frei von Quarkus,
  Jackson, CDI und HTTP; Adapter bleiben in `engine.adapter.*`.
- Keine Commits, Branches oder Milestone-Änderungen ohne ausdrückliche
  Anweisung.
- Die vier Analysen bleiben die Detailquellen; diese Datei priorisiert und
  verbindet sie.

---

*Erstellt: 2026-10-26 · Synthese aus `oc-open.md`, `pi-learnings.md`,
`oh-learnings.md`, `nessy-learnings.md` und dem aktuellen QOC-Findings-Register.*
