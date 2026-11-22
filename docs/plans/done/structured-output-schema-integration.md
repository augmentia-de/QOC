# Implementation Plan: Java Records and YAML in Structured-Output Configuration

**Status:** Implemented (2026-11-22) — Java source parsing (JavaSourceToJsonSchema), YAML parsing (YamlToJsonSchema), SchemaResource endpoints (from-java, from-yaml, from-class, from-openapi, validate), 50 KB size limit, reactflow UI with responseSchema editing, tests present.
**Reference:** P0/P1/P2 completed (Commit `a89ea1a`), Structured-Output infrastructure present (`SchemaResource`, `JavaRecordToJsonSchema`, `OpenApiToJsonSchema`)

---

## 1. Goal

Structured output (Response Schema) should no longer be configurable only as a hand-written JSON schema; instead it should be generatable from **Java source code** (Records/POJOs/DTOs) and **YAML files** (OpenAPI or plain JSON Schema) — at the **agent level** and at the **node level** (e.g. `llm_eval`).

The integration must work in **JVM and native mode**.

---

## 2. Existing Infrastructure

### Backend
| File | Role |
|---|---|
| `workflow-runner/schema/JavaRecordToJsonSchema.java` | Reflection: Java Record/POJO → JSON Schema (Map) |
| `workflow-runner/schema/OpenApiToJsonSchema.java` | OpenAPI 3.x (YAML/JSON) → JSON Schema |
| `quarkus/http/SchemaResource.java` | JAX-RS `/api/schema`: `from-class`, `from-openapi`, `validate` |
| `WorkflowReflectionConfig.java` | Native-reflection registration for schema classes |
| `QocEngine.AgentConfig.responseSchema` | JSON Schema for LLM structured output |
| `Agent.responseSchema`, `NodeData.responseSchema` | Persisted schemas |
| `StructuredOutputConverter` | LLM `structured()` → `Map` for workflow context |

### Frontend
| File | Role |
|---|---|
| `types/workflow.ts` | `Agent.responseSchema`, `NodeData.responseSchema` |
| `pages/Agents.tsx` | Textarea for JSON Schema (manual) |
| `components/PropertyPanel.tsx` | Agent Config: Response-Schema textarea |
| `services/api.ts` | **No** `SchemaApi` |

### Tests
- `JavaRecordToJsonSchemaTest`, `SchemaParseCompatTest` secure Record→Schema→Parse.

---

## 3. Integration Paths (decided)

### 3.1 Java Records → source-code upload only

Source code is parsed; **no** reflection registration is needed for user classes → native-compatible.

- **Backend:** `POST /api/schema/from-java` with body `{ source: string }`.
  - Parses `.java` source code with `JavaParser` (`com.github.javaparser:javaparser-core:3.28.0`).
  - Extracts Record components and class fields from the AST.
  - Converts to JSON Schema (type mapping analogous to `JavaRecordToJsonSchema`).
  - Returns `{ schema, className }`.
  - Size limit: **50 KB**. No code execution.
- **Frontend:** File upload (`<input type="file" accept=".java">`) or textarea; "Generate schema" calls `/from-java` and fills the schema textarea.

### 3.2 YAML files → both modes (auto-detect)

- **OpenAPI YAML:** Already possible via `/from-openapi` (Swagger parser reads YAML). Frontend sends file content.
- **Plain JSON Schema in YAML:** New endpoint `POST /api/schema/from-yaml` `{ yaml }`.
  - Parses YAML with Jackson-YAML → Map.
  - Auto-detect: if an `openapi` key is present → `OpenApiToJsonSchema`; otherwise validate as JSON Schema and return it.
- **Frontend:** File upload/textarea + mode selection (OpenAPI vs. JSON Schema).

### 3.3 Structured output on both levels

- **Agent level:** Schema is persisted in the Agent store (existing).
- **Node level:** `NodeData.responseSchema` is already in the model. Expose it in the PropertyPanel for `llm_eval` (and optionally `agent` nodes as an override).
- Backend dispatcher `llm_eval` then uses `node.data().responseSchema()` instead of the hard-coded `true`.

---

## 4. Backend Changes

### 4.1 Dependencies

**`workflow-runner/pom.xml`**
```xml
<dependency>
    <groupId>com.github.javaparser</groupId>
    <artifactId>javaparser-core</artifactId>
    <version>3.28.0</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
    <version>${jackson.version}</version>
</dependency>
```

**`quarkus/pom.xml`**
```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

### 4.2 New Files

**`workflow-runner/schema/JavaSourceToJsonSchema.java`**
- Parses Java source code with `JavaParser`.
- Extracts `RecordDeclaration` components and `ClassDeclaration` fields (only public/non-static).
- Type mapping analogous to `JavaRecordToJsonSchema` (String, int, Optional, Collections, nested types, Enums).
- Supports `@JsonProperty`, `@Schema(description)`.
- Returns `Map<String, Object>` (JSON Schema).

**`workflow-runner/schema/YamlToJsonSchema.java` (optional)**
- Wrapper: parses YAML string with Jackson-YAML → Map.
- If `openapi` key present → delegates to `OpenApiToJsonSchema`.
- Otherwise validates the Map as JSON Schema and returns it.

### 4.3 Extend `SchemaResource.java`

```java
@POST
@Path("/from-java")
public Response fromJava(Map<String, Object> request) {
    String source = request == null ? null : String.valueOf(request.get("source"));
    if (source == null || source.isBlank()) {
        return badRequest("source required");
    }
    if (source.length() > 50_000) {
        return badRequest("source too large (max 50 KB)");
    }
    try {
        Map<String, Object> schema = JavaSourceToJsonSchema.fromSource(source);
        String className = JavaSourceToJsonSchema.extractClassName(source);
        return Response.ok(Map.of("schema", schema, "className", className)).build();
    } catch (Exception e) {
        return badRequest(e.getMessage());
    }
}

@POST
@Path("/from-yaml")
public Response fromYaml(Map<String, Object> request) {
    String yaml = request == null ? null : String.valueOf(request.get("yaml"));
    if (yaml == null || yaml.isBlank()) {
        return badRequest("yaml required");
    }
    if (yaml.length() > 50_000) {
        return badRequest("yaml too large (max 50 KB)");
    }
    try {
        Map<String, Object> schema = YamlToJsonSchema.fromYaml(yaml);
        return Response.ok(Map.of("schema", schema)).build();
    } catch (Exception e) {
        return badRequest(e.getMessage());
    }
}
```

### 4.4 `DefaultNodeDispatcher.java` — `llm_eval`

```java
case "llm_eval" -> {
    // ... existing code ...
    boolean structuredOutput = node.data().jsonOutput() == null || node.data().jsonOutput();
    Map<String, Object> responseSchema = node.data().responseSchema();
    Option<Schema> schemaOption = responseSchema == null
            ? Option.none()
            : Option.some(JsonSchema.parse(JsonValues.fromJava(responseSchema)).orElseThrow());
    QocEngine.AgentConfig config = new QocEngine.AgentConfig(
            prefixedName, null, 0.3, 4096, 1.0,
            "You are an evaluation assistant. Respond with valid JSON.",
            List.of(), List.of(), List.of(), List.of(), structuredOutput,
            schemaOption);
    return runAgent(node, prefixedName, prompt, config, ctx);
}
```

### 4.5 `WorkflowReflectionConfig.java`

Register JavaParser classes for native reflection:
```java
@RegisterForReflection(classNames = {
    "com.github.javaparser.ast.CompilationUnit",
    "com.github.javaparser.ast.body.RecordDeclaration",
    "com.github.javaparser.ast.body.ClassOrInterfaceDeclaration",
    "com.github.javaparser.ast.body.FieldDeclaration",
    "com.github.javaparser.ast.type.Type",
    // ... further required classes
})
```

### 4.6 Security

- Limit source/YAML size to 50 KB.
- JavaParser does not execute code → no execution risk.
- YAML parsing: Jackson-YAML is safe (no code execution under the standard configuration).
- The class-name endpoint (`/from-class`) is **not** used in the MVP; source-code parsing is used instead.

---

## 5. Frontend Changes

### 5.1 `api.ts` — `SchemaApi`

```typescript
export const SchemaApi = {
  fromJava: (source: string) =>
    api.post<{ schema: Record<string, unknown>; className: string }>('/schema/from-java', { source }),
  fromOpenApi: (spec: string, schemaName?: string) =>
    api.post<{ schema?: Record<string, unknown>; schemas?: string[] }>('/schema/from-openapi', { spec, schemaName }),
  fromYaml: (yaml: string) =>
    api.post<{ schema: Record<string, unknown> }>('/schema/from-yaml', { yaml }),
  validate: (schema: unknown) =>
    api.post<{ valid: boolean; path?: string; message?: string; error?: string }>('/schema/validate', { schema }),
};
```

### 5.2 New Component `SchemaSource.tsx`

Reusable UI for schema import:

```tsx
interface SchemaSourceProps {
  value: Record<string, unknown> | null;
  onChange: (schema: Record<string, unknown> | null) => void;
  disabled?: boolean;
}

// Modes: 'manual' | 'java' | 'openapi' | 'yaml'
// - manual: Textarea (existing)
// - java: File upload (.java) or textarea + "Generate schema"
// - openapi: Textarea/upload + "Load schemas" → dropdown + "Apply"
// - yaml: Textarea/upload → "Parse schema"
// Validation: "Validate schema" button + error display
```

### 5.3 Integration in `Agents.tsx`

- Replace the response-schema textarea with `SchemaSource`.
- Mode selection via dropdown.
- File upload via `<input type="file" accept=".java,.yaml,.yml,.json">`.
- `FileReader.readAsText()` → pass content to the corresponding endpoint.

### 5.4 Integration in `PropertyPanel.tsx` (Agent Config)

- Replace the existing response-schema textarea with `SchemaSource`.
- Same functionality as in `Agents.tsx`.

### 5.5 Node-level `responseSchema` for `llm_eval`

- PropertyPanel: Collapsible "Output Schema" for `llm_eval` nodes.
- Textarea (JSON) or `SchemaSource` component.
- `updateField('responseSchema', schema)`.

---

## 6. Phases

| Phase | Content | Effort |
|---|---|---|
| **SS1** | Backend `/from-yaml` + Jackson-YAML dependency | 0.5 day |
| **SS2** | Backend `/from-java` with JavaParser (source → schema) | 1 day |
| **SS3** | Frontend `SchemaApi` + `SchemaSource` component | 1 day |
| **SS4** | Integration in `Agents.tsx` and `PropertyPanel.tsx` | 0.5 day |
| **SS5** | Node-level `responseSchema` for `llm_eval` (UI + dispatcher) | 0.5–1 day |
| **SS6** | Tests + native verification | 0.5–1 day |

---

## 7. Tests

- **Backend:** Unit tests for `JavaSourceToJsonSchema` (record, POJO, nested, Optional, Enum).
- **Backend:** Unit test for `from-yaml` (OpenAPI YAML + plain JSON Schema YAML).
- **Backend:** Integration test for `/from-java` and `/from-yaml` in `SchemaResource` (or extend the existing test class).
- **Frontend:** Manual verification (file upload, schema generation, validation, save/reload).
- **Native:** Build with `./deploy/build-native-image.sh` and smoke-test the endpoints.

---

## 8. Open Questions (answered)

1. **Java Records:** Source-code upload only (A2). ✅
2. **YAML:** Both modes (OpenAPI + plain JSON Schema), auto-detect. ✅
3. **Node level:** Generally usable for future node definitions (`llm_eval` as first candidate). ✅
4. **Native registration:** No longer needed with source-code parsing. ✅
5. **Size limit:** 50 KB. ✅

---

## 9. Recommendation

- **MVP (SS1–SS4):** Java source-code upload + OpenAPI/YAML import in agent configuration.
- **Native:** Source-code parsing needs no reflection registration for user classes; only JavaParser/YAML parser must run natively (Quarkus dependency handling).
- **Node level (SS5):** Only for `llm_eval` as first candidate, since `agent` nodes obtain their schema from the Agent store.
- **Security:** Limit source-code size; JavaParser does not execute code; YAML parsing with Jackson-YAML is safe.

---

## 10. Files to be touched

### Backend
- `workflow-runner/pom.xml` — dependencies `javaparser-core`, `jackson-dataformat-yaml`
- `quarkus/pom.xml` — dependency `jackson-dataformat-yaml`
- `workflow-runner/schema/JavaSourceToJsonSchema.java` — **new**
- `workflow-runner/schema/YamlToJsonSchema.java` — **new** (optional)
- `quarkus/http/SchemaResource.java` — extend (`/from-java`, `/from-yaml`)
- `workflow-runner/runner/DefaultNodeDispatcher.java` — `llm_eval` uses `responseSchema`
- `WorkflowReflectionConfig.java` — JavaParser registration for native

### Frontend
- `workflow-ui/src/services/api.ts` — `SchemaApi`
- `workflow-ui/src/components/SchemaSource.tsx` — **new**
- `workflow-ui/src/pages/Agents.tsx` — integrate schema source
- `workflow-ui/src/components/PropertyPanel.tsx` — schema source in Agent Config + node level for `llm_eval`
- `workflow-ui/src/types/workflow.ts` — no change needed (`responseSchema` already present)

### Tests
- `workflow-runner/src/test/java/.../schema/JavaSourceToJsonSchemaTest.java` — **new**
- `workflow-runner/src/test/java/.../schema/YamlToJsonSchemaTest.java` — **new** (optional)
- `quarkus/src/test/java/.../SchemaResourceTest.java` — **new** (optional)
