# Enterprise Readiness – QOC

> **Status:** 2026-10-31  
> **Zweck:** Einordnung von QOC anhand der vier Säulen, die aus einer
> Agent-Demo eine produktionsreife Lösung machen: **Transaktionen,
> Idempotenz, Audit, menschliche Freigabe.** QOC adressiert alle vier – aber
> mit unterschiedlichem Reifegrad und einigen bewussten Grenzen. Jeder Punkt
> enthält eine Einschätzung zu Aufwand, Risiko, Lesbarkeit und sauberer
> Architektur.

---

## These

> 90 % der Produktionsreife eines Agenten sind verteiltes System, nicht KI.
> Transaktionen, Idempotenz, Audit, Berechtigungen, menschliche Freigabe.
> Genau dort entscheidet sich, ob ein Agent Demo oder Produktion ist – und
> genau dort fehlt den meisten Agent-Frameworks die Erfahrung aus dem
> klassischen Enterprise.

QOC ist näher an „Produktionsreife“ als reine Agent-Frameworks, aber die
verteilten Säulen sind **session-lokal implementiert**, nicht
mandanten-/instanzübergreifend. Genau dort – Multi-Instanz-Approval, globales
Audit, verteilte Transaktionen – liegt der verbleibende Enterprise-Abstand.

---

## 1. Transaktionen

### Status

**Vorhanden:**

- **DB-Transaktionen** über `Sql.transaction` / `Sqlite.transaction` für
  Sessions, Journal, Inbox, Approvals, Claims.
  Beispiel: Inbox-Delivery ist eine Transaktion (`DELETE session_inbox` +
  `INSERT session_message` zusammen).
- **Revert/Snapshots:** Vor jedem Turn wird ein Datei-Snapshot erfasst;
  `revert` stellt den Zustand wieder her (file-level, nicht DB-TX).
- **Event-Sourcing:** `journal` ist append-only; Events sind die Quelle der
  Wahrheit, `seq` monotonic.

**Lücke:**

- Tool-Aufrufe sind **nicht** in eine verteilte Transaktion eingebunden. QOC
  wrappt keine externen Systeme (DB, APIs) in eine TX.
- Rollback über mehrere Tools gibt es nur über den File-Snapshot, nicht über
  Side-Effects in externen Systemen.

### Was umgesetzt werden kann

#### 1.1 Tool-Level-Compensation (Saga-Pattern)

**Beschreibung:** Jedes Tool kann eine kompensierende Aktion deklarieren
(z. B. `create_file` → `delete_file`). Bei einem Teilfehler in einer
Tool-Kette laufen die Kompensationen in umgekehrter Reihenfolge. Analog zu
SAGA in verteilten Systemen.

**Aufwand:** hoch (neuer Contract in `ToolDefinition`/`ToolOutcome`, Compensations-
Registry, Orchestrierung in `Step`/`ToolRuntime`).

**Risiko:** mittel-hoch (Komplexität, partielle Fehlersemantik, Tool-Autoren
müssen Kompensationen implementieren).

**Lesbarkeit:** mittel (neue Abstraktion, aber klarer Vertrag).

**Saubere Architektur:** gut – Compensation als Port/Contract in
`engine.core`, Implementierung in `runtime`.

**Empfehlung:** Nur für explizit markierte side-effecting Tools einführen,
nicht global erzwingen.

#### 1.2 Transactional Tool Groups

**Beschreibung:** Mehrere Tools als atomare Gruppe ausführen: alle succeed oder
alle revert. Dateien über Snapshot, externe Effekte über Compensation.

**Aufwand:** mittel für File-Level; hoch für externe Systeme.

**Risiko:** mittel.

**Lesbarkeit:** mittel.

**Saubere Architektur:** moderat – erfordert Gruppierung im `ToolRuntime`.

**Empfehlung:** Optionaler Modus, nicht Default.

#### 1.3 Turn-Level-Idempotency (siehe Abschnitt 2)

---

## 2. Idempotenz

### Status

**Vorhanden:**

- `IdempotencyKey` pro Tool-Call (`idem_<session>_<call>`), stabil über
  Approval, Execution und Recovery.
- Inbox-Admission ist idempotent (wiederholte Enqueue mit gleicher ID gewinnt
  beim ersten).
- `session_claim` mit `ON CONFLICT DO NOTHING` (global pro Session).
- Durable Resume-Counter.

**Lücke (wichtig):**

- QOC garantiert **at-least-once**, nicht exactly-once. Side-effecting Tools
  müssen selbst idempotent sein.
- Es gibt keine Idempotency-Keys für ganze Prompts/Turns, nur für Tool-Calls.

### Was umgesetzt werden kann

#### 2.1 Prompt-/Turn-Level-Idempotenz

**Beschreibung:** Jeder Prompt erhält einen Idempotency-Key (Client- oder
generiert). Bei Wiederholung (Retry, Doppelklick) wird der bestehende Turn
zurückgegeben statt neu ausgeführt. Persistiert in `session_inbox` oder
einer neuen `turn_idempotency`-Tabelle.

**Aufwand:** mittel (`InboxItem`/`Admission` erweitern, Store-Methode
`findTurn(key)`).

**Risiko:** niedrig (rein additiv, kein Breaking Change).

**Lesbarkeit:** gut.

**Saubere Architektur:** gut – neuer Port `TurnIdempotencyStore`.

**Empfehlung:** Schnell umsetzbar, hoher Nutzen für Retries.

#### 2.2 Durable Tool-Result-Cache

**Beschreibung:** Tool-Ergebnisse werden unter dem `IdempotencyKey`
gespeichert. Bei einem Retry mit demselben Key wird das gecachte Ergebnis
zurückgegeben statt erneut ausgeführt. Besonders für side-effecting Tools.

**Aufwand:** mittel (`ToolOutputStore` erweitern um Lookup by Key).

**Risiko:** niedrig-mittel (Cache-Invalidierung, Staleness).

**Lesbarkeit:** gut.

**Saubere Architektur:** gut – Erweiterung eines bestehenden Ports.

**Empfehlung:** Sinnvoll für MCP- und A2A-Tools.

#### 2.3 Exactly-Once-Garantie für externe Systeme

**Beschreibung:** QOC kann nur Keys propagieren und lokal deduplizieren; externe
Systeme müssen die Keys selbst unterstützen. Dokumentation + SDK-Hilfe für
Tool-Autoren.

**Aufwand:** niedrig (Dokumentation, Beispiel-Implementierungen).

**Risiko:** niedrig.

**Lesbarkeit:** gut.

**Saubere Architektur:** n/a (Dokumentation).

**Empfehlung:** Sofort umsetzbar, klärt Erwartungen.

---

## 3. Audit

### Status

**Vorhanden:**

- **Event-Sourcing:** `StoredEvent` mit Typ, Zeit, Seq; Journal append-only.
- `audit(sessionId, limit)` projiziert Tool/Execution/Step/Compaction-Events.
- **Rotating LLM-Trace** (Request/Response, 2 MiB × 20 Dateien).
- Audit-Logging für Permission-, Tool- und Guardrail-Entscheidungen.
- `session_message` enthält Tool-Zustände (`streaming → running →
  completed/error`); Messages werden bei Compaction nicht gelöscht.
- `pending_approval`-Tabelle persistiert Approvals (sichtbar nach Restart,
  reconcilable/expire).

**Lücke:**

- Audit ist **session-scoped**, nicht global/mandantenübergreifend.
- Journal ist bei Session-Delete löschbar → kein unveränderliches,
  append-only Audit-Log über Sessions hinweg.
- Keine separate Audit-Tabelle für Security-Events außerhalb der Session.

### Was umgesetzt werden kann

#### 3.1 Globales, unveränderliches Audit-Log

**Beschreibung:** Separate `audit_event`-Tabelle, append-only, kein
`ON DELETE CASCADE`, mit `tenant_id`/`instance_id`. Schreibt
Security-relevante Events (Permission, Guardrail, Tool, Session-Lifecycle)
unabhängig von der Session-Lebensdauer.

**Aufwand:** mittel-hoch (Migration, Store, API-Endpoint, Retention-Policy).

**Risiko:** niedrig (rein additiv).

**Lesbarkeit:** gut (klarer neuer Port `AuditStore`).

**Saubere Architektur:** gut – neuer Port in `engine.core`, Implementierung in
`adapter`.

**Empfehlung:** Hoher Wert für Compliance; früh umsetzen.

#### 3.2 Security-Event-Audit

**Beschreibung:** Permission-Entscheidungen, Guardrail-Results,
Auth-Failures werden strukturiert geloggt (Principal, Action, Resource,
Outcome, Zeit). Bereits teilweise vorhanden; konsolidieren und persistieren.

**Aufwand:** mittel.

**Risiko:** niedrig.

**Lesbarkeit:** gut.

**Saubere Architektur:** gut – Erweiterung des bestehenden `PermissionEngine`.

**Empfehlung:** Schnell, hoher Compliance-Nutzen.

#### 3.3 Audit-Export / SIEM-Integration

**Beschreibung:** Endpoint oder Datei-Export (JSON/CSV) für Audit-Events,
optional Webhook-Push. Retention-Policy konfigurierbar.

**Aufwand:** mittel.

**Risiko:** niedrig.

**Lesbarkeit:** gut.

**Saubere Architektur:** gut – Exporter als Adapter.

**Empfehlung:** Nach 3.1 umsetzen.

---

## 4. Freigabe (Human-in-the-Loop)

### Status

**Vorhanden:**

- Permission-Effects: `ALLOW` / `DENY` / `ASK`.
- `PendingApprovals` (in-memory + persistiert in `pending_approval`), TTL 300 s.
- Web-UI-Endpoints: `GET /api/session/{id}/permission`,
  `POST .../permission/{requestId}/reply`.
- `ApprovalStore`: project-wide „always allow“-Approvals, persistiert.
- `QuestionTool` für Rückfragen an den Menschen.
- `ASK` wird vom `ToolRuntime` Permission-Gate durchgesetzt.
- **Webhook-Guardrail:** externer Policy-Dienst kann Entscheidungen
  beeinflussen.
- **Ephemeral/One-Shot:** ASK-Futures sind process-local (Neustart verliert
  sie, aber Request ist persistiert → reconcilable/expire).

**Lücke:**

- Approval-Futures sind **process-local**; Multi-Instanz-Approval-Koordination
  ist unvollständig (`session_claim` existiert, wird aber in `Engine` nicht
  genutzt).
- Kein „Human in the Loop“ für einzelne Tool-Schritte jenseits von
  `ASK`/`Question`.
- Keine granulare Freigabe nach Kosten/Sensitivität (nur Permission-Rules).

### Was umgesetzt werden kann

#### 4.1 Multi-Instanz-Approval-Koordination

**Beschreibung:** Approval-Futures über die DB koordinieren: `session_claim`
als Lease nutzen, Approval-Requests persistieren (existiert), Futures bei
Neustart/anderer Instanz aus der DB rekonstruieren. Polling oder
DB-Notification statt in-memory `CompletableFuture`.

**Aufwand:** hoch (neue Koordinationsschicht, Race-Conditions, Timeout-Handling).

**Risiko:** mittel (Verteilungsprobleme, Konsistenz).

**Lesbarkeit:** mittel (neue Abstraktion `ApprovalCoordinator`).

**Saubere Architektur:** gut – Port `ApprovalSource` erweitern, Implementierung
in Adapter.

**Empfehlung:** Notwendig für echtes Multi-Instanz-HITL; vorher
Single-Instanz ausreichend.

#### 4.2 Pre-Execution-Approval für einzelne Tool-Schritte

**Beschreibung:** Tools können `requiresApproval` deklarieren; vor der
Ausführung wird ein Approval-Request erzeugt und die Ausführung pausiert.
Bereits teilweise über `ASK`-Effect; ausbauen auf Tool-Ebene mit Kontext
(Kosten, Sensitivität).

**Aufwand:** mittel (ToolDefinition erweitern, Step-Integration).

**Risiko:** niedrig.

**Lesbarkeit:** gut.

**Saubere Architektur:** gut – Erweiterung von `ToolDefinition.options()`.

**Empfehlung:** Sinnvoll für teure/sensible Tools.

#### 4.3 Granulare Freigabe nach Kosten/Sensitivität

**Beschreibung:** Permission-Rules um Kosten-Schwellen und Sensitivitäts-Tags
erweitern (z. B. `effect: ASK if cost > 1.00` oder `sensitivity: high`).
Policy-Engine evaluiert diese.

**Aufwand:** mittel (PermissionRules erweitern, Rule-Schema erweitern).

**Risiko:** niedrig.

**Lesbarkeit:** gut.

**Saubere Architektur:** gut – Erweiterung bestehender Contracts.

**Empfehlung:** Nach 4.2 umsetzen.

#### 4.4 Human-Decision-Audit-Trail

**Beschreibung:** Wer hat was wann freigegeben/abgelehnt, mit Begründung.
Persistiert in `pending_approval` (existiert) + Audit-Log (Abschnitt 3).

**Aufwand:** niedrig-mittel.

**Risiko:** niedrig.

**Lesbarkeit:** gut.

**Saubere Architektur:** gut – Erweiterung bestehender Tabellen.

**Empfehlung:** Schnell, hoher Compliance-Nutzen.

---

## 5. Zusätzliche verteilte Aspekte

| Bereich | QOC-Status | Nächster Schritt |
|---|---|---|
| Multi-Instanz DB | Sessions global sichtbar; `instance_id` für ephemeral; Retention global | Instance-Affinity für persistente Sessions (Filter in `list()`) |
| A2A | Client + Server, Egress-Guard, Agent-Card-Cache | Push-Notifications, Transport-Level-Egress |
| MCP | Client + Server, OAuth, Resource-Tools | Tool-Output-Streaming |
| Egress | Allowlists für Webhook und A2A (Host/IP, TLS) | Zentrale Egress-Policy |
| Principal/Auth | none/basic/oidc/proxy/mcp-oauth, Trusted-Proxy-CIDRs | Mandanten-Isolation |
| Observability | OpenTelemetry, Rotating LLM-Trace, Health/Ready | Metriken für Approval-Latenz |

---

## 6. Empfohlene Umsetzungsreihenfolge

1. **Sofort (niedriger Aufwand, hoher Nutzen):**
   - 2.3 Exactly-Once-Dokumentation für Tool-Autoren
   - 3.2 Security-Event-Audit konsolidieren
   - 4.4 Human-Decision-Audit-Trail

2. **Kurzfristig (mittlerer Aufwand):**
   - 2.1 Prompt-/Turn-Level-Idempotenz
   - 2.2 Durable Tool-Result-Cache
   - 3.1 Globales Audit-Log
   - 3.3 Audit-Export/SIEM
   - 4.2 Pre-Execution-Approval

3. **Mittelfristig (hoher Aufwand):**
   - 4.1 Multi-Instanz-Approval-Koordination
   - 4.3 Granulare Freigabe nach Kosten/Sensitivität
   - 1.1 Tool-Level-Compensation (Saga)
   - Multi-Instanz Session-Affinity

4. **Langfristig (Architektur):**
   - 1.2 Transactional Tool Groups
   - Verteilte Transaktionskoordination (Saga über externe Systeme)

---

## 7. Fazit

Die vier Säulen sind in QOC **konzeptionell vorhanden und teilweise
produktionsreif**:

- **Transaktionen/Audit** sind durch Event-Sourcing und DB-Transaktionen stark.
- **Idempotenz** ist durch `IdempotencyKey` angelegt, aber die Last liegt bei
  den Tools (at-least-once).
- **Freigabe** ist vollständig implementiert (ASK, PendingApprovals, Web-UI,
  Webhook).

QOC ist also **näher an „Produktionsreife“** als reine Agent-Frameworks, aber
die verteilten Säulen sind **session-lokal implementiert**, nicht
mandanten-/instanzübergreifend. Genau dort – Multi-Instanz-Approval,
globales Audit, verteilte Transaktionen – liegt der verbleibende
Enterprise-Abstand.
