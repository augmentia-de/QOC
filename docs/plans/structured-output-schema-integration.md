# Implementierungsplan: Java-Records und YAML in Structured-Output-Konfiguration

**Status:** Plan (2026-11-12)
**Bezug:** P0/P1/P2 abgeschlossen (Commit `a89ea1a`), Structured-Output-Infrastruktur vorhanden (`SchemaResource`, `JavaRecordToJsonSchema`, `OpenApiToJsonSchema`)

---

## 1. Ziel

Strukturierte Ausgabe (Response Schema) soll nicht mehr nur als handgeschriebenes JSON-Schema konfigurierbar sein, sondern aus **Java-Quellcode** (Records/POJOs/DTOs) und **YAML-Dateien** (OpenAPI oder reines JSON Schema) generiert werden können — auf **Agent-Ebene** und **Node-Ebene** (z. B. `llm_eval`).

Die Integration muss in **JVM- und Native-Mode** funktionieren.

---

## 2. Bestehende Infrastruktur

### Backend
| Datei | Rolle |
|---|---|
| `workflow-runner/schema/JavaRecordToJsonSchema.java` | Reflection: Java-Record/POJO → JSON-Schema (Map) |
| `workflow-runner/schema/OpenApiToJsonSchema.java` | OpenAPI 3.x (YAML/JSON) → JSON-Schema |
| `quarkus/http/SchemaResource.java` | JAX-RS `/api/schema`: `from-class`, `from-openapi`, `validate` |
| `WorkflowReflectionConfig.java` | Native-Reflection-Registrierung für Schema-Klassen |
| `QocEngine.AgentConfig.responseSchema` | JSON-Schema für LLM-Structured-Output |
| `Agent.responseSchema`, `NodeData.responseSchema` | Persistierte Schemas |
| `StructuredOutputConverter` | LLM-`structured()` → `Map` für Workflow-Kontext |

### Frontend
| Datei | Rolle |
|---|---|
| `types/workflow.ts` | `Agent.responseSchema`, `NodeData.responseSchema` |
| `pages/Agents.tsx` | Textarea für JSON-Schema (manuell) |
| `components/PropertyPanel.tsx` | Agent Config: Response-Schema-Textarea |
| `services/api.ts` | **Kein** `SchemaApi` |

### Tests
- `JavaRecordToJsonSchemaTest`, `SchemaParseCompatTest` sichern Record→Schema→Parse.

---

## 3. Integrationspfade (entschieden)

### 3.1 Java Records → nur Quellcode-Upload

Quellcode wird geparst, **keine** Reflection-Registrierung für User-Klassen nötig → Native-tauglich.

- **Backend:** `POST /api/schema/from-java` mit Body `{ source: string }`.
  - Parst `.java`-Quellcode mit `JavaParser` (`com.github.javaparser:javaparser-core:3.28.0`).
  - Extrahiert Record-Komponenten und Klassenfelder aus dem AST.
  - Konvertiert zu JSON-Schema (Type-Mapping analog zu `JavaRecordToJsonSchema`).
  - Gibt `{ schema, className }` zurück.
  - Größenlimit: **50 KB**. Keine Code-Ausführung.
- **Frontend:** Datei-Upload (`<input type="file" accept=".java">`) oder Textarea; „Schema generieren“ ruft `/from-java` auf und befüllt das Schema-Textarea.

### 3.2 YAML-Dateien → beide Modi (Auto-Detect)

- **OpenAPI YAML:** Bereits über `/from-openapi` möglich (Swagger-Parser liest YAML). Frontend sendet Dateiinhalt.
- **Reines JSON Schema in YAML:** Neuer Endpoint `POST /api/schema/from-yaml` `{ yaml }`.
  - Parst YAML mit Jackson-YAML → Map.
  - Auto-Detect: wenn `openapi`-Schlüssel vorhanden → `OpenApiToJsonSchema`; sonst als JSON Schema validieren und zurückgeben.
- **Frontend:** File-Upload/Textarea + Modus-Auswahl (OpenAPI vs. JSON Schema).

### 3.3 Strukturierte Ausgabe auf beiden Ebenen

- **Agent-Ebene:** Schema wird im Agent-Store persistiert (bestehend).
- **Node-Ebene:** `NodeData.responseSchema` ist bereits im Modell. Für `llm_eval` (und optional `agent`-Nodes als Override) im PropertyPanel exponieren.
- Backend-Dispatcher `llm_eval` nutzt dann `node.data().responseSchema()` statt hart codiertem `true`.

---

## 4. Backend-Änderungen

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

### 4.2 Neue Dateien

**`workflow-runner/schema/JavaSourceToJsonSchema.java`**
- Parst Java-Quellcode mit `JavaParser`.
- Extrahiert `RecordDeclaration`-Komponenten und `ClassDeclaration`-Felder (nur public/non-static).
- Type-Mapping analog zu `JavaRecordToJsonSchema` (String, int, Optional, Collections, verschachtelte Typen, Enums).
- Unterstützt `@JsonProperty`, `@Schema(description)`.
- Gibt `Map<String, Object>` (JSON Schema) zurück.

**`workflow-runner/schema/YamlToJsonSchema.java` (optional)**
- Wrapper: parst YAML-String mit Jackson-YAML → Map.
- Wenn `openapi`-Schlüssel vorhanden → delegiert an `OpenApiToJsonSchema`.
- Sonst validiert die Map als JSON Schema und gibt sie zurück.

### 4.3 `SchemaResource.java` erweitern

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

JavaParser-Klassen für native Reflection registrieren:
```java
@RegisterForReflection(classNames = {
    "com.github.javaparser.ast.CompilationUnit",
    "com.github.javaparser.ast.body.RecordDeclaration",
    "com.github.javaparser.ast.body.ClassOrInterfaceDeclaration",
    "com.github.javaparser.ast.body.FieldDeclaration",
    "com.github.javaparser.ast.type.Type",
    // ... weitere benötigte Klassen
})
```

### 4.6 Security

- Quellcode/YAML-Größe auf 50 KB begrenzen.
- JavaParser führt keinen Code aus → keine Ausführungsgefahr.
- YAML-Parsing: Jackson-YAML ist sicher (keine Code-Execution bei Standard-Konfiguration).
- Klassennamen-Endpoint (`/from-class`) wird **nicht** mehr im MVP verwendet; stattdessen Quellcode-Parsing.

---

## 5. Frontend-Änderungen

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

### 5.2 Neues Component `SchemaSource.tsx`

Wiederverwendbare UI für Schema-Import:

```tsx
interface SchemaSourceProps {
  value: Record<string, unknown> | null;
  onChange: (schema: Record<string, unknown> | null) => void;
  disabled?: boolean;
}

// Modus: 'manual' | 'java' | 'openapi' | 'yaml'
// - manual: Textarea (bestehend)
// - java: File-Upload (.java) oder Textarea + "Schema generieren"
// - openapi: Textarea/Upload + "Schemas laden" → Dropdown + "Übernehmen"
// - yaml: Textarea/Upload → "Schema parsen"
// Validierung: "Schema validieren" Button + Fehleranzeige
```

### 5.3 Integration in `Agents.tsx`

- Response-Schema-Textarea durch `SchemaSource` ersetzen.
- Modus-Auswahl über Dropdown.
- Datei-Upload via `<input type="file" accept=".java,.yaml,.yml,.json">`.
- `FileReader.readAsText()` → Inhalt an den entsprechenden Endpoint.

### 5.4 Integration in `PropertyPanel.tsx` (Agent Config)

- Bestehendes Response-Schema-Textarea durch `SchemaSource` ersetzen.
- Gleiche Funktionalität wie in `Agents.tsx`.

### 5.5 Node-Level `responseSchema` für `llm_eval`

- PropertyPanel: Collapsible „Output Schema“ für `llm_eval`-Nodes.
- Textarea (JSON) oder `SchemaSource`-Component.
- `updateField('responseSchema', schema)`.

---

## 6. Phasen

| Phase | Inhalt | Aufwand |
|---|---|---|
| **SS1** | Backend `/from-yaml` + Jackson-YAML Dependency | 0,5 Tag |
| **SS2** | Backend `/from-java` mit JavaParser (Quellcode → Schema) | 1 Tag |
| **SS3** | Frontend `SchemaApi` + `SchemaSource`-Component | 1 Tag |
| **SS4** | Integration in `Agents.tsx` und `PropertyPanel.tsx` | 0,5 Tag |
| **SS5** | Node-Level `responseSchema` für `llm_eval` (UI + Dispatcher) | 0,5–1 Tag |
| **SS6** | Tests + Native-Verifikation | 0,5–1 Tag |

---

## 7. Tests

- **Backend:** Unit-Tests für `JavaSourceToJsonSchema` (Record, POJO, verschachtelt, Optional, Enum).
- **Backend:** Unit-Test für `from-yaml` (OpenAPI YAML + reines JSON Schema YAML).
- **Backend:** Integration-Test für `/from-java` und `/from-yaml` in `SchemaResource` (oder bestehende Test-Klasse erweitern).
- **Frontend:** Manuelle Verifikation (Datei-Upload, Schema-Generierung, Validierung, Save/Reload).
- **Native:** Build mit `./deploy/build-native-image.sh` und Smoke-Test der Endpoints.

---

## 8. Offene Fragen (beantwortet)

1. **Java-Records:** Nur Quellcode-Upload (A2). ✅
2. **YAML:** Beide Modi (OpenAPI + reines JSON Schema), Auto-Detect. ✅
3. **Node-Level:** Generell für zukünftige Node-Definitionen nutzbar (`llm_eval` als erster Kandidat). ✅
4. **Native-Registrierung:** Fällt weg bei Quellcode-Parsing. ✅
5. **Größenlimit:** 50 KB. ✅

---

## 9. Empfehlung

- **MVP (SS1–SS4):** Java-Quellcode-Upload + OpenAPI/YAML-Import in der Agent-Konfiguration.
- **Native:** Quellcode-Parsing braucht keine Reflection-Registrierung für User-Klassen; nur JavaParser/YAML-Parser müssen nativ laufen (Quarkus-Dependency-Handling).
- **Node-Level (SS5):** Nur für `llm_eval` als ersten Kandidaten, da `agent`-Nodes ihr Schema aus dem Agent-Store beziehen.
- **Sicherheit:** Quellcode-Größe begrenzen; JavaParser führt keinen Code aus; YAML-Parsing mit Jackson-YAML ist sicher.

---

## 10. Dateien, die angefasst werden

### Backend
- `workflow-runner/pom.xml` — Dependencies `javaparser-core`, `jackson-dataformat-yaml`
- `quarkus/pom.xml` — Dependency `jackson-dataformat-yaml`
- `workflow-runner/schema/JavaSourceToJsonSchema.java` — **neu**
- `workflow-runner/schema/YamlToJsonSchema.java` — **neu** (optional)
- `quarkus/http/SchemaResource.java` — erweitern (`/from-java`, `/from-yaml`)
- `workflow-runner/runner/DefaultNodeDispatcher.java` — `llm_eval` nutzt `responseSchema`
- `WorkflowReflectionConfig.java` — JavaParser-Registrierung für native

### Frontend
- `workflow-ui/src/services/api.ts` — `SchemaApi`
- `workflow-ui/src/components/SchemaSource.tsx` — **neu**
- `workflow-ui/src/pages/Agents.tsx` — Schema-Quelle integrieren
- `workflow-ui/src/components/PropertyPanel.tsx` — Schema-Quelle in Agent Config + Node-Level für `llm_eval`
- `workflow-ui/src/types/workflow.ts` — keine Änderung nötig (`responseSchema` bereits vorhanden)

### Tests
- `workflow-runner/src/test/java/.../schema/JavaSourceToJsonSchemaTest.java` — **neu**
- `workflow-runner/src/test/java/.../schema/YamlToJsonSchemaTest.java` — **neu** (optional)
- `quarkus/src/test/java/.../SchemaResourceTest.java` — **neu** (optional)
