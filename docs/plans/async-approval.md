# Plan: Asynchrone Freigaben (Option B – Suspend & Resume)

> **Status:** Plan · 2026-10-31  
> **Ziel:** Freigaben (HITL) sollen asynchron über externe Kanäle (E-Mail)
> erfolgen können, ohne den Tool-Call-Thread unbegrenzt zu blockieren.

---

## 1. Motivation

Heute ist die Freigabe **synchron/blockierend**:

- `PermissionEngine.assertAllowed` ruft `ApprovalSource.request(...)` auf und
  blockiert den Tool-Call-Thread bis zu 300 s (`WebApprovalSource`).
- Die `CompletableFuture` ist in-memory; der Request ist aber in
  `pending_approval` persistiert.
- Bei E-Mail (Stunden/Tage) reicht das nicht.

## 2. Architektur-Optionen

### Option A – Langes blockierendes Warten (minimal)

`ApprovalSource.request` pollt die DB statt einer in-memory-Future, mit
konfigurierbarem Timeout (Stunden/Tage).

- Pro: wenig Code, nutzt bestehende Tabelle.
- Con: hält Thread + Verbindung; bei sehr langen Wartezeiten problematisch.
- Aufwand: niedrig-mittel.

### Option B – Suspend & Resume (sauber, skalierbar) ← **gewählt**

On `ASK`: Approval persistieren, Session als `suspended` markieren, Tool-Call
gibt ein `Paused`-Ergebnis zurück, Turn endet. Bei Freigabe (E-Mail-Link →
Endpoint) wird die Session geweckt und der Tool-Call erneut ausgeführt.

- Pro: keine langen Verbindungen, skalierbar, passt zu `SessionWake`.
- Con: Tool muss idempotent sein (Wiederholung), Session-State muss
  wiederhergestellt werden.
- Aufwand: hoch.

## 3. Umsetzungsphasen

### Phase 1 – Infrastruktur (dieses Dokument, umgesetzt)

1. **Notification-Channel-Port** (`engine.core.port.human.NotificationChannel`)
   - `send(ApprovalRequest, approvalUrl)`
   - Implementierungen: `EmailChannel` (SMTP), `WebhookChannel`
2. **Token-basierte Freigabe-Links**
   - `pending_approval` um `token` (gehasht), `expires_at`, `channel` erweitern
   - Endpoint `GET/POST /api/approval/{token}` (token-authentifiziert)
3. **Durable ApprovalSource**
   - `DurableApprovalSource`: persistiert Request, sendet Notification, pollt DB
     mit konfigurierbarem Timeout
   - Ersetzt/ergänzt `WebApprovalSource`
4. **Konfiguration**
   - `engine.approval.channel`, `engine.approval.email.*`,
     `engine.approval.timeout-hours`, `engine.approval.token-ttl-days`

### Phase 2 – Suspend & Resume (folgt)

1. **Paused-Outcome**
   - `ToolOutcome.Paused(approvalId)` und `StepResult.Paused`
   - `PromptResult` um `Paused`-Outcome erweitern
2. **Session-Suspend**
   - On ASK: Approval persistieren, Session als `suspended` markieren,
     Tool-Call gibt `Paused` zurück, Turn endet mit `PromptResult.Paused`
   - HTTP-Response: `{status: "paused", approvalId, approvalUrl}`
3. **Resume**
   - Endpoint `POST /api/session/{id}/resume` oder `SessionWake.wake(session)`
     bei Freigabe
   - Re-Run des Turns; Tool-Call wird erneut ausgeführt (IdempotencyKey
     dedupliziert)
4. **Idempotenz-Voraussetzung**
   - Tools müssen `IdempotencyKey` nutzen (at-least-once → genau-once bei
     Wiederholung)

### Phase 3 – Granulare Freigabe

- Pre-Execution-Approval für einzelne Tool-Schritte
- Freigabe nach Kosten/Sensitivität
- Multi-Instanz-Approval-Koordination

## 4. Multi-Instanz-Hinweis

Approval-Futures sind process-local. Bei Multi-Instanz (shared DB) muss die
Entscheidung über die DB laufen (Phase 1 polling oder Phase 2 suspend/resume).
Die `session_claim`-Tabelle existiert, wird aber in `Engine` noch nicht
genutzt – dort wäre der Ansatzpunkt.

## 5. Was bereits da ist

- `pending_approval`-Tabelle (persistiert, Status
  `PENDING/ORPHANED/DECIDED/EXPIRED`)
- `WebApprovalSource` (Template für neue Channels)
- `PermissionEngine.reply(...)` (ONCE/ALWAYS/REJECT)
- Web-UI-Endpoints für Approval
- `ApprovalStore` (project-wide „always allow“)
- `SessionWake` + `suspended`-Feld
- `IdempotencyKey` pro Tool-Call

## 6. Offene Entscheidungen

1. Token-Transport: URL mit Token im Query-Parameter oder POST-Body?
2. E-Mail-Template: HTML oder Plain-Text?
3. Timeout: wie lange darf eine Freigabe offen sein (7 Tage?)?
4. Soll der Agent bei `Paused` einen synthetischen Hinweis bekommen?
5. Werden `ASK`-Regeln für MCP-Tools automatisch erzeugt?
