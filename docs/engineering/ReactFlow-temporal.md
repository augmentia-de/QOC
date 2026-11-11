# ReactFlow + QOC Engine — Plan & Analyse

**Status:** Draft (2026-11-10) · Ziel: React-UI mit ReactFlow-Graph-Editor + QOC Engine als Backend

---

## 1. Ziel

Eine neue React-basierte UI mit ReactFlow als Graph-Editor, die QOC Engine (nicht agentflow-app/quad-core) als Backend nutzt. Workflows werden im ReactFlow-Format (Nodes + Edges) definiert und ausgeführt. Agent-Konfiguration erfolgt über ein integriertes Property Window im Graph-Editor.

---

## 2. Motivation

| Aspekt | Aktuell (Angular + agentflow-app) | Ziel (React + QOC Engine) |
|--------|-----------------------------------|---------------------------|
| UI-Framework | Angular 20 | React 19 |
| Graph-Editor | Custom SVG (~700 Zeilen) | ReactFlow (@xyflow/react) |
| Backend | agentflow-app (Spring Boot + quad-core) | Temporal-Modul (plain Java + QOC Engine) |
| Agent-Runtime | quad-core (reif, 289 Tests) | QOC Engine (früh, runPrompt = Platzhalter) |
| Storage | In-Memory (quad-core) | Memory/SQLite/Postgres per Port (QOC) |
| Workflow-Format | v2 (dependsOn-Arrays) | ReactFlow (Nodes + Edges) |

---

## 3. Ist ein "Property Window für Agent-Config" in agentflow enthalten?

**Teilweise.** agentflow hat zwei getrennte Ansichten:

| Aspekt | agentflow (Angular) | Gewünscht (React) |
|--------|---------------------|-------------------|
| Node-Properties im Graph-Editor | ✅ Side-Panel (ID, Title, Type, Dependencies, Agent-Ref, HITL-Assignee, Loop-Body, Branches, Join-Type) | ✅ Gleich, als ReactFlow-Side-Panel |
| Agent-Config (Model, Temperature, SystemPrompt, Tools, Guardrails) | ❌ **Nicht** im Graph-Editor. Separate Seite `/agents` mit Formular | ✅ **Property Window** im Graph-Editor: Agent-Node auswählen → Agent-Config direkt bearbeiten |
| Agent-Ref Dropdown | ✅ Im Graph-Editor | ✅ Gleich, plus Inline-Edit |

**Fazit:** agentflow hat Node-Properties im Graph-Editor, aber Agent-Config ist auf einer separaten Seite. Ein **integriertes Property Window** (Agent-Config direkt im Graph-Editor-Side-Panel) ist **neu** und nicht in agentflow enthalten.

---

## 4. ReactFlow-Format vs. agentflow v2-Format

### 4.1 agentflow v2-Format (dependsOn-Arrays)

```json
{
  "format": "quad.workflow",
  "schemaVersion": 2,
  "mainWorkflow": {
    "id": "support-triage",
    "name": "Customer Support Triage",
    "nodes": [
      { "id": "classify", "type": "agent", "title": "Classify ticket", "agent": { "ref": "ex-support-classifier" }, "requiresHumanApproval": true },
      { "id": "playbook", "type": "agent", "title": "Find playbook", "dependsOn": ["classify"], "agent": { "ref": "ex-playbook-finder" } },
      { "id": "respond", "type": "agent", "title": "Draft reply", "dependsOn": ["playbook"], "agent": { "ref": "ex-support-writer" } },
      { "id": "end", "type": "end", "title": "Done", "dependsOn": ["respond"] }
    ]
  },
  "agents": [
    { "id": "ex-support-classifier", "name": "Support Classifier", "model": "deepseek-v4.1-flash", "temperature": 0.1, "maxTokens": 1024, "systemPrompt": "..." }
  ]
}
```

**Struktur:** Nodes haben `dependsOn: string[]` — implizite Edges. Keine expliziten Edge-Objekte.

### 4.2 ReactFlow-Format (Nodes + Edges)

```json
{
  "nodes": [
    { "id": "classify", "type": "agent", "position": { "x": 0, "y": 0 }, "data": { "title": "Classify ticket", "agentRef": "ex-support-classifier", "requiresHumanApproval": true } },
    { "id": "playbook", "type": "agent", "position": { "x": 250, "y": 0 }, "data": { "title": "Find playbook", "agentRef": "ex-playbook-finder" } },
    { "id": "respond", "type": "agent", "position": { "x": 500, "y": 0 }, "data": { "title": "Draft reply", "agentRef": "ex-support-writer" } },
    { "id": "end", "type": "end", "position": { "x": 750, "y": 0 }, "data": { "title": "Done" } }
  ],
  "edges": [
    { "id": "e-classify-playbook", "source": "classify", "target": "playbook" },
    { "id": "e-playbook-respond", "source": "playbook", "target": "respond" },
    { "id": "e-respond-end", "source": "respond", "target": "end" }
  ],
  "agents": [
    { "id": "ex-support-classifier", "name": "Support Classifier", "model": "deepseek-v4.1-flash", "temperature": 0.1, "maxTokens": 1024, "systemPrompt": "..." }
  ]
}
```

**Struktur:** Explizite Nodes mit Position + Edges. ReactFlow-native.

### 4.3 Konvertierung (ReactFlow ↔ v2)

| ReactFlow | v2 (agentflow) |
|-----------|----------------|
| `node.id` | `node.id` |
| `node.type` | `node.type` |
| `node.data.title` | `node.title` |
| `node.data.agentRef` | `node.agent.ref` |
| `node.data.requiresHumanApproval` | `node.requiresHumanApproval` |
| `edges.filter(e => e.target === node.id).map(e => e.source)` | `node.dependsOn` |
| `node.position` | (nicht in v2, aber in localStorage/DB) |

**Empfehlung:** Intern ReactFlow-Format nutzen (für den Editor), für die Ausführung in v2-Format konvertieren (für den Backend). Oder: Backend direkt ReactFlow-Format akzeptieren.

---

## 5. Workflow-Ausführung basierend auf ReactFlow-Format

### 5.1 Wie agentflow die Ausführung macht

agentflow nutzt **LangGraph4j** (Java) für die Ausführung. Der `DeclarativePlanner` flattet die v2-Definition in einen Graphen, und `DeclarativeAgentWorkflow` interpretiert ihn.

**Schritte:**
1. `DeclarativePlanner.flatten()` — Sub-Workflows inline auflösen
2. `DeclarativeAgentWorkflow.run()` — Node für Node ausführen
3. Pro Node: Agent ausführen (LLM-Call), Branches auswerten, Loops handhaben, HITL warten
4. Dependencies: `dependsOn` bestimmt die Reihenfolge

### 5.2 Wie QOC Engine die Ausführung machen soll

QOC Engine hat einen `Coordinator` und `ExecutionRunner`, aber `runPrompt()` ist ein Platzhalter. Die Ausführung muss neu implementiert werden.

**Ansatz: ReactFlow-Graph → QOC Session Engine**

```
ReactFlow JSON (Nodes + Edges)
    ↓
Konvertierung zu v2 (dependsOn aus Edges)
    ↓
DeclarativePlanner (aus agentflow lernen)
    ↓
ExecutionRunner (QOC Coordinator)
    ↓
Pro Node: QocEngine.runPrompt() (LLM-Call, wenn implementiert)
```

### 5.3 Was von agentflow gelernt werden kann

| Komponente | agentflow | QOC (neu) | Aktion |
|------------|-----------|-----------|--------|
| DeclarativePlanner | `DeclarativePlanner.java` (flatten, alias) | Übernehmen | Portieren (pure Java) |
| ConditionEvaluator | `ConditionEvaluator.java` (contains, equals, regex) | Übernehmen | Portieren (pure Java) |
| Node-Runner | `DeclarativeAgentWorkflow.java` (LangGraph4j) | Neu | QOC Coordinator nutzen |
| Agent-Execution | `AgentBuilder` + `Agent.execute()` | QOC `runPrompt()` | LLM in QOC wiren |
| HITL | Temporal Signal `decide` | Bereits in HitlWorkflow | Wiederverwenden |
| Sub-Workflows | Inline flatten | Übernehmen | Portieren |

### 5.4 Was verschieden ist (QOC vs. quad-core)

| Aspekt | quad-core (agentflow) | QOC Engine | Konsequenz |
|--------|----------------------|------------|------------|
| Agent-Builder | `AgentBuilder` mit ModelFactory, Tools, Guardrails, Hooks | `AgentId` + Session (kein Builder) | Agent-Config muss in QOC als Session-Metadaten gespeichert werden |
| LLM-Call | `Agent.execute()` / `executeStructured()` | `QocEngine.runPrompt()` (Platzhalter) | **Muss implementiert werden**: ContextBuilder + ProviderAdapter in QOC |
| Tools | ToolRegistry + SseMcpProxy | ToolRuntime (separat) | ToolRuntime in QOC wiren |
| Memory | PersistentMemoryStore + MemoryCategory | SessionStore + JournalStore | Memory-Adapter nutzen |
| Provenance | ProvenanceTracker | (nicht vorhanden) | Optional: ProvenanceStore in QOC hinzufügen |
| Guardrails | GuardrailPlugin + PiiAnonymizerHook + AuditTrailHook | PermissionEngine | PermissionEngine in QOC nutzen |
| Storage | In-Memory (quad-core) | Memory/SQLite/Postgres per Port | QOC Storage-Backends nutzen |

---

## 6. Storage in Agentflow vs. QOC (In-Memory Mode)

### 6.1 agentflow (quad-core) Storage

agentflow nutzt quad-core, welches **In-Memory** speichert:
- `PersistentMemoryStore` — In-Memory mit optionaler Persistenz (Datei/DB)
- `CheckpointService` — In-Memory oder SQLite (via `CheckpointStore`)
- Session-State — In-Memory

**Keine** konfigurierbaren Storage-Backends pro Port. Global In-Memory oder SQLite.

### 6.2 QOC Storage (per Port)

QOC hat **StorageFactory** + **Stores** + **StorageConfig**:

```java
// Per-Port konfigurierbar
StorageConfig config = new StorageConfig(StorageKind.MEMORY);
// Oder: engine.storage.session-store=sqlite
// Oder: engine.storage.journal-store=postgres
```

**Ports:**
- `SessionStore` — Session-Metadaten
- `JournalStore` — Event-Journal
- `ClaimStore` — Distributed Claims
- `MemoryStore` — Agent-Memory
- `CheckpointStore` — Graph-Checkpoints
- `ProviderStore` — LLM-Provider-Config
- `IntegrationStore` — Integrationen

**Backends:** MEMORY, SQLITE, POSTGRES (per Port konfigurierbar)

### 6.3 Unterschiede

| Aspekt | agentflow (quad-core) | QOC Engine |
|--------|----------------------|------------|
| Storage-Granularität | Global (In-Memory oder SQLite) | Per-Port (Memory/SQLite/Postgres) |
| Konfiguration | `agentflow.checkpoint.path` | `engine.storage.<port>=memory\|sqlite\|postgres` |
| Adapter-Pattern | Keine (direkt In-Memory) | StorageFactory + Stores Record |
| Skalierung | Single-Node | Multi-Node (Postgres) |
| Persistenz | Optional (Datei) | Immer (wenn SQLite/Postgres) |

### 6.4 In-Memory Mode in QOC

Für den Temporal-Modul (In-Memory) wird `StorageKind.MEMORY` genutzt:

```java
Stores stores = StorageFactory.create(StorageKind.MEMORY, memoryBudget);
StorageConfig config = new StorageConfig(StorageKind.MEMORY);
QocEngine engine = QocEngine.create(stores, config, clock, false);
```

**Konsequenz:** Daten sind nicht persistent. Temporal sorgt für Dauerhaftigkeit via Workflow-Replay. Storage-Backends sind für Session-State, nicht für Workflow-State.

---

## 7. Agent-Config: Global vs. in AgentConfig definiert

### 7.1 Wie agentflow Agent-Config handhabt

In agentflow sind Agents in der Workflow-Definition als `agents[]` Array definiert:

```json
{
  "agents": [
    {
      "id": "ex-support-classifier",
      "name": "Support Classifier",
      "model": "deepseek-v4.1-flash",
      "temperature": 0.1,
      "maxTokens": 1024,
      "topP": 1.0,
      "tools": [],
      "guardrailsInput": [],
      "guardrailsOutput": [],
      "hooks": [],
      "jsonOutput": true,
      "active": true,
      "systemPrompt": "You classify customer support tickets..."
    }
  ]
}
```

**Agent-Config ist Teil der Workflow-Definition.** Jeder Workflow hat seine eigenen Agents. Keine globale Agent-Registry.

### 7.2 Wie QOC Agent-Config handhaben soll

In QOC gibt es **keine** Agent-Definitionen. QOC nutzt `AgentId` (UUID) und `SessionInfo`. Agent-Properties (Model, Temperature, etc.) sind nicht Teil der Session.

**Optionen:**

| Option | Beschreibung | Empfehlung |
|--------|-------------|------------|
| **A: Agent-Config in Workflow-Definition** (wie agentflow) | Agents als Teil der Workflow-JSON, referenziert via `agent.ref` | ✅ Empfohlen (kompatibel mit agentflow) |
| **B: Globale Agent-Registry** | Separate `/agents` Endpoint, Workflows referenzieren Agent-IDs | 🟡 Für wiederverwendbare Agents |
| **C: Agent-Config in QOC Session** | Agent-Properties als Session-Metadaten | ❌ Session ist für Runtime-State, nicht Config |

**Empfehlung: Option A** — Agent-Config in der Workflow-Definition (wie agentflow). Das ist kompatibel und bewährt. Optional zusätzlich Option B für eine globale Agent-Registry.

### 7.3 Property Window für Agent-Config

Im ReactFlow-Graph-Editor, wenn ein Agent-Node ausgewählt ist:

```
Property Panel (Side-Panel):
├── Node Properties
│   ├── ID: classify
│   ├── Type: agent
│   ├── Title: Classify ticket
│   ├── Agent-Ref: [dropdown: ex-support-classifier]
│   ├── requiresHumanApproval: [checkbox]
│   └── Dependencies: [checkboxes]
│
└── Agent Config (wenn Agent-Ref gewählt)
    ├── Name: Support Classifier
    ├── Model: [dropdown: deepseek-v4.1-flash, gpt-4o, ...]
    ├── Temperature: [slider: 0.0 - 2.0]
    ├── Max Tokens: [number: 1024]
    ├── Top P: [slider: 0.0 - 1.0]
    ├── System Prompt: [textarea]
    ├── Tools: [multi-select]
    ├── Guardrails Input: [checkboxes: pii, sql-injection, prompt-injection]
    ├── Guardrails Output: [checkboxes: pii, sql-injection, prompt-injection]
    ├── Hooks: [checkboxes: pii-masking, audit]
    └── JSON Output: [checkbox]
```

**Verhalten:**
- Agent-Node auswählen → Property Panel zeigt Node-Properties + Agent-Config
- Agent-Ref ändern → Agent-Config wird aus `agents[]` geladen
- Agent-Config ändern → `agents[]` wird aktualisiert
- "Save" → Workflow-Definition (mit Agents) wird gespeichert

---

## 8. React-Projekt-Plan

### 8.1 Projekt-Struktur

```
qoc/react-ui/                    # Neues React-Projekt
├── package.json
├── vite.config.ts
├── tsconfig.json
├── src/
│   ├── main.tsx
│   ├── App.tsx
│   ├── components/
│   │   ├── GraphEditor.tsx      # ReactFlow-basiert
│   │   ├── PropertyPanel.tsx    # Agent-Config Property Window
│   │   ├── NodeTypes/           # Custom ReactFlow Node Components
│   │   │   ├── AgentNode.tsx
│   │   │   ├── DecisionNode.tsx
│   │   │   ├── LoopNode.tsx
│   │   │   ├── ForkNode.tsx
│   │   │   ├── JoinNode.tsx
│   │   │   ├── HitlNode.tsx
│   │   │   ├── SubworkflowNode.tsx
│   │   │   └── EndNode.tsx
│   │   ├── Sidebar.tsx
│   │   ├── Topbar.tsx
│   │   └── Toast.tsx
│   ├── pages/
│   │   ├── Dashboard.tsx
│   │   ├── Runs.tsx
│   │   ├── RunDetail.tsx
│   │   ├── Workflows.tsx
│   │   ├── WorkflowDetail.tsx
│   │   ├── Generate.tsx
│   │   ├── Agents.tsx
│   │   ├── Testbench.tsx
│   │   ├── Hitl.tsx
│   │   ├── Observe.tsx
│   │   ├── Examples.tsx
│   │   └── Settings.tsx
│   ├── services/
│   │   ├── api.ts               # REST-Client (fetch/axios)
│   │   └── runStream.ts         # SSE-Streaming
│   ├── store/
│   │   └── useWorkflowStore.ts  # Zustand oder Jotai
│   ├── types/
│   │   ├── workflow.ts          # v2 + ReactFlow Types
│   │   └── agent.ts             # Agent-Config Type
│   └── utils/
│       ├── convert.ts           # ReactFlow ↔ v2 Konvertierung
│       └── autoLayout.ts        # elkjs Integration
```

### 8.2 Dependencies

```json
{
  "dependencies": {
    "react": "^19.0.0",
    "react-dom": "^19.0.0",
    "react-router-dom": "^7.0.0",
    "@xyflow/react": "^12.0.0",
    "elkjs": "^0.9.0",
    "zustand": "^5.0.0",
    "axios": "^1.7.0",
    "react-hook-form": "^7.50.0",
    "zod": "^3.23.0",
    "tailwindcss": "^4.0.0",
    "@radix-ui/react-dialog": "^1.1.0",
    "@radix-ui/react-select": "^2.1.0",
    "lucide-react": "^0.400.0"
  }
}
```

### 8.3 Property Window Design

```
┌─────────────────────────────────────────────────────────────┐
│  Graph Editor (ReactFlow Canvas)          │  Property Panel  │
│                                           │                  │
│  ┌──────┐   ┌──────┐   ┌──────┐         │  Node: classify  │
│  │classify│──▶│playbook│──▶│respond│         │  Type: agent     │
│  └──────┘   └──────┘   └──────┘         │  Title: Classify │
│                                           │  Agent: [dropdown]│
│  ┌──────┐                                 │                  │
│  │  end  │                                 │  ┌─ Agent Config ─┐│
│  └──────┘                                 │  │ Model: deepseek ││
│                                           │  │ Temp: 0.1      ││
│                                           │  │ MaxTokens: 1024 ││
│                                           │  │ SystemPrompt:   ││
│                                           │  │ [textarea]      ││
│                                           │  │ Tools: [list]   ││
│                                           │  │ Guardrails:     ││
│                                           │  │ [checkboxes]    ││
│                                           │  └────────────────┘│
│                                           │                  │
│                                           │  [Save] [Reset]  │
└─────────────────────────────────────────────────────────────┘
```

---

## 9. Implementierungs-Reihenfolge

### Phase 1: React-Projekt aufsetzen
1. Vite + React 19 + TypeScript
2. ReactFlow (@xyflow/react) installieren
3. Tailwind CSS konfigurieren
4. Basis-Routing (react-router-dom)
5. API-Client (axios) mit Proxy auf Temporal-Modul

### Phase 2: Graph-Editor mit ReactFlow
1. ReactFlow Canvas mit Custom Node Types
2. Auto-Layout (elkjs)
3. Drag & Drop, Click-to-Connect
4. Node-CRUD (Add, Delete, Rename)
5. Property Panel (Side-Panel) für Node-Properties
6. **Property Window für Agent-Config** (Model, Temperature, SystemPrompt, Tools, Guardrails)

### Phase 3: Konvertierung ReactFlow ↔ v2
1. `convert.ts`: ReactFlow → v2 (Edges → dependsOn)
2. `convert.ts`: v2 → ReactFlow (dependsOn → Edges)
3. Position-Persistenz (localStorage + Backend)

### Phase 4: Backend-Erweiterung (Temporal-Modul)
1. `POST /workflows/run` — ReactFlow-JSON akzeptieren
2. `DeclarativePlanner` portieren (pure Java)
3. `ConditionEvaluator` portieren (pure Java)
4. `QocEngine.runPrompt()` implementieren (LLM-Call)
5. Node-Runner mit QOC Coordinator

### Phase 5: Vollständige UI
1. Alle 13 Seiten (Dashboard, Runs, Workflows, etc.)
2. SSE-Streaming (RunStreamService)
3. Settings, Observability, Testbench
4. Examples (5 Business-Cases)

---

## 10. Was von agentflow gelernt werden kann (konkret)

| agentflow-Datei | Was lernen | Portieren nach |
|-----------------|-----------|----------------|
| `DeclarativePlanner.java` | Flatten von Sub-Workflows, Alias zu `end` | `temporal/.../planner/DeclarativePlanner.java` |
| `ConditionEvaluator.java` | Condition-Auswertung (contains, equals, regex) | `temporal/.../evaluator/ConditionEvaluator.java` |
| `DeclarativeAgentWorkflow.java` | Node-Interpretation (agent/decision/loop/fork/join/hitl/end) | `temporal/.../workflow/DeclarativeWorkflow.java` |
| `graph-model.ts` (Angular) | Node-VM-Mapping, Auto-Layout | `react-ui/src/utils/convert.ts` |
| `workflow-detail.ts` (Angular) | Graph-Editor-Integration, Save/Validate/Version | `react-ui/src/pages/WorkflowDetail.tsx` |
| `agents.ts` (Angular) | Agent-Editor-Formular | `react-ui/src/components/PropertyPanel.tsx` |
| `models.ts` (Angular) | Type-Definitionen | `react-ui/src/types/workflow.ts` |

---

## 11. Offene Fragen (muss noch geprüft werden)

1. **QOC Engine LLM-Wiring:** Wann wird `runPrompt()` implementiert? (ContextBuilder + ProviderAdapter in QOC Engine)
2. **Agent-Config in QOC:** Wo werden Agent-Properties (Model, Temperature, SystemPrompt) gespeichert? In der Workflow-Definition (wie agentflow) oder in einem separaten Store?
3. **Tool-Integration:** Wie werden Tools (MCP, built-in) in QOC Engine eingebunden? ToolRuntime existiert, aber ist nicht mit LLM-Call verkoppelt.
4. **Guardrails:** Wie werden Guardrails (PII, SQL-Injection, etc.) in QOC Engine implementiert? PermissionEngine existiert, aber ist nicht mit Agent-Execution verkoppelt.
5. **Provenance:** Braucht QOC Engine einen ProvenanceTracker? (Optional für v1, aber für Observability wichtig)
6. **Storage in In-Memory Mode:** Temporal sorgt für Dauerhaftigkeit via Workflow-Replay. Aber Session-State (Memory, Checkpoints) ist in QOC in-memory und geht bei Neustart verloren. Ist das akzeptabel für v1?
7. **ReactFlow ↔ v2 Konvertierung:** Soll das Backend ReactFlow-Format direkt akzeptieren, oder intern zu v2 konvertieren? (Empfehlung: Backend akzeptiert ReactFlow-Format, konvertiert intern)
8. **Property Window Scope:** Soll das Property Window nur Agent-Config zeigen, oder auch Node-Properties (Dependencies, Branches, etc.)? (Empfehlung: Beides, getrennt in Tabs/Accordion)

---

## 12. Referenzen

- agentflow UI-Design: `/home/torsten/dev/my-projects/agentflow-standalone/UI.md`
- agentflow Workflow-v2: `/home/torsten/dev/my-projects/agentflow-standalone/agentflow-app/src/main/resources/workflows/support-triage.json`
- QOC Engine: `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/QocEngine.java`
- Temporal-Modul: `qoc/temporal/src/main/java/de/augmentia/qoc/temporal/TemporalServer.java`
- Temporal-Engine.md: `qoc/docs/engineering/Temporal-Engine.md` (§9: AgentFlow-Analyse, Ports)
- Multi-Memory.md: `qoc/docs/engineering/Multi-Memory.md`
- ReactFlow: https://reactflow.dev/
- elkjs: https://github.com/kieler/elkjs
- @xyflow/react: https://www.npmjs.com/package/@xyflow/react
