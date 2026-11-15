# UI-Improvements – Plan zur weiteren Prüfung

**Status:** Plan (2026-11-15)
**Bezug:** `docs/engineering/Unified-Storage.md` §8, `docs/plans/mobile-workflow-ui.md`

Dieses Dokument sammelt die bekannten UI-Verbesserungspunkte und offenen
Architektur-Fragen, die noch nicht implementiert sind.

---

## 1. Echter File-Journal für Runtime (Offline-Crash-Recovery)

**Problem:** Im `file`-Modus (`engine.storage=file`) ist die Engine-Runtime
in-memory — keine Crash-Recovery. Bei einem Neustart gehen Sessions, Journal
und Snapshots verloren.

**Aktueller Zustand:**
- `memory`-Modus: alles ephemeral (bewusst)
- `file`-Modus: Workflows/Agents aus Dateien, Runtime in-memory
- `sqlite`/`postgres`-Modus: volle Persistenz + Crash-Recovery

**Mögliche Umsetzung:**
- File-basiertes Journal (`wf-data/journal/`) das Sessions, Events und
  Snapshots als JSON-Dateien persistiert
- Analog zu `SqliteAdapters` / `SqlAdapters`, aber mit File-Backend
- Crash-Recovery: beim Start offene Claims (session_claim) laden und
  Workflows resumen

**Aufwand:** 3–5 Tage
**Abhängigkeit:** `ClaimStore`, `Journal`, `SessionStore`, `SnapshotStore` Ports
**Risiko:** Hoher Aufwand, geringer Nutzen (sqlite ist bereits lightweight)

**Empfehlung:** Niedrige Priorität. SQLite bietet bereits Offline-Persistenz
mit geringem Overhead. File-Journal nur implementieren, wenn explizit
Offline-Nutzung ohne SQLite gewünscht ist.

---

## 2. UI-basierte Konfiguration für Agents, Permissions und MCP-Server

**Problem:** Aktuell werden Agents, Permissions und MCP-Server über
JSON-Dateien (`settings.json`, `application.properties`) oder
Umgebungsvariablen konfiguriert. Keine UI für diese Konfiguration.

**Aktueller Zustand:**
- Agents: UI vorhanden (`/workflow/agents`) mit CRUD, aber keine
  Permission-Konfiguration
- MCP-Server: UI vorhanden (`/workflow/mcp-servers`) mit Add/Remove/Reload
- Permissions: nur über `application.properties` oder Agent-Frontmatter

**Mögliche Umsetzung:**

### 2.1 Agent-Permissions in der Agents-UI
- `PropertyPanel` / `Agents.tsx`: Feld für `rolesAllowed` (Multi-Select)
- Feld für `toolAllow` / `toolDeny` (Tool-Picker, bereits vorhanden)
- Guardrails-Konfiguration (Input/Output Filter)

### 2.2 Permissions-Dashboard
- Neue Seite `/permissions` mit Übersicht aller Agent-Permission-Regeln
- Matrix-Ansicht: Agent × Tool → Allow/Deny/Ask
- Export/Import als JSON

### 2.3 MCP-Server-Konfiguration in der UI
- Bereits teilweise vorhanden (`/workflow/mcp-servers`)
- Erweiterung: OAuth-Konfiguration, Timeout-Einstellungen, Header-Config
- Health-Status mit automatischem Retry

### 2.4 Settings-UI
- `Settings.tsx` erweitern: Provider, Model, Permissions-Default
- Live-Reload der Konfiguration ohne Neustart

**Aufwand:** 5–8 Tage
**Abhängigkeit:** Backend-Endpoints für Config-CRUD (teilweise vorhanden)
**Risiko:** Großer Umfang, viele Edge-Cases

**Empfehlung:** Mittlere Priorität. Die Agents-UI (Phase 2.1) ist der
größte Quick-Win, da `rolesAllowed` und `toolAllow`/`toolDeny` bereits im
Datenmodell vorhanden sind und nur in der UI exponiert werden müssen.

---

## 3. MCP-Server DB-Persistenz (Migration V12)

**Problem:** MCP-Server werden in `mcp-servers.json` gespeichert, nicht in
der DB. Bei postgres/sqlite-Modus wäre DB-Persistenz konsistenter.

**Aktueller Zustand:** Implementiert (Commit `ca40412`):
- `SqlMcpServerStore` (DB-backed, nutzt `DataSource` wenn verfügbar)
- `McpServerPersistence` delegiert an `SqlMcpServerStore` bei gesetztem DataSource
- `Engine.wire()` setzt `McpServerPersistence.setDataSource(dataSource)`
- V12-Migration (postgres + sqlite) für `mcp_server`-Tabelle

**Verbleibende Arbeit:**
- Tests für `SqlMcpServerStore`
- Verifikation im DB-Modus (postgres/sqlite)
- Fallback-Verhalten bei DB-Fehler (aktuell: stille Fehler, kein Fallback auf File)

**Aufwand:** 0.5 Tage (Tests + Verifikation)

---

## 4. Weitere UI-Verbesserungen

### 4.1 Mobile-Optimierung (siehe mobile-workflow-ui.md)
- Phase 1–3 implementiert (Responsive Layout, NodeListView, BottomSheet)
- Phase 4 (Tests & Polish) noch offen
- Card-Layouts statt Tabellen für Workflows/Runs/Agents/McpServers

### 4.2 Workflow-Generate UI
- `Generate.tsx` vorhanden, aber Backend-Endpunkt jetzt implementiert
- UI könnte generierten Workflow direkt im Editor öffnen

### 4.3 Context-Picker (siehe node-data-contracts plan)
- PropertyPanel Tab "Context" für explizite Node-Auswahl
- Token-Budget-Anzeige

### 4.4 Dark/Light Theme Toggle
- Aktuell nur Dark Mode
- Tailwind `dark:` Prefix bereits vorhanden

### 4.5 Keyboard-Shortcuts
- GraphEditor: Delete, Copy/Paste, Undo/Redo
- Globale Shortcuts für Navigation

---

## Priorisierung

| Priorität | Item | Aufwand | Nutzen |
|---|---|---|---|
| 1 | 2.1 Agent-Permissions in Agents-UI | 1 Tag | Hoch |
| 2 | 3.1 SqlMcpServerStore Tests | 0.5 Tag | Mittel |
| 3 | 4.1 Mobile Card-Layouts | 2 Tage | Mittel |
| 4 | 2.2 Permissions-Dashboard | 3 Tage | Mittel |
| 5 | 4.5 Keyboard-Shortcuts | 2 Tage | Mittel |
| 6 | 1. File-Journal für Runtime | 3–5 Tage | Niedrig |
| 7 | 2.3 MCP-Server OAuth-UI | 2 Tage | Niedrig |
| 8 | 4.4 Theme Toggle | 1 Tag | Niedrig |