# Plan: Strict Structured Outputs (Phase 3 — offen)

**Status:** Offen (nicht implementiert) — 2026-11-16
**Bezug:** `docs/plans/node-data-contracts-and-context-economy.md` (Phase 3),
`docs/plans/node-data-contracts-progress.md`

## 1. Aktuelle Implementierung (Option 1) — mit Schwächen

qoc nutzt für strukturierte Ausgaben das reservierte `submit_result`-Tool (Function Calling).
OpenAI validiert Tool-Argumente gegen das Schema, aber im **non-strict**-Modus kann das
Modell Abweichungen machen (zusätzliche Felder, fehlende required, falsche Enums).
Diese werden von qoc reaktiv catcht (`ToolRuntime.decode`, `NodeContractValidator`), was
retries nötig macht → höhere Latenz und Kosten.

**Schwächen:**
- OpenAI kann Abweichungen machen → retries nötig → höhere Latenz/Kosten.
- Validierung ist **reaktiv** (nach dem Call), nicht **präventiv** (während der Generierung).
- `submit_result`-Tool-Schema ist nicht strict-kompatibel (additionalProperties: true, optionale Felder).
- Kein Constrained Decoding → Modell kann in seltenen Fällen ungültiges JSON produzieren.

**Stärken (bereits vorhanden):**
- Abweichungen werden durch `ToolRuntime.decode()` und `NodeContractValidator` catcht.
- Semantische Fehler (`Empty`/`InvalidArgument`/`Unavailable`/`Failed`) sind unterscheidbar.
- Node-Outputs werden gegen `outputContract` validiert.
- Funktioniert mit allen Providern (Function Calling ist portabel).

## 2. OpenAI Strict als nächste Erweiterung (Option 2)

**Ziel:** Strict-Enforcement (Constrained Decoding) aktivieren, damit das Modell
technisch keine Abweichungen machen kann.

Zwei Wege, um "strict" in qoc zu erreichen:

### Weg A: `response_format`-Weg

Statt `submit_result`-Tool → `response_format: json_schema` auf dem ChatRequest setzen.

**Ablauf:**
1. `response_format: { type: "json_schema", json_schema: { strict: true } }` wird auf den ChatRequest gesetzt.
2. OpenAI enforced das Schema via Constrained Decoding.
3. Bei Parse-Fehler → force-prompt + retry.

**Vorteile:**
- Nutzt OpenAI's Constrained Decoding direkt (strict garantiert).
- Kein Tool-Call-Overhead für structured output.

**Nachteile:**
- Großer Refactor — verliert die Tool-Integration.
- Structured output und tools koexistieren schlecht (structured output wird ignoriert wenn tool calls present).
- qoc's `submit_result`-Tool müsste entfernt/umgebaut werden.
- Verliert `NodeContractValidator`-Integration (kein Node-Output-Contract mehr).

**Aufwand:** Hoch (Architektur-Änderung)

**Empfehlung:** ❌ Nicht empfohlen für qoc — qoc's Architektur ist auf Function Calling aufgebaut.

### Weg B: `strict: true` auf `ToolSpecification` (Function-Calling-Strict)

`strict(true)` auf dem `submit_result`-Tool setzen. OpenAI enforced dann das Schema
bei der Generierung der Tool-Argumente.

**Ablauf:**
1. `LangChain4jProviderAdapter.toToolSpecification()` setzt `.strict(true)`.
2. `toJsonObjectSchema()` setzt `additionalProperties(false)` (statt `true` oder unset).
3. Alle Felder müssen `required` sein (keine `Schema.Opt` auf Top-Level).
4. OpenAI garantiert Schema-Einhaltung bei der Generierung.

**Vorteile:**
- Behält qoc's Architektur (submit_result-Tool, Tool-Integration, NodeContractValidator).
- Kein großer Refactor.
- Präventiv statt reaktiv (keine retries nötig).

**Nachteile:**
- Erfordert Schema-Anpassungen (additionalProperties: false, all required).
- `Schema.Opt` (optionale Felder) nicht erlaubt auf Top-Level in strict mode.
- Nur OpenAI/Azure (keine Provider-Portabilität).
- Erfordert echtes API-Testing.

**Aufwand:** Mittel (Schema-Anpassungen + Testing)

**Empfehlung:** ✅ Empfohlen für qoc.

## 3. Implementierungsschritte (Weg B)

### Schritt 1: Tool-Schemas strict-kompatibel machen

**Datei:** `engine/src/main/java/de/augmentia/oc/engine/core/json/Schema.java`

- `Schema.Opt` (optionale Felder) auf Top-Level verbieten, wenn strict mode aktiv.
- Alternativ: `Schema.Opt` in strict mode als `required` mit `null`-Default behandeln.

**Datei:** `quarkus/src/main/java/de/augmentia/oc/engine/http/LangChain4jProviderAdapter.java`

```java
private ToolSpecification toToolSpecification(ToolDefinition definition) {
    return ToolSpecification.builder()
            .name(definition.effectiveName())
            .description(definition.description())
            .parameters(toJsonObjectSchema(definition.inputSchema()))
            .strict(true)  // ← NEU
            .build();
}

private JsonObjectSchema toJsonObjectSchema(Schema schema) {
    if (schema instanceof Schema.Obj obj) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Schema> field : obj.fields().entrySet()) {
            Schema inner = field.getValue();
            boolean optional = inner instanceof Schema.Opt;
            Schema unwrapped = optional ? ((Schema.Opt) inner).inner() : inner;
            builder.addProperty(field.getKey(), toLcSchema(unwrapped));
            // strict: alle Felder required
            required.add(field.getKey());
        }
        // strict: additionalProperties: false
        builder.additionalProperties(false);
        return builder.required(required).build();
    }
    // ...
}
```

### Schritt 2: `strict`-Flag konfigurierbar machen

- System-Property: `qoc.structured-output.strict=true` (Default: `false` für Abwärtskompatibilität).
- Wenn `false`: aktuelles Verhalten (non-strict, eigene Validierung fängt Abweichungen).
- Wenn `true`: strict mode aktiviert (Constrained Decoding).

### Schritt 3: Tests

**Unit-Tests:**
- `toJsonObjectSchema` setzt `additionalProperties(false)` wenn strict.
- `toJsonObjectSchema` setzt alle Felder als `required` wenn strict.
- `toToolSpecification` setzt `.strict(true)` wenn strict-Flag aktiv.

**Integration-Tests (erfordert echten API-Key):**
- LLM mit strict mode gibt schema-konformes `submit_result` zurück.
- LLM ohne strict mode kann Abweichungen machen (Baseline).
- Vergleich: Latenz/Kosten mit/ohne strict.

### Schritt 4: Dokumentation

- `docs/CONFIG.md`: neues Property `qoc.structured-output.strict`.
- `docs/guides/langchain4j.md`: Strict-Mode-Erklärung.

## 4. Risiken

| Risiko | Mitigation |
|---|---|
| Schema nicht strict-kompatibel → OpenAI lehnt ab | Validierung beim Start: prüfen ob Schema strict-kompatibel |
| `Schema.Opt` (optionale Felder) nicht erlaubt | Entweder entfernen oder als required mit null-Default |
| Provider-Portabilität verloren (nur OpenAI/Azure) | Feature hinter Flag, Default: false (portabel) |
| Tests mit demo-key schlagen fehl | Integration-Tests nur mit echtem Key (CI-Secret) |

## 5. Abhängigkeiten

- LangChain4j `ToolSpecification.strict(Boolean)` — verfügbar seit 1.19.3 (verifiziert).
- OpenAI Function-Calling-Strict-Mode — verfügbar seit Aug 2024 (gpt-4o, gpt-4o-mini).

## 6. Warum offen?

1. **Schema-Anpassungen** erfordern Änderungen an `Schema.java` und allen Tool-Definitionen.
2. **Echtes API-Testing** nötig — die Quarkus-Tests laufen mit `demo-key` und können strict mode nicht validieren.
3. **Provider-Portabilität** — strict mode ist OpenAI-exklusiv; Default muss `false` bleiben.
4. **Priorität** — qoc's aktuelle Umsetzung (non-strict + eigene Validierung) ist funktional sicher. Strict ist ein Performance/Reliability-Gewinn, kein Sicherheitsgewinn.

## 7. Empfehlung

- **Sofort:** Nicht nötig — qoc's aktuelle Umsetzung ist funktional ausreichend.
- **Mittelfristig:** Wenn hohes Volumen oder kritische Anwendungen → Weg B implementieren.
- **Langfristig:** Wenn Provider-Portabilität nicht mehr nötig → Weg A evaluieren (response_format).

## 8. Dritte Option: Beibehaltung der aktuellen Umsetzung (Option 1)

Falls weder Weg A noch Weg B umgesetzt wird, bleibt die aktuelle Implementierung
(Option 1) bestehen:

- Non-strict Function Calling + eigene Validierung (`ToolRuntime.decode`, `NodeContractValidator`).
- Abweichungen werden reaktiv catcht → retries möglich.
- Funktional sicher, aber nicht optimal effizient.
- Portabel über alle Provider.

**Empfehlung für Option 1:** Ausreichend für die meisten Use Cases. Strict-Mode (Weg B)
lohnt sich nur bei hohen Volumen oder kritischen Anwendungen.