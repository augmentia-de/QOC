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
