# Structured Output (JSON Schema) for Agents and Workflows

## Overview

Agents and workflow nodes can define a `responseSchema` (JSON Schema).
The LLM is then forced via the synthetic `submit_result` tool to produce a
structured result. The result ends up in
`EngineResult.structured()` / `NodeResult.structured` and is converted
into a `Map<String,Object>` via `StructuredOutputConverter.toMap(...)`.

## HTTP Path (Quarkus)

```http
POST /api/session/{id}/prompt
Content-Type: application/json

{ "text": "…", "responseSchema": { "type": "object", "properties": { "answer": { "type": "string" } }, "required": ["answer"] } }
```

Server-side: `JsonSchema.parse(JsonValues.fromJava(request.responseSchema()))`.

## Workflow Path

- `WorkflowDefinition.Agent.responseSchema: Map<String,Object>` (JSON Schema)
- `NodeData.responseSchema` (override per node)
- `DefaultNodeDispatcher` parses the schema and passes it to `QocEngine.runPrompt(..., Option<Schema>)`.
- `EngineResult.structured()` is populated from the `submit_result` tool call.

## Converter

- `JavaRecordToJsonSchema.fromClass(Class<?>)` – reflection over Records/POJOs.
- `OpenApiToJsonSchema.fromSpec(spec, schemaName)` – OpenAPI 3.x → JSON Schema.
- Endpoints: `POST /api/schema/from-class`, `/from-openapi`, `/validate`.

## UI

- `Agents.tsx`: Textarea "Response Schema" + import buttons.
- `PropertyPanel.tsx`: toggle + schema textarea.
- `GraphEditor.tsx`: badge on agent nodes with schema.
- `Testbench.tsx`: uses the HTTP path (usable immediately).
- `Runs.tsx` / `Observe.tsx`: structured result as a JSON tree.

## Validation

Client: `JSON.parse`. Server: `JsonSchema.parse(...)` → `Result.Err` on an invalid schema.
