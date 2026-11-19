# Implementation Plan: Workflow Generation from Prompt (MVP)

Status: Plan (2026-11-14)
Reference: `workflow-runner/`, `workflow-ui/src/pages/Generate.tsx`, `quad/.../generate/WorkflowPlanParser.java`
Goal: Generate a valid `WorkflowDefinition` (Nodes/Edges/Agents) from a free-text prompt, instead of just creating an empty workflow.

## 1. Current State

- `Generate.tsx` calls `WorkflowApi.create(prompt.substring(0,40))` → `POST /workflows` with `{name}`.
- Backend creates an empty `WorkflowDefinition` with that name. The prompt is not evaluated.
- No LLM integration, no generation, no validation of the generated output.

## 2. Target State (MVP)

```
User-Prompt → LLM (structured output) → stripFences → parseSteps → validateSteps → WorkflowBuilder → WorkflowDefinition → store.save → UI shows Editor
```

## 3. New Package (separated, no reverse dependency)

```
workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/generate/
├── StripFences.java              # Utility: remove Markdown fences from LLM reply
├── WorkflowPlanParser.java        # ported from quad: parseSteps + validateSteps + GeneratedStep
├── WorkflowBuilder.java          # GeneratedStep → WorkflowDefinition (Nodes/Edges/Agents)
├── WorkflowGenerator.java        # Orchestrator: Prompt → LLM → parse → build → save
└── GenerateConfig.java           # Opt-in flag + LLM settings

workflow-runner/src/test/java/de/augmentia/qoc/workflowrunner/generate/
├── StripFencesTest.java
├── WorkflowPlanParserTest.java
└── WorkflowBuilderTest.java
```

Dependencies: `generate` → `model`, `store`, `validation` (GraphCompiler). No reverse dependency.

## 4. Components in Detail

### 4.1 `StripFences` (ported from quad, ~15 lines)

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

### 4.2 `WorkflowPlanParser` (ported from quad, ~120 lines)

- `GeneratedStep` record: `agentRole, instruction, tools, dependsOn, timeoutSeconds, systemPrompt, data`.
- `WorkbookResult` record: `steps, workflowName, description, raw`.
- `parseSteps(mapper, json)`: JSON → Steps + validation (deps exist, no cycles, no duplicates).
- `validateSteps(steps, byRole)`: cycle check (DFS).
- `stripFences` is called beforehand (or separately).

### 4.3 `WorkflowBuilder` (QOC-specific, ~100 lines)

```java
public final class WorkflowBuilder {
    public static WorkflowDefinition build(WorkbookResult result) {
        // 1. Derive agents from GeneratedSteps (agentRole → Agent)
        // 2. Build nodes from steps (each step → Node with NodeData)
        // 3. Build edges from dependsOn
        // 4. WorkflowDefinition with id, name, version, status, nodes, edges, agents
    }
}
```

Mapping:
- `GeneratedStep.agentRole` → `WorkflowDefinition.Agent` (with id = agentRole, name = agentRole).
- `GeneratedStep` → `WorkflowDefinition.Node` (type "agent", data with agent = agentRole, instruction, tools, timeout).
- `GeneratedStep.dependsOn` → `WorkflowDefinition.Edge` (source = dep, target = agentRole).

### 4.4 `WorkflowGenerator` (Orchestrator, ~80 lines)

```java
public class WorkflowGenerator {
    private final LlmClient llm;       // LangChain4j or HTTP
    private final WorkflowStore store;
    private final ObjectMapper mapper;

    public WorkflowDefinition generate(String prompt) {
        String raw = llm.generate(prompt, GENERATE_SYSTEM_PROMPT);
        String json = StripFences.strip(raw);
        WorkbookResult result = WorkflowPlanParser.parseSteps(mapper, json);
        WorkflowDefinition def = WorkflowBuilder.build(result);
        // Validation
        GraphCompiler.validate(def); // or validateTemplatesOnly
        return store.save(def.id() == null ? generateId() : def.id(), def);
    }
}
```

System prompt (template):
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
- `ENGINE_WORKFLOW_GENERATE_MODEL` (Default: from EngineConfig.model()).
- `ENGINE_WORKFLOW_GENERATE_MAX_STEPS` (Default: 10).

### 4.6 Endpoint

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

### 4.7 UI Adjustment (`Generate.tsx`)

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

## 5. Implementation Order

1. **Step 1 (Day 1):** Port `StripFences` + `WorkflowPlanParser` + tests.
2. **Step 2 (Day 1-2):** Implement `WorkflowBuilder` + tests (mapping GeneratedStep → WorkflowDefinition).
3. **Step 3 (Day 2):** `GenerateConfig` + opt-in flag.
4. **Step 4 (Day 2-3):** `WorkflowGenerator` (LLM integration) + `POST /workflows/generate` endpoint.
5. **Step 5 (Day 3):** Adjust `Generate.tsx` + UI tests (manual).
6. **Step 6 (Day 3):** Integration test (Prompt → generate → store → load → validate).

## 6. Risks / Open Questions

- **LLM quality:** Generated workflows may be invalid. GraphCompiler validation + playing back errors (Part A of the Deterministic-Loop plan) as a retry.
- **Agent resolution:** Generated `agentRole` values must correspond to existing agents or are created as new agents (Default: only reference, do not create).
- **Token costs:** LLM call per generation; large output for complex prompts.
- **Conflict with existing `POST /workflows`:** The new endpoint `/workflows/generate` is separate; no breaking change.

## 7. Dependencies

- `workflow-runner` already has: Jackson (WorkflowJson), WorkflowStore, GraphCompiler, WorkflowDefinition.
- LLM access: workflow-runner uses no LLM directly. Options:
  - (a) HTTP call to Engine API (`POST /api/run` with Generate-Agent).
  - (b) Add LangChain4j as a dependency in workflow-runner.
  - Recommendation: (a) – uses existing Engine infrastructure, no new dependency.

## 8. Tests

- `StripFencesTest`: fences, bare json, prose, null.
- `WorkflowPlanParserTest`: valid JSON, missing steps, duplicates, cycles, unknown deps.
- `WorkflowBuilderTest`: mapping, empty steps, multiple agents.
- Integration: `POST /workflows/generate` with mock LLM → 201 + valid WorkflowDefinition.