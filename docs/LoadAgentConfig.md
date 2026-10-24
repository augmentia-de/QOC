# LoadAgentConfig – Agent-Konfiguration neu laden

## Ziel

Eine Agent-Konfiguration soll zur Laufzeit neu geladen werden können, ohne die Anwendung neu zu starten und ohne bestehende Sessions zu zerstören.

Der Reload soll wahlweise:

- in der **gleichen Session** weiterarbeiten, oder
- eine **neue Session** starten.

Zusätzlich soll die Konfiguration von außen überschrieben werden können, sowohl **vor** als auch **während** des Reloads.

## Aktueller Zustand

- `EngineConfig` wird einmalig in `@PostConstruct` geladen.
- `AgentCatalogue` wird einmalig aus `EngineConfig.agents()` gebaut.
- `Engine.wire()` baut einmalig:
  - Tool-Registry
  - MCP-Server
  - globalen `ToolSnapshot`
  - `ModelRef`
  - `ContextBuilder`
- `runPrompt(...)` liest `agentCatalogue.config(agentName)` bei jedem Prompt dynamisch.
- `filterToolSnapshot(...)` filtert den globalen Snapshot pro Prompt anhand der Agent-Config.
- Der globale `ToolSnapshot` ist eingefroren; MCP-Tools werden beim Startup registriert.
- Sessions speichern den Agenten als `AgentId` in der Datenbank.

Daraus folgt:

- Agent-Tool-/MCP-Filter und Modell-Override wirken beim nächsten Prompt bereits.
- Globaler Modell-/Provider-Wechsel und neue MCP-Server erfordern heute einen Neustart.
- Agent-Identitäten (`AgentId`) dürfen nicht einfach neu generiert werden, sonst brechen bestehende Sessions.

## Zielbild

Ein Reload soll drei Dinge können:

1. Config neu laden aus Dateien plus optionalem externem Override.
2. Agenten stabil aktualisieren, ohne bestehende Session-IDs zu brechen.
3. Wahlweise in derselben Session weiterarbeiten oder eine neue Session starten.

## Reload-Modell

### A. Atomarer Config-Snapshot

- `EngineConfig` als unveränderliches Snapshot-Objekt behandeln.
- `AgentCatalogue` als Snapshot-Objekt behandeln.
- `Engine` hält `volatile ConfigSnapshot configSnapshot`.
- Reload baut zuerst einen neuen Snapshot, validiert ihn und tauscht ihn dann atomar.

Vorteil: Laufende Prompts sehen entweder den alten oder den neuen Snapshot, aber nie einen halbfertigen Zustand.

### B. Agent-Identitäten stabil halten

Beim Reload:

- Bestehende Agenten nach Namen matchen.
- IDs für gleichnamige Agenten beibehalten.
- Neue Agenten bekommen neue IDs.
- Entfernte Agenten bleiben in einer `removedAgents`-Map, damit bestehende Sessions beim nächsten Prompt auf den Primär-Agenten zurückfallen können.

So bleiben `SessionInfo.agent` gültig. Bei unbekanntem Agent-Namen fallback auf `primaryId()`.

### C. Modell/Provider

- Globaler Modellwechsel aktualisiert `ModelRef` im neuen Snapshot.
- Agent-spezifisches `model` wird ohnehin in `runPrompt` aus `agentConfig` gelesen.

### D. Tools/MCP

Phase 1, klein:

- Agent-Tool-Restriktionen und MCP-Filter werden bereits pro Prompt aus dem Snapshot gelesen.
- Kein Rebuild des globalen Tool-Snapshots nötig.

Phase 2, vollständig:

- `McpServerManager` um `reconcile(List<McpServerConfig>)` erweitern:
  - neue Server hinzufügen
  - geänderte Server neu discovern
  - entfernte Server aus Registry entfernen
  - Status/Fehler neu setzen
- Tool-Snapshot neu bauen, falls MCP-Server oder statische Tools sich ändern.

## Reload-Ablauf

`POST /api/config/reload`

Body:

```json
{
  "mode": "same",
  "sessionId": "ses_...",
  "agent": "researcher",
  "override": {
    "agents": [],
    "model": { "provider": "openai", "id": "..." },
    "provider": { "url": "..." }
  },
  "persist": false
}
```

Ablauf:

1. Base-Config aus globaler und Projektdatei laden.
2. Override anwenden.
3. Validieren:
   - JSON gültig
   - Agent-Namen eindeutig
   - Tools/Permissions/MCP-Server plausibel
   - Modell vorhanden
4. Neuen Agent-Catalogue-Snapshot bauen.
5. Neuen Config-Snapshot validieren.
6. Atomic swap.
7. MCP reconcile (Phase 2).
8. Session-Handling gemäß `mode`.

### Gleiche Session

- Session bleibt bestehen.
- Konversation und Kontext bleiben.
- Agent wird bei Bedarf auf denselben Namen neu aufgelöst.
- Wenn der bisherige Agent entfernt wurde, fallback auf Primär-Agent.
- Nächster Prompt nutzt die neue Config.

Vorteil: Kontext bleibt, ideal für Prompt-Iterationen.

### Neue Session

- Alte Session bleibt unverändert.
- Neue Session wird mit dem gewünschten Agenten erstellt.
- Optionaler Parameter `agent` wählt den Start-Agenten.

Vorteil: sicherer, keine Überraschungen bei laufenden Agenten.

## Externe Overrides

### Vor dem Reload

Unterstützen:

1. **Datei-Override**

```bash
ENGINE_CONFIG_OVERRIDE=/opt/qoc/settings.override.json
```

Die Datei wird nach globaler und Projektdatei gemergt. Das ist ideal für Deployments oder SSH-basierte Änderungen.

2. **Projektdatei**

`<workspace>/settings.json` ist ohnehin die Projekt-Config.

3. **Umgebungsvariablen**

Bereits vorhandene `ENGINE_*` Variablen bleiben die Primärquelle für Infrastrukturwerte.

### Während des Reloads

Der API-Body kann einen `override` enthalten. Semantik:

- Objekte werden feldweise gemergt.
- `agents`-Liste im Override ersetzt die bestehende Liste, nicht anhängt.
- `mcp`-Objekt wird feldweise gemergt.
- Ungültiger Override führt zu `400` und keinem Swap.

Damit kann man von außen kurzfristig Parameter ändern, ohne Dateien zu schreiben.

### Persistenz

Zwei Modi:

- `persist: false` (Default): Override nur im Speicher für diese Laufzeit.
- `persist: true`: Override wird in `<workspace>/settings.runtime.json` geschrieben und bei späteren Starts/Reloads erneut angewendet.

Das verhindert, dass temporäre Experimente dauerhaft die Datei verändern.

## Konflikte und Nebenläufigkeit

- Reload und Prompt-Ausführung dürfen sich nicht gegenseitig stören.
- Empfohlen: `ReadWriteLock`.
  - Prompt hält Read-Lock.
  - Reload hält Write-Lock.
- Retention-Scheduler läuft unabhängig; er greift nur auf DB zu und ist nicht config-abhängig.
- Laufende Sessions werden beim Reload nicht abgebrochen.
- Wenn ein Agent während eines Prompts entfernt wird, läuft der Prompt mit dem alten Snapshot zu Ende; der nächste Prompt nutzt den neuen.

## UI

Neue Controls:

- `Reload Config`
- Radio/Select: `Same session` oder `New session`
- Optional `Agent` für neue Session
- Textfeld für JSON-Override, optional
- Anzeige von Validierungsfehlern
- Anzeige des aktiven Config-Stands (`/api/config`)

Sinnvolle Voreinstellung:

- `New session`, damit bestehende Sessions nicht überraschend ihr Verhalten ändern.

## API

### Reload

```http
POST /api/config/reload
Content-Type: application/json

{
  "mode": "same",
  "sessionId": "ses_...",
  "agent": "researcher",
  "override": { ... },
  "persist": false
}
```

Antwort:

```json
{
  "ok": true,
  "config": { ... },
  "sessionId": "ses_...",
  "warnings": []
}
```

### Override speichern

```http
PUT /api/config/override
```

Body wie oben `override`. Speichert nur den Override, ohne Reload.

### Override entfernen

```http
DELETE /api/config/override
```

### Status

`GET /api/config` liefert weiterhin die effektive Config, optional mit Quelle:

```json
{ "key": "model.id", "value": "...", "source": "override" }
```

## Risiken und offene Punkte

- Agent-IDs müssen stabil bleiben, sonst brechen alte Sessions.
- MCP-Server-Removal braucht eine echte Reconcile-Funktion im `McpServerManager`.
- Tool-Snapshot-Rebuild kann laufende Prompts nicht beeinflussen; das ist gewollt.
- Ein externer Override kann semantisch unklar sein. Deshalb:
  - `agents` als ganze Liste ersetzen
  - Objekte feldweise mergen
  - Validierung vor Swap
- Ein externer Override kann sensible Werte wie API-Keys enthalten. Persistierte Override-Datei sollte `0600` sein.
- Wenn `engine.workspace` geändert wird, betrifft das nur neue Sessions; bestehende Sessions behalten ihr Verzeichnis.

## Empfohlene Umsetzungsreihenfolge

1. `EngineConfig` reload-fähig machen und Override-Merge implementieren.
2. Agent-Catalogue-Snapshot mit stabilen IDs einführen.
3. `Engine` auf `volatile ConfigSnapshot` umstellen.
4. Reload-API und Validierung.
5. Same/New-Session-Verhalten.
6. UI-Controls.
7. MCP-Reconcile und Tool-Snapshot-Rebuild.
8. Persistierter Override.

Das ergibt einen sicheren, nachvollziehbaren Reload ohne Neustart und ohne bestehende Sessions zu zerstören.

---

# Task-/Execution-Gateway

## Ziel

Eine Instanz soll eine Aufgabe zusammen mit einer Konfiguration über einen eingehenden Transport empfangen, einen speziellen Agenten laden bzw. temporär definieren, den User-Prompt ausführen und danach entweder:

- in einer vorhandenen Session **sequentiell** weiterarbeiten, oder
- eine **neue Session** erstellen, ausführen und am Ende wieder löschen.

Unterstützte Transporte:

- REST
- gRPC
- Kafka

Die Kernlogik gehört in einen transportunabhängigen `ExecutionGateway`. REST, gRPC und Kafka sind nur Adapter.

## Request-Modell

```json
{
  "requestId": "req-123",
  "prompt": "Analysiere ...",
  "agent": "researcher",
  "agentDefinition": {
    "name": "researcher",
    "system": "...",
    "model": "deepseek-v4.1-flash",
    "maxSteps": 20,
    "tools": { "allow": ["read", "websearch", "subagent"], "deny": [] },
    "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
    "mcpServers": ["*"]
  },
  "configOverride": {
    "model": { "provider": "openai", "id": "..." },
    "provider": { "url": "..." }
  },
  "sessionId": "ses_...",
  "mode": "reuse",
  "deleteOnComplete": true,
  "retainOnError": false,
  "timeoutSeconds": 300
}
```

Feldbedeutung:

| Feld | Bedeutung |
|---|---|
| `requestId` | Idempotency-Key und Audit-ID |
| `prompt` | Auszuführender User-Prompt |
| `agent` | Name eines bereits konfigurierten Agenten |
| `agentDefinition` | Temporäre Agent-Definition nur für diesen Lauf |
| `configOverride` | Temporäre Config-Override-Schicht für diesen Lauf |
| `sessionId` | Vorhandene Session für sequentielle Ausführung |
| `mode` | `reuse` oder `new` |
| `deleteOnComplete` | Ephemere Session nach Erfolg löschen |
| `retainOnError` | Ephemere Session bei Fehler behalten |
| `timeoutSeconds` | Maximale Ausführungsdauer |

## ExecutionGateway

```text
ExecutionGateway.execute(ExecutionRequest) -> ExecutionResponse
```

Ablauf:

1. Request validieren.
2. Temporären Config-Snapshot bauen.
3. Agent auflösen oder temporär definieren.
4. Session auswählen oder erstellen.
5. Prompt ausführen.
6. Ergebnis normalisieren.
7. Ephemere Session aufräumen.
8. Audit- und Metrikdaten schreiben.

Per-Request-Konfiguration darf **nicht** die globale Config verändern. Sonst beeinflussen sich parallele Requests gegenseitig. Deshalb arbeitet der Gateway mit einem temporären Snapshot.

## Agent-Auflösung

### Variante A: Konfigurierter Agent

```json
{ "agent": "researcher" }
```

- Der Gateway nutzt `EngineConfig.AgentConfig` aus dem aktuellen Snapshot.
- Keine Registrierung oder Mutation nötig.

### Variante B: Inline-Agent

```json
{ "agentDefinition": { "name": "researcher", "...": "..." } }
```

- Der Gateway erzeugt einen temporären Agenten, z. B. `exec-<uuid>`.
- Die Definition wird nur für diesen Lauf verwendet.
- Keine globale Registrierung im `AgentCatalogue`.

Dafür sollte `Engine` eine Überladung erhalten:

```java
PromptResult runPrompt(
    SessionId id,
    String text,
    AgentProfile agentOverride,
    EngineConfig.AgentConfig agentConfig
)
```

Wenn `agentConfig` gesetzt ist, verwendet `runPrompt` diese Definition für:

- System-Prompt
- Modell-Override
- `maxSteps`
- Permissions
- Tool-Snapshot-Filter
- MCP-Scoping

## Per-Request-Config-Override

Der Gateway baut einen temporären Config-Snapshot:

1. Basis aus dem aktuellen `EngineConfig`-Snapshot.
2. `configOverride` feldweise mergen.
3. Optional `agentDefinition` einfügen.
4. Validieren.
5. Snapshot für genau diesen Lauf verwenden.

Merge-Semantik:

- Objekte: feldweise mergen.
- `agents`: vollständig ersetzen, nicht anhängen.
- `mcp`: feldweise mergen.
- Ungültige Werte: Request ablehnen, keine Session erstellen.

Der Override wird standardmäßig nicht persistiert.

## Session-Modi

### A. Sequentiell mit vorhandener Session

```json
{
  "prompt": "...",
  "sessionId": "ses_...",
  "mode": "reuse"
}
```

Ablauf:

1. Session laden.
2. Optional Agent wechseln.
3. Prompt ausführen.
4. Session behalten.
5. Ergebnis zurückgeben.

Eigenschaften:

- Konversation und Kontext bleiben erhalten.
- Nächster Request kann dieselbe Session weiterverwenden.
- Wenn die Session bereits läuft: `409 Conflict` oder Queue.

### B. Neue ephemere Session

```json
{
  "prompt": "...",
  "mode": "new",
  "deleteOnComplete": true
}
```

Ablauf:

1. Neue Session mit dem gewählten Agenten erstellen.
2. Prompt ausführen.
3. Im `finally`-Block löschen, wenn `deleteOnComplete=true`.
4. Ergebnis zurückgeben.

Eigenschaften:

- Kein Session-Leak bei normalen Fehlern.
- Ideal für stateless Task-Ausführung.
- Wenn das Löschen fehlschlägt, Session-ID im Response zurückgeben.

## Concurrency

Global:

- Eigener Semaphore für den Gateway, z. B. 4 parallele Ausführungen.
- Timeout für das Acquire, z. B. 5 Minuten.

Pro Session:

- `mode=reuse` muss pro Session serialisiert werden.
- Empfohlen: `ConcurrentHashMap<SessionId, ReentrantLock>` oder Single-Thread-Executor pro Session.
- Kafka: Partitionierung nach `sessionId` oder `requestId`.

## Fehlerbehandlung

| Fehler | Verhalten |
|---|---|
| Ungültige Config | `400`, keine Session erstellen |
| Unbekannter Agent | `400` |
| Session nicht gefunden | `404` |
| Session läuft bereits | `409` oder Queue |
| Prompt fehlgeschlagen | Fehler zurückgeben, ephemere Session trotzdem löschen |
| Löschen fehlgeschlagen | Ergebnis plus Warnung und Session-ID zurückgeben |
| Timeout | Lauf abbrechen, Cleanup, `504` |

## REST

```http
POST /api/execute
Content-Type: application/json
```

Antwort:

```json
{
  "requestId": "req-123",
  "status": "completed",
  "finishReason": "NORMAL",
  "text": "...",
  "inputTokens": 123,
  "outputTokens": 45,
  "sessionId": null,
  "durationMs": 1234
}
```

## gRPC

```proto
service ExecutionService {
  rpc Execute(ExecutionRequest) returns (ExecutionResponse);
}
```

Gleiche Semantik wie REST. Authentifizierung über TLS/mTLS.

## Kafka

- Request-Topic: `qoc.execute.request`
- Response-Topic: `qoc.execute.response`
- Key: `sessionId` oder `requestId`
- Payload: JSON oder Avro
- Fehler: Response mit `status=failed` oder Dead-Letter-Topic

Kafka ist at-least-once. Deshalb muss der Gateway idempotent sein:

- `requestId` speichern.
- Bereits abgeschlossene Requests nicht erneut ausführen.
- Ergebnis aus dem Idempotency-Store zurückgeben.

## Audit und Beobachtbarkeit

- Jeder Lauf erhält eine `requestId`.
- Loggen:
  - Agent
  - Session
  - Finish-Reason
  - Token-Usage
  - Dauer
  - Fehler
- Optional: Ergebnisse in einer separaten Audit-Tabelle speichern, da ephemere Sessions gelöscht werden.
- Tracing über OpenTelemetry, falls aktiviert.

## Sicherheit

- REST: bestehende Basic-Auth.
- gRPC: TLS/mTLS.
- Kafka: SASL/SSL.
- Config-Override nur für autorisierte Clients.
- Inline-Agent-Definitionen validieren:
  - erlaubte Tools
  - erlaubte MCP-Server
  - maximale `maxSteps`
  - keine ungeprüften System-Prompts für unautorisierte Clients

## Risiken

- Per-Request-Config erhöht die Komplexität.
- Zu viele parallele Läufe überlasten die e2-micro-Instanz.
- Ephemere Sessions müssen zuverlässig gelöscht werden.
- Inline-Agenten dürfen keine unerlaubten Tool- oder MCP-Zugriffe erhalten.
- Kafka at-least-once erfordert Idempotency.

## Empfohlene Umsetzungsreihenfolge

1. `ExecutionRequest` und `ExecutionResponse` definieren.
2. `Engine.runPrompt(..., AgentConfig)`-Überladung einführen.
3. `ExecutionGateway` für REST mit vorhandenen Agenten implementieren.
4. Ephemere Sessions mit garantiertem Cleanup.
5. Inline-Agent-Definitionen.
6. Per-Request-Config-Override.
7. Idempotency und Audit.
8. gRPC-Adapter.
9. Kafka-Adapter.

Das ergibt eine Task-Instanz, die sowohl stateless als auch sequentiell arbeiten kann, ohne globale Config-Zustände zu verändern.

---

# Verteilte Orchestrator-/Worker-Architektur

## Ziel

Ein Quarkus-Orchestrator-Container steuert deterministisch mehrere Worker-Container. Die Worker führen Agent-Aufgaben aus. Orchestrator und Worker teilen sich eine Datenbank für Sessions, Events, Config und Audit.

Der Orchestrator kann Agent-Definitionen anpassen und neue Config-Versionen erzeugen. Worker erhalten nur eine Config-ID und Version.

## Grundprinzip

- **Orchestrator**: empfängt Tasks, löst Config auf, wählt Worker, dispatcht, überwacht, aggregiert.
- **Worker**: lädt Config-Version, führt Prompt in einer Session aus, schreibt Ergebnisse.
- **Gemeinsame PostgreSQL-DB**: Sessions, Events, Config, Task-Status, Audit.
- **Gemeinsamer Workspace**: alle Worker sehen dieselben Dateien.
- **Agent-Config versioniert in der DB**, nicht nur in `settings.json`.

SQLite ist für mehrere Container ungeeignet. Für Shared-State muss PostgreSQL genutzt werden.

## Datenmodell

### Agent-Config

```text
agent_config
  id uuid primary key
  name text not null
  created_at timestamptz not null

agent_config_version
  id uuid primary key
  config_id uuid not null references agent_config(id)
  version int not null
  definition jsonb not null
  created_at timestamptz not null
  created_by text
  unique (config_id, version)
```

Die `definition` entspricht der bekannten Agent-Definition:

```json
{
  "name": "researcher",
  "system": "...",
  "model": "deepseek-v4.1-flash",
  "maxSteps": 20,
  "tools": { "allow": ["read", "websearch", "subagent"], "deny": [] },
  "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
  "mcpServers": ["*"]
}
```

### Execution Task

```text
execution_task
  id uuid primary key
  request_id text unique not null
  session_id text references session(id)
  agent_config_version_id uuid references agent_config_version(id)
  prompt text not null
  status text not null
  created_at timestamptz not null
  started_at timestamptz
  finished_at timestamptz
  worker_id uuid
```

Statuswerte:

```text
queued
running
completed
failed
cancelled
```

### Execution Result

```text
execution_result
  task_id uuid primary key references execution_task(id)
  finish_reason text
  text text
  input_tokens bigint
  output_tokens bigint
  duration_ms bigint
  error text
  created_at timestamptz not null
```

### Worker Registry

```text
worker_instance
  id uuid primary key
  name text
  endpoint text
  status text
  last_heartbeat timestamptz
  capacity int
  active_tasks int
```

## Warum Config-ID statt vollständiger Config?

Empfohlen wird eine **Config-ID plus Version**:

```json
{
  "configId": "cfg_123",
  "configVersion": 3,
  "prompt": "...",
  "sessionId": "ses_..."
}
```

Vorteile:

- kleine Messages
- versionierte und auditierbare Config
- laufende Tasks bleiben an ihre Version gebunden
- Orchestrator kann neue Versionen erzeugen, ohne Worker neu zu deployen
- Rollback auf ältere Versionen möglich

Inline-Definitionen können zusätzlich unterstützt werden. Der Orchestrator erzeugt daraus eine temporäre Config-Version.

## Orchestrator

Der Orchestrator ist deterministisch. Er trifft keine inhaltlichen LLM-Entscheidungen über Routing, sondern nutzt Regeln.

Aufgaben:

- Task entgegennehmen
- Config-ID/Version auflösen
- Session auswählen oder erstellen
- Worker auswählen oder starten
- Task dispatchen
- Retry, Timeout und Cancel steuern
- Ergebnisse aggregieren
- ephemere Sessions löschen
- Audit schreiben

### Orchestrator-Tools

- `create_agent_config`
- `update_agent_config`
- `get_agent_config`
- `start_worker`
- `stop_worker`
- `list_workers`
- `submit_task`
- `get_task_status`
- `cancel_task`
- `run_in_session`

### Deterministische Regeln

Beispiele:

```text
task.type = "research"   -> Agent "researcher"
task.type = "code-review" -> Agent "reviewer"
task.priority = "high"   -> Worker-Pool "fast"
```

Weitere Kriterien:

- Capability-Matrix
- Worker-Auslastung
- Retry-Policy
- Timeout-Policy
- Fallback-Agent

## Worker

Worker laufen im Modus:

```bash
QOC_ROLE=worker
```

Sie bieten einen Execution-Endpoint an:

```http
POST /api/execute
```

oder konsumieren Kafka-Nachrichten.

Worker-Aufgaben:

- Config-Version aus DB laden
- Session laden oder erstellen
- Prompt ausführen
- Events und Ergebnis in DB schreiben
- ephemere Session löschen
- Health/Heartbeat melden

Worker sollten keine UI und keine globale Agent-Verwaltung besitzen. Sie sind reine Ausführungsinstanzen.

## Shared State

### Datenbank

PostgreSQL ist die gemeinsame Quelle für:

- Sessions
- Event-Log
- Messages
- Inbox
- Tool-Output
- Config-Versionen
- Task-Status
- Audit

### Workspace

Sessions referenzieren Verzeichnisse wie `/data/workspace`. In einer verteilten Umgebung muss dieser Pfad für alle Worker gleich sein.

Optionen:

1. Shared Volume / NFS
2. Object Storage mit lokalem Cache
3. Worker-spezifische Workspaces mit Dateitransfer

Für den ersten Schritt: gemeinsames Volume mounten.

Wichtig: Wenn Worker unterschiedliche Dateien sehen, werden `read`, `write`, `edit`, `glob` und `grep` inkonsistent.

## Session-Strategie

### A. Sequentiell mit vorhandener Session

- Orchestrator übergibt `sessionId`.
- Worker lädt die Session aus der DB.
- Worker wechselt bei Bedarf den Agenten.
- Worker führt den Prompt aus.
- Session bleibt erhalten.

### B. Neue ephemere Session

- Worker erstellt eine neue Session.
- Worker führt den Prompt aus.
- Worker löscht die Session im `finally`.
- Ergebnis wird in `execution_result` gespeichert, damit Audit erhalten bleibt.

### C. Parallele Subtasks

- Orchestrator startet mehrere Worker.
- Jeder Worker bekommt eine eigene Session oder
- dieselbe Session wird pro Worker serialisiert.

## Config-Anpassungen durch den Orchestrator

Der Orchestrator schreibt neue Versionen, statt bestehende zu überschreiben:

```http
POST /api/agent-configs
POST /api/agent-configs/{id}/versions
```

Beispiel:

- `researcher` v3 ist die Basis.
- Orchestrator erzeugt `researcher` v4 mit angepasstem System-Prompt oder Tool-Set.
- Task referenziert v4.
- Worker lädt v4.

Vorteile:

- laufende Tasks bleiben stabil
- Auditierbarkeit
- einfacher Rollback
- keine stillen Änderungen an alten Tasks

## Kommunikation

### REST

Gut für synchrone, kleine Aufgaben:

```http
POST /api/execute
```

### gRPC

Gut für typisierte Service-zu-Service-Kommunikation.

### Kafka

Gut für:

- asynchrone Tasks
- Entkopplung
- Replay und Audit
- Skalierung

Empfehlung:

- Kafka für Task-Dispatch und Ergebnisse
- REST/gRPC für Status, Config und Steuerung

## Worker-Lifecycle

Erster Schritt:

- statische Worker-Container
- Health-Checks
- Round-Robin- oder Least-Busy-Verteilung

Später:

- dynamisches Starten von Workern
- Kubernetes Jobs/Pods
- Autoscaling
- Priority Queues
- Worker Pools nach Capability

## Sicherheit

- Service-zu-Service-Authentifizierung über mTLS oder JWT.
- Worker dürfen Config-Versionen nur lesen.
- Orchestrator darf Config-Versionen und Tasks verwalten.
- Tenant-Isolation in DB und Workspace.
- Shell-Tools in Workern nur mit Sandbox/Containment.
- Agent-Definitionen vor dem Speichern validieren.
- Keine Secrets in Task-Payloads; Config referenziert Credentials über IDs.

## Observability

- Distributed Tracing über OpenTelemetry.
- Jeder Task hat `requestId`, `taskId`, `sessionId`, `workerId`.
- Metriken:
  - Task-Latenz
  - Worker-Auslastung
  - Fehlerrate
  - Token-Usage pro Agent
  - Session-Lebensdauer
- Logs zentral sammeln.

## Risiken

- SQLite ist für mehrere Worker ungeeignet.
- Shared Workspace erfordert konsistente Mounts oder Dateitransfer.
- Config-Mutationen dürfen laufende Tasks nicht beeinflussen; Versionierung ist Pflicht.
- Worker-Crashes können ephemere Sessions hinterlassen; Retention und Recovery nötig.
- Zu viele Config-Versionen blähen DB und Audit auf.
- Tool-Ausführung in Workern ist sicherheitskritisch.
- Kafka at-least-once erfordert Idempotency.

## Empfohlene Umsetzungsreihenfolge

1. PostgreSQL als gemeinsame DB nutzen.
2. `agent_config` und `agent_config_version` einführen.
3. Config-ID/Version in Task-Requests verwenden.
4. Execution-Gateway transportunabhängig bauen.
5. Worker-Modus für QOC-Container einführen.
6. Statische Worker und Round-Robin-Dispatch.
7. Session-Reuse und ephemere Sessions mit Cleanup.
8. Orchestrator-Tools für Worker- und Task-Verwaltung.
9. Kafka-Dispatch und Ergebnisse.
10. Dynamisches Worker-Scaling.
11. Vollständige Observability.

Das ergibt eine skalierbare Architektur, bei der Config und Sessions zentral liegen, Worker zustandslos ausführen und der Orchestrator deterministisch steuert.
