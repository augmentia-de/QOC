# Analyse: Zwei verschiedene „Agenten"-Systeme in QOC

> Status: Analyse (2026-11-13)
> Ergänzt: `Workflow.md`, `Workflow-OIDC.md`, `CONFIG.md`

## 1. Übersicht

QOC kennt **zwei getrennte Agent-Systeme**, die nicht miteinander verbunden sind.
Ein Workflow kann QOC-definierte Agenten (z. B. `build`, `plan`, Markdown-Agenten)
derzeit **nicht** nutzen – nur Workflow-eigene Agenten oder globale
Workflow-Agenten.

---

## 2. System 1: QOC-Agenten (Engine-Ebene)

### 2.1 Quellen

| Quelle | Pfad / Mechanismus | Beschreibung |
|---|---|---|
| **Config (JSON)** | `settings.json` → `agents[]` / `application.properties` `agents` | `EngineConfig.load()` parst `agents[].name`, `system`, `mode`, `model`, `tools`, `permissions`, `rolesAllowed`, `mcpServers`, `hidden` |
| **Markdown (global)** | `~/.config/opencode/agents/` | `AgentMarkdownLoader.loadDirectory()` |
| **Markdown (projekt)** | `<workspace>/agents/` (wenn `workspaceSettingsEnabled`) | Nur wenn Workspace-Settings aktiv |
| **Runtime-definiert** | `engine.define(name, content)` | Nur im Speicher, geht bei Neustart verloren (außer persistiert) |
| **Baseline** | `defaultBuildAgent()`, `defaultPlanAgent()` | Fallback wenn keine Agenten konfiguriert |

### 2.2 Datenmodell

`EngineConfig.AgentConfig` (Record, `EngineConfig.java:719`):
- `name`, `system` (System-Prompt), `hidden`, `mode` (primary/subagent/all),
  `model`, `maxSteps`, `toolAllow`, `toolDeny`, `permissions` (Rules),
  `mcpServers`, `rolesAllowed`.

### 2.3 Verwendung

- **Quarkus Engine** (`Engine.java`): `agentCatalogue.config(name)` →
  `EngineConfig.AgentConfig` → `QocEngine.AgentConfig` → `runPrompt`.
- **Subagents**: `engine.isSubagent(name)`, `engine.agentAllowed(name, roles)`.
- **Sessions**: `createSession(agentName, ...)` → `AgentId` aus `AgentCatalogue`.
- **Skills**: `agentSkills(agentName)` → JIT-Skill-Auswahl.

### 2.4 Endpoints

- `GET /api/agent` → `AgentCatalogue.AgentDto {id, name, hidden}` (gefiltert
  nach `agentAllowed`).
- `GET /api/agent/{name}` → Content + Config + Hash.
- `POST/PUT/DELETE /api/agent/{name}` → Authoring (Admin, nicht serverless).

### 2.5 Lebenszyklus

- Geladen beim Start (`@PostConstruct load()`).
- Reload via `agentCatalogue.reload()` (bei Workspace-Wechsel).
- Runtime-definierte Agenten überleben Reloads (`runtimeConfigs`).
- Markdown-Agenten: global immer, projekt nur bei `workspaceSettingsEnabled`.

---

## 3. System 2: Workflow-Agenten (Workflow-Runner-Ebene)

### 3.1 Quellen

| Quelle | Pfad / Mechanismus | Beschreibung |
|---|---|---|
| **Eingebettet** | `WorkflowDefinition.agents` (JSON-Array in der Workflow-Definition) | Agenten, die Teil einer bestimmten Workflow-Definition sind |
| **Global (File-Store)** | `wf-data/agents/*.json` via `AgentStore` | Wiederverwendbare Agenten, referenziert per `agentRef` |

### 3.2 Datenmodell

`WorkflowDefinition.Agent` (Record, `WorkflowDefinition.java:125`):
- `id`, `name`, `version`, `category`, `model`, `temperature`, `maxTokens`,
  `topP`, `tools`, `guardrailsInput`, `guardrailsOutput`, `hooks`,
  `jsonOutput`, `active`, `systemPrompt`, `responseSchema`,
  `ownerId`, `rolesAllowed`.

### 3.3 Verwendung

- **Workflow-Node** (`type="agent"`): `agentRef` → `findAgent(def, agentRef)`
  → `WorkflowDefinition.Agent` → `toAgentConfig()` → `QocEngine.AgentConfig`
  → `engine.runPrompt(childSession, prefixedName, prompt, config, ...)`.
- **Prefixed Name**: `wf:<Name>:<Version>` (Namenskonflikt-Vermeidung).
- **Child-Session**: Jeder Agent-Knoten erzeugt eine eigene Session.

### 3.4 Endpoints

- `GET /api/agents` → `AgentStore.listAll()` (globale Agents).
- `GET /api/agents/{id}` → einzelner Agent.
- `POST /api/agents` → erstellen.
- `PUT /api/agents/{id}` → speichern (Rename möglich).
- `DELETE /api/agents/{id}` → löschen.

### 3.5 Lebenszyklus

- Geladen aus `wf-data/agents/*.json` (File-basiert).
- Cache in `AgentStore` (`ConcurrentHashMap`).
- CRUD über REST-API (UI).
- RBAC: `ownerId` + `rolesAllowed` (neu hinzugefügt).

---

## 4. Vergleich

| Aspekt | QOC-Agenten | Workflow-Agenten |
|---|---|---|
| **Speicherort** | settings.json / Markdown-Dateien | JSON-Files (`wf-data/agents/`) oder eingebettet |
| **Format** | Markdown (OpenCode-Stil) oder JSON-Config | JSON (Jackson) |
| **Modell-Config** | `EngineConfig.AgentConfig` | `WorkflowDefinition.Agent` |
| **Tools** | `toolAllow`/`toolDeny` (Patterns) | `tools` (Liste von Tool-Namen) |
| **Guardrails** | `permissions` (Rules) | `guardrailsInput`/`guardrailsOutput` (Listen) |
| **Skills** | JIT-Skill-Auswahl (`agentSkills`) | nicht unterstützt |
| **Modus** | `primary`/`subagent`/`all` | nicht vorhanden |
| **Roles** | `rolesAllowed` (QOC-seitig) | `rolesAllowed` (neu hinzugefügt) |
| **Owner** | nicht vorhanden | `ownerId` (neu hinzugefügt) |
| **Hidden** | `hidden` | `active` (invertiert) |
| **Structured Output** | nicht vorhanden | `responseSchema` (JSON Schema) |
| **Versionierung** | nicht vorhanden | `version`-Feld |
| **Kategorie** | nicht vorhanden | `category`-Feld |
| **Endpoint** | `/api/agent` (singular) | `/api/agents` (plural) |
| **UI** | Agent-Catalogue (eingeschränkt) | `Agents.tsx`, `PropertyPanel.tsx` |

---

## 5. Problem: Keine Brücke

### 5.1 Aktueller Zustand

`DefaultNodeDispatcher.findAgent()` (`DefaultNodeDispatcher.java:282`):

```java
private WorkflowDefinition.Agent findAgent(WorkflowDefinition def, String agentRef) {
    if (agentRef == null || agentRef.isBlank()) return null;
    for (WorkflowDefinition.Agent a : def.agents()) {
        if (a.id().equals(agentRef)) return a;
    }
    // Option B: agents are stored globally and referenced by id.
    return agentStore == null ? null : agentStore.get(agentRef);
}
```

**Kein Fallback auf QOC-Agenten.** Wenn `agentRef = "build"` (ein QOC-Agent),
liefert `findAgent` `null`. Dann:
- `toAgentConfig(null, ...)` erzeugt einen `AgentConfig` mit `model=null`,
  `systemPrompt=null`, leeren Tools/Guardrails.
- `runPrompt` nutzt `buildModelInfo` → fällt auf das Engine-Default-Modell
  zurück (weil `config.model()` blank).
- Der QOC-Agent verliert seinen System-Prompt, seine Tools, Guardrails und Skills.
- Der Agent-Name im Registry wird `wf:build:1.0.0` statt `build`.

→ **QOC-Agenten sind in Workflows de facto nicht nutzbar** (nur Namenskollision
mit degradiertem Verhalten).

### 5.2 UI

- Die Agent-Ref-Dropdown in `PropertyPanel.tsx` wird aus dem `agents`-Prop
  befüllt, das aus `AgentApi.list()` → `/api/agents` (Workflow-Store) kommt.
- QOC-Agenten (build, plan, Markdown-Agenten) erscheinen **nicht** in dieser
  Liste.
- Die `Agents.tsx`-Seite verwaltet ausschließlich Workflow-Agenten
  (CRUD via `/api/agents`).
- Der GraphEditor-Default-Agent "build" ist ein Workflow-Agent, nicht der
  QOC-build-Agent.

---

## 6. Lösungsoptionen

### Option A – Import-Endpoint (empfohlen)

Neuer Endpoint `POST /api/agents/import-from-qoc` (oder `/api/agents/import`),
der `engine.agents()` / `engine.agentConfig(name)` / `engine.agentContent(name)`
liest und in einen `WorkflowDefinition.Agent` konvertiert und in den
`AgentStore` schreibt. Danach erscheint der Agent in `/api/agents` und der
UI-Dropdown.

**Vorteile:** Keine Duplikation zur Laufzeit; Agenten sind editierbar in der UI.
**Nachteile:** Manueller Import; QOC-Änderungen werden nicht automatisch
synchronisiert.

### Option B – Fallback in `findAgent`

Wenn `agentRef` weder in `def.agents()` noch im `AgentStore` gefunden wird,
`engine.agentConfig(agentRef)` bzw. `engine.agentContent(agentRef)` abfragen
und on-the-fly in einen `WorkflowDefinition.Agent` konvertieren.

**Vorteile:** Keine Duplikation; immer aktuell.
**Nachteile:** Konvertierung bei jedem Run; QOC-Agenten erscheinen nicht in der
UI-Liste.

### Option C – Merge in `/api/agents`

`AgentStoreResource.list()` ergänzt QOC-Agenten (markiert mit `source: "qoc"`)
in die Antwort, sodass die UI sie anzeigt und auswählbar macht. Speichern
würde dann Option A auslösen.

**Vorteile:** UI zeigt alle Agenten.
**Nachteile:** Komplexere UI-Logik (read-only für QOC-Agenten).

---

## 7. Konvertierungsmapping (QOC → WorkflowDefinition.Agent)

| QOC (`EngineConfig.AgentConfig`) | Workflow (`WorkflowDefinition.Agent`) |
|---|---|
| `name` | `name` / `id` |
| `model` | `model` |
| `system` (systemPrompt) | `systemPrompt` |
| `tools` | `tools` |
| `guardrailsInput/Output` | `guardrailsInput/Output` |
| `hooks` | `hooks` |
| `mode` (primary/subagent/all) | (neu: `mode`-Feld?) |
| `skills` | (neu oder in systemPrompt einbetten) |
| `hidden` | `active` (invertiert) |
| `jsonOutput` | `jsonOutput` |
| `rolesAllowed` | `rolesAllowed` |
| `maxSteps` | (neu oder in `maxTokens`?) |

---

## 8. Einschränkungen / zu beachten

1. **Tools**: QOC-Agenten können QOC-interne Tools (Shell, Files, Web, MCP,
   Subagents) referenzieren. Der Workflow-Runner nutzt dieselbe
   `QocEngine`-Instanz, daher sind dieselben Tools verfügbar — aber der
   `ToolSnapshot` im Workflow-Modus ist leer (`toolSnapshot` = null). Prüfen,
   ob der Agent's `tools`-Liste im Workflow-Modus respektiert wird
   (`toAgentConfig` übergibt `a.tools()` an `AgentConfig.tools`).

2. **Permissions**: Workflow-Runner nutzt no-op PermissionGate; QOC-Agenten
   mit `least-privilege` würden im Workflow alles erlauben. Für Parität
   müsste der Gate übernommen werden.

3. **Skills**: QOC-Skills (JIT) werden im Workflow-Modus nicht geladen
   (kein `skillSelection`-Aufruf in `DefaultNodeDispatcher`). Wäre
   nachzurüsten.

4. **Subagents**: QOC-Agenten können Subagents aufrufen; im Workflow-Modus
   funktioniert das über die gleiche `SubagentTool`, aber
   Rekursionstiefen-Limits gelten.

5. **Markdown-Agenten**: `AgentMarkdownLoader` parst `.md`-Dateien. Diese
   könnten als Import-Quelle dienen (globales Verzeichnis scannen).

6. **RBAC**: QOC-Agenten haben `rolesAllowed`. Workflow-Agenten haben jetzt
   ebenfalls `rolesAllowed` (neu). Der Workflow-Runner prüft `rolesAllowed`
   derzeit nicht (nur `AgentAccess` im Quarkus-Engine). Wäre nachzurüsten
   in `DefaultNodeDispatcher` oder im Workflow-Runner.

---

## 9. SSOT-Unification (implementiert)

Agenten haben jetzt eine **Single Source of Truth**:

- **AgentStorePort** (Interface) mit Implementierungen: `AgentStore` (File),
  `InMemoryAgentStore`, `SqliteAgentStore`, `PostgresAgentStore`.
- **AgentStoreFactory** wählt das Backend analog zu `WorkflowStoreFactory`
  (auto/memory/sqlite/postgres/file).
- **DB-Schema**: `agent_definition`-Tabelle (V11-Migration) speichert Agenten
  als JSON.
- **WorkflowBeans** nutzt jetzt `AgentStoreFactory` statt `new AgentStore(dir)`.
- **AgentStoreResource** und **WorkflowResource** nutzen `AgentStorePort`.

### Format

- **On-Disk (OpenCode-kompatibel)**: Markdown-Dateien mit YAML-Frontmatter
  (`~/.config/opencode/agents/`, `<workspace>/agents/`).
- **In DB/File (SSOT)**: JSON (`WorkflowDefinition.Agent`-Format mit
  `ownerId`, `rolesAllowed`, `responseSchema`).
- **Workflows**: JSON in DB (bereits vorhanden via `WorkflowStoreFactory`).

### Umgesetzt (SSOT-Infrastruktur)

- `AgentStorePort` (Interface) mit Implementierungen: `AgentStore` (File),
  `InMemoryAgentStore`, `SqliteAgentStore`, `PostgresAgentStore`.
- `AgentStoreFactory` wählt das Backend analog zu `WorkflowStoreFactory`.
- DB-Schema: `agent_definition`-Tabelle (V11-Migration).
- `WorkflowBeans` nutzt `AgentStoreFactory` statt `new AgentStore(dir)`.
- `AgentStoreResource`, `WorkflowResource` nutzen `AgentStorePort`.
- `QocAgentConverter.fromQoc()` konvertiert `EngineConfig.AgentConfig` →
  `WorkflowDefinition.Agent`.
- `WorkflowRecovery.importQocAgents()` importiert QOC-Agenten (Config +
  Markdown global) beim Start in den unified Store.

### Umgesetzt (SSOT-Integration)

- `Engine.config()` (Quarkus) überträgt jetzt `system`, `tools` (aus
  `toolAllow`) in den Runtime-`QocEngine.AgentConfig` (vorher verloren).
- `DefaultNodeDispatcher.findAgent()` (Option B): QOC-Agenten, die nicht im
  Store liegen, werden on-the-fly aus dem Engine-`AgentResolver` konvertiert
  (`fromQocRuntime`) und in den Store geschrieben (Caching).
- `WorkflowRecovery.importQocAgents()` importiert beim Start (und bei
  `Engine.reloadConfig()`):
  1. Config-Agenten (settings.json / application.properties).
  2. Markdown-Agenten (global: `~/.config/opencode/agents/`).
  3. Markdown-Agenten (projekt-level, wenn `workspaceSettingsEnabled`).
- Projekt-Level Markdown-Agenten (`projectAgentDirs`) werden jetzt importiert.

### Verbleibende Arbeit (QOC-Seite)

- QOC Engine lädt Agenten derzeit noch aus `EngineConfig` (settings.json/Markdown)
  zusätzlich zum Store. Für volle SSOT: EngineConfig sollte Agenten aus
  `AgentStorePort` laden (oder EngineConfig.agents() aus dem Store befüllen).
- Markdown → JSON Import: implementiert (WorkflowRecovery.importQocAgents).
- JSON → Markdown Export: implementiert (AgentMarkdownExporter, best-effort
  beim create/save in AgentStoreResource).
- Verbleibend: EngineConfig.agents() liest noch aus settings.json/Markdown;
  für volle SSOT sollte EngineConfig Agenten aus AgentStorePort laden (oder
  agents() aus dem Store befüllen). Reihenfolge (Store-Population vor
  EngineConfig-Load) muss angepasst werden.
- RBAC (`rolesAllowed`) wird jetzt im Workflow-Runner geprüft
  (`DefaultNodeDispatcher.agentAllowed`); '*' = alle, admin/system immer erlaubt.

## 10. Fazit

Aktuell **keine** Nutzung von QOC-Agenten in Workflows möglich (außer
Namenskollision mit Verlust der Konfiguration). Für echte Nutzung ist ein
**Import- oder Fallback-Mechanismus** (Option A/B) plus **Konvertierung**
`EngineConfig.AgentConfig → WorkflowDefinition.Agent` nötig, sowie die
Behandlung von Tools/Skills/Permissions im Workflow-Modus.

Die SSOT-Infrastruktur (DB-Backend, Factory, Migration) ist jetzt vorhanden.
Der nächste Schritt ist die QOC-seitige Integration (Agenten aus Store laden
oder Markdown importieren).
