# Implementierungsplan: Workflow-Generierung aus Prompt (MVP)

Status: Plan (2026-11-14)
Bezug: `workflow-runner/`, `workflow-ui/src/pages/Generate.tsx`, `quad/.../generate/WorkflowPlanParser.java`
Ziel: Aus einem Freitext-Prompt einen validen `WorkflowDefinition` (Nodes/Edges/Agents) generieren, statt nur einen leeren Workflow anzulegen.

## 1. Ist-Zustand

- `Generate.tsx` ruft `WorkflowApi.create(prompt.substring(0,40))` → `POST /workflows` mit `{name}`.
- Backend erstellt leeren `WorkflowDefinition` mit dem Namen. Prompt wird nicht ausgewertet.
- Keine LLM-Integration, keine Generierung, keine Validierung des generierten Outputs.

## 2. Zielbild (MVP)

```
User-Prompt → LLM (structured output) → stripFences → parseSteps → validateSteps → WorkflowBuilder → WorkflowDefinition → store.save → UI zeigt Editor
```

## 3. Neues Package (separiert, keine Rückwärts-Abhängigkeit)

```
workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/generate/
├── StripFences.java              # Utility: Markdown-Fences aus LLM-Reply entfernen
├── WorkflowPlanParser.java        # portiert aus quad: parseSteps + validateSteps + GeneratedStep
├── WorkflowBuilder.java          # GeneratedStep → WorkflowDefinition (Nodes/Edges/Agents)
├── WorkflowGenerator.java        # Orchestrator: Prompt → LLM → parse → build → save
└── GenerateConfig.java           # Opt-in Flag + LLM-Settings

workflow-runner/src/test/java/de/augmentia/qoc/workflowrunner/generate/
├── StripFencesTest.java
├── WorkflowPlanParserTest.java
└── WorkflowBuilderTest.java
```

Abhängigkeiten: `generate` → `model`, `store`, `validation` (GraphCompiler). Keine Rückwärts-Abhängigkeit.

## 4. Komponenten im Detail

### 4.1 `StripFences` (portiert aus quad, ~15 Zeilen)

```java
public final class StripFences {
    public static String strip(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        t = t.replaceFirst("(?s)^```(?:json)?\\s*", "");
        t = t.replaceFirst("(?s)\\s*```\\s*$", "");
        t = t.replaceFirst("(?s)^json\\s*(?=\\{)", "");
        int firstBrace = t.indexOf('{');
        int lastBrace = t.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            t = t.substring(firstBrace, lastBrace + 1);
        }
        return t;
    }
}
```

### 4.2 `WorkflowPlanParser` (portiert aus quad, ~120 Zeilen)

- `GeneratedStep` record: `agentRole, instruction, tools, dependsOn, timeoutSeconds, systemPrompt, data`.
- `WorkbookResult` record: `steps, workflowName, description, raw`.
- `parseSteps(mapper, json)`: JSON → Steps + Validierung (Deps existieren, keine Zyklen, keine Duplikate).
- `validateSteps(steps, byRole)`: Zyklenprüfung (DFS).
- `stripFences` wird davor aufgerufen (oder separat).

### 4.3 `WorkflowBuilder` (QOC-spezifisch, ~100 Zeilen)

```java
public final class WorkflowBuilder {
    public static WorkflowDefinition build(WorkbookResult result) {
        // 1. Agents aus GeneratedSteps ableiten (agentRole → Agent)
        // 2. Nodes aus Steps bauen (jeder Step → Node mit NodeData)
        // 3. Edges aus dependsOn bauen
        // 4. WorkflowDefinition mit id, name, version, status, nodes, edges, agents
    }
}
```

Mapping:
- `GeneratedStep.agentRole` → `WorkflowDefinition.Agent` (mit id = agentRole, name = agentRole).
- `GeneratedStep` → `WorkflowDefinition.Node` (type "agent", data mit agent = agentRole, instruction, tools, timeout).
- `GeneratedStep.dependsOn` → `WorkflowDefinition.Edge` (source = dep, target = agentRole).

### 4.4 `WorkflowGenerator` (Orchestrator, ~80 Zeilen)

```java
public class WorkflowGenerator {
    private final LlmClient llm;       // LangChain4j oder HTTP
    private final WorkflowStore store;
    private final ObjectMapper mapper;

    public WorkflowDefinition generate(String prompt) {
        String raw = llm.generate(prompt, GENERATE_SYSTEM_PROMPT);
        String json = StripFences.strip(raw);
        WorkbookResult result = WorkflowPlanParser.parseSteps(mapper, json);
        WorkflowDefinition def = WorkflowBuilder.build(result);
        // Validierung
        GraphCompiler.validate(def); // oder validateTemplatesOnly
        return store.save(def.id() == null ? generateId() : def.id(), def);
    }
}
```

System-Prompt (Template):
```
Du bist ein Workflow-Architekt. Erstelle einen Workflow als JSON mit folgendem Schema:
{
  "workflowName": "...",
  "description": "...",
  "steps": [
    {
      "agentRole": "analyzer",
      "instruction": "...",
      "tools": ["read", "grep"],
      "dependsOn": [],
      "timeoutSeconds": 120,
      "systemPrompt": "..."
    }
  ]
}
Antworte NUR mit JSON.
```

### 4.5 `GenerateConfig` (Opt-in)

- `ENGINE_WORKFLOW_GENERATE_ENABLED` (Default: `false`).
- `ENGINE_WORKFLOW_GENERATE_MODEL` (Default: aus EngineConfig.model()).
- `ENGINE_WORKFLOW_GENERATE_MAX_STEPS` (Default: 10).

### 4.6 Endpunkt

`POST /workflows/generate` in `WorkflowRunnerMain.java`:
```java
} else if ("POST".equals(method) && "/workflows/generate".equals(path)) {
    Map<String, Object> body = parseBody(ex);
    String prompt = (String) body.get("prompt");
    if (prompt == null || prompt.isBlank()) { sendJson(ex, 400, ...); return; }
    if (!generateConfig.enabled()) { sendJson(ex, 403, ...); return; }
    try {
        WorkflowDefinition def = generator.generate(prompt);
        sendJson(ex, 201, def);
    } catch (IllegalArgumentException e) {
        sendJson(ex, 422, Map.of("error", e.getMessage()));
    }
}
```

### 4.7 UI-Anpassung (`Generate.tsx`)

```tsx
const generate = async () => {
    if (!prompt.trim()) return;
    setGenerating(true);
    setResult(null);
    try {
        const res = await fetch('/workflows/generate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ prompt: prompt.trim() })
        });
        if (!res.ok) throw new Error(await res.text());
        const data = await res.json();
        setResult(`Workflow erstellt: ${data.id}`);
        // Optional: navigate to editor
    } catch (e) {
        console.error(e);
        setResult('Generierung fehlgeschlagen: ' + (e as Error).message);
    } finally {
        setGenerating(false);
    }
};
```

## 5. Reihenfolge der Umsetzung

1. **Schritt 1 (Tag 1):** `StripFences` + `WorkflowPlanParser` portieren + Tests.
2. **Schritt 2 (Tag 1-2):** `WorkflowBuilder` implementieren + Tests (Mapping GeneratedStep → WorkflowDefinition).
3. **Schritt 3 (Tag 2):** `GenerateConfig` + Opt-in Flag.
4. **Schritt 4 (Tag 2-3):** `WorkflowGenerator` (LLM-Integration) + `POST /workflows/generate` Endpunkt.
5. **Schritt 5 (Tag 3):** `Generate.tsx` anpassen + UI-Tests (manuell).
6. **Schritt 6 (Tag 3):** Integration-Test (Prompt → generate → store → load → validate).

## 6. Risiken / zu klären

- **LLM-Qualität:** Generierte Workflows können ungültig sein. GraphCompiler-Validierung + Fehler zurückspielen (Teil A des Deterministic-Loop-Plans) als Retry.
- **Agenten-Auflösung:** Generierte `agentRole`-Werte müssen existierenden Agenten entsprechen oder werden als neue Agenten angelegt (Default: nur referenzieren, nicht anlegen).
- **Token-Kosten:** LLM-Call pro Generierung; bei komplexen Prompts großer Output.
- **Konflikt mit bestehendem `POST /workflows`:** Der neue Endpunkt `/workflows/generate` ist separat; kein Breaking Change.

## 7. Abhängigkeiten

- `workflow-runner` hat bereits: Jackson (WorkflowJson), WorkflowStore, GraphCompiler, WorkflowDefinition.
- LLM-Zugriff: workflow-runner nutzt keinen LLM direkt. Optionen:
  - (a) HTTP-Call an Engine-API (`POST /api/run` mit Generate-Agent).
  - (b) LangChain4j als Dependency in workflow-runner hinzufügen.
  - Empfehlung: (a) – nutzt bestehende Engine-Infrastruktur, kein neues Dependency.

## 8. Tests

- `StripFencesTest`: Fences, bare json, Prosa, null.
- `WorkflowPlanParserTest`: gültiges JSON, fehlende steps, Duplikate, Zyklen, unbekannte Deps.
- `WorkflowBuilderTest`: Mapping, leere Steps, mehrere Agents.
- Integration: `POST /workflows/generate` mit Mock-LLM → 201 + valides WorkflowDefinition.
