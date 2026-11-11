# Structured Output (JSON Schema) für Agents und Workflows

## Übersicht

Agents und Workflow-Nodes können ein `responseSchema` (JSON Schema) definieren.
Das LLM wird dann über das synthetische Tool `submit_result` gezwungen, ein
strukturiertes Ergebnis zu liefern. Das Ergebnis landet in
`EngineResult.structured()` / `NodeResult.structured` und wird über
`StructuredOutputConverter.toMap(...)` in eine `Map<String,Object>` umgewandelt.

## HTTP-Pfad (Quarkus)

```http
POST /api/session/{id}/prompt
Content-Type: application/json

{ "text": "…", "responseSchema": { "type": "object", "properties": { "answer": { "type": "string" } }, "required": ["answer"] } }
```

Server-seitig: `JsonSchema.parse(JsonValues.fromJava(request.responseSchema()))`.

## Workflow-Pfad

- `WorkflowDefinition.Agent.responseSchema: Map<String,Object>` (JSON Schema)
- `NodeData.responseSchema` (Override pro Node)
- `DefaultNodeDispatcher` parst das Schema und übergibt es an `QocEngine.runPrompt(..., Option<Schema>)`.
- `EngineResult.structured()` wird aus dem `submit_result`-Tool-Call befüllt.

## Converter

- `JavaRecordToJsonSchema.fromClass(Class<?>)` – Reflection über Records/POJOs.
- `OpenApiToJsonSchema.fromSpec(spec, schemaName)` – OpenAPI 3.x → JSON Schema.
- Endpoints: `POST /api/schema/from-class`, `/from-openapi`, `/validate`.

## UI

- `Agents.tsx`: Textarea „Response Schema“ + Import-Buttons.
- `PropertyPanel.tsx`: Toggle + Schema-Textarea.
- `GraphEditor.tsx`: Badge an Agent-Nodes mit Schema.
- `Testbench.tsx`: nutzt HTTP-Pfad (sofort nutzbar).
- `Runs.tsx` / `Observe.tsx`: strukturiertes Ergebnis als JSON-Baum.

## Validierung

Client: `JSON.parse`. Server: `JsonSchema.parse(...)` → `Result.Err` bei ungültigem Schema.
