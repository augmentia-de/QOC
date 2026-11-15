# Implementierungsplan: Verify-on-Stop Guards

Status: Plan (2026-11-15)
Bezug: `engine/.../QocEngine.java` (Agent-Loop, Zeilen 844-1260), Paper "Harness Engineering" §16 (Design Recommendations), Observation "Verify-on-Stop Guards"

## 1. Problem

Der Agent-Loop in `QocEngine.runPrompt` endet, sobald:
- `hasToolCalls == false` (keine weiteren Tool-Calls) und TurnPolicy sagt `ANSWER_NOW`/`FAIL_TURN`, oder
- `maxSteps` erreicht wird, oder
- ein Loop erkannt wird.

Es gibt **keine deterministische Pruefung**, ob der Agent die Aufgabe tatsaechlich abgeschlossen hat (z. B. ob `verify_stock` aufgerufen wurde, ob Tests gelaufen sind, ob ein `submit_result` erfolgte). Der Agent kann "fertig" melden, obwohl kritische Schritte fehlen.

## 2. Zielbild

Vor dem Abschluss eines Runs (vor `break`/`return`) wird ein **Verify-on-Stop Guard** ausgefuehrt, der:
1. Die Liste der aufgerufenen Tools prueft (Tracker: `recentToolCalls`).
2. Erforderliche Abschluss-Bedingungen evaluiert (z. B. "mindestens ein Test-Tool aufgerufen", "submit_result aufgerufen").
3. Bei Verletzung: entweder den Loop fortsetzen (mit Hinweis an das LLM) oder den Run als "unvollstaendig" markieren.

## 3. Datenmodell

### 3.1 AgentConfig (QocEngine + quarkus EngineConfig)

Neues Feld:
- `verifyOnStop: List<VerifyRule>` - Default `List.of()` = keine Pruefung (Rueckwaertskompatibel).

```java
public record VerifyRule(String name, VerifyCondition condition, VerifyAction onFail) {}
```

### 3.2 VerifyCondition (sealed interface)

```java
public sealed interface VerifyCondition {
    record ToolCalled(String toolName) implements VerifyCondition {}
    record ToolCalledWith(String toolName, String paramPattern) implements VerifyCondition {}
    record AnyOf(List<VerifyCondition> conditions) implements VerifyCondition {}
    record AllOf(List<VerifyCondition> conditions) implements VerifyCondition {}
}
```

### 3.3 VerifyAction

```java
public enum VerifyAction {
    CONTINUE,   // Loop fortsetzen, Hinweis an LLM
    WARN,       // Run als unvollstaendig markieren, aber abschliessen
    FAIL        // Run als fehlgeschlagen markieren
}
```

### 3.4 Agent-Frontmatter / Markdown

Neuer Frontmatter-Key:
```yaml
verifyOnStop:
  - condition: tool_called
    tool: run_tests
    onFail: continue
  - condition: tool_called
    tool: submit_result
    onFail: warn
```

### 3.5 Workflow-Node (NodeData)

Neues Feld in `NodeData`:
- `verifyOnStop: List<VerifyRule>` (Default `null` = erbt vom Agent).

## 4. Implementierung

### 4.1 Tracker (bereits vorhanden)

`QocEngine` hat bereits `recentToolCalls: List<ToolCall>` (Zeile 866) und `finished.toolCalls()`. Der Tracker wird pro Run gefuehrt.

### 4.2 Guard-Evaluation

Neue Methode in `QocEngine`:

```java
private List<VerifyRule> evaluateVerifyOnStop(List<VerifyRule> rules,
        List<ToolCall> calledTools) {
    List<VerifyRule> failed = new ArrayList<>();
    for (VerifyRule rule : rules) {
        boolean ok = switch (rule.condition()) {
            case ToolCalled tc -> calledTools.stream()
                    .anyMatch(c -> c.name().value().equals(tc.toolName()));
            case ToolCalledWith tcw -> calledTools.stream()
                    .anyMatch(c -> c.name().value().equals(tcw.toolName())
                            && matches(c.input(), tcw.paramPattern()));
            case AnyOf ao -> ao.conditions().stream().anyMatch(c -> evaluate(c, calledTools));
            case AllOf ao -> ao.conditions().stream().allMatch(c -> evaluate(c, calledTools));
        };
        if (!ok) failed.add(rule);
    }
    return failed;
}
```

### 4.3 Integration in den Agent-Loop

An drei Stellen im Loop (Zeilen ~1174, ~1190, ~1240):

**Stelle 1: Keine Tool-Calls mehr (Zeile 1174)**
```java
if (!hasToolCalls) {
    List<VerifyRule> failed = evaluateVerifyOnStop(agent.verifyOnStop(), recentToolCalls);
    if (!failed.isEmpty()) {
        // CONTINUE: Hinweis an LLM, Loop fortsetzen
        // WARN: als unvollstaendig markieren, aber abschliessen
        // FAIL: als fehlgeschlagen markieren
        handleVerifyFailure(failed, ...);
        if (shouldContinue) { stepNo++; continue; }
    }
    break;
}
```

**Stelle 2: TurnPolicy ANSWER_NOW (Zeile 1190)**
Gleiche Pruefung; bei CONTINUE -> `continue` statt forced final step.

**Stelle 3: maxSteps erreicht (Zeile 899)**
Gleiche Pruefung; bei FAIL -> `EngineResult.failed(...)`.

### 4.4 Handhabung bei Verletzung

```java
private void handleVerifyFailure(List<VerifyRule> failed, ...) {
    String tools = failed.stream().map(r -> r.condition().toString())
            .collect(Collectors.joining(", "));
    String message = "\n\n**[Verify-on-Stop] Unvollstaendig:** Erforderliche Schritte nicht ausgefuehrt: "
            + tools + ". Bitte fuehre die fehlenden Schritte aus.";
    statusSink.accept(message);
    // CONTINUE: Loop fortsetzen (stepNo++ + continue)
    // WARN: als unvollstaendig markieren (EngineResult mit Warnung)
    // FAIL: EngineResult.failed(...)
}
```

### 4.5 System-Prompt-Injektion

Wenn `verifyOnStop` nicht leer, an System-Prompt anhaengen:
```
"Abschluss-Bedingungen: Bevor du fertig bist, stelle sicher, dass folgende Tools aufgerufen wurden: [run_tests, submit_result]."
```

## 5. Dateien

### Neu
- `engine/.../core/eval/VerifyRule.java` (record)
- `engine/.../core/eval/VerifyCondition.java` (sealed interface)
- `engine/.../core/eval/VerifyAction.java` (enum)

### Geaendert
- `engine/.../runtime/QocEngine.java` (AgentConfig + Loop-Integration + Prompt-Injektion)
- `quarkus/.../http/EngineConfig.java` (AgentConfig + Frontmatter-Parsing)
- `quarkus/.../http/AgentMarkdownLoader.java` (Frontmatter-Parsing fuer verifyOnStop)
- `quarkus/.../http/AgentMarkdownExporter.java` (Export)
- `workflow-runner/.../model/WorkflowDefinition.java` (NodeData.verifyOnStop)
- `workflow-runner/.../runner/DefaultNodeDispatcher.java` (Prompt-Injektion)

### Tests
- `QocEngineTest` - Verify-on-Stop Cases (CONTINUE, WARN, FAIL).
- `EngineConfigTest` - Frontmatter-Parsing.
- `WorkflowBuilderTest` - NodeData-Mapping.

## 6. Reihenfolge der Umsetzung

1. **Tag 1:** Datenmodell (VerifyRule, VerifyCondition, VerifyAction) + AgentConfig-Feld.
2. **Tag 1-2:** Guard-Evaluation + Loop-Integration (3 Stellen).
3. **Tag 2:** System-Prompt-Injektion + Frontmatter-Parsing/Export.
4. **Tag 2-3:** Workflow-Node-Integration (NodeData + DefaultNodeDispatcher).
5. **Tag 3:** Tests (QocEngineTest, EngineConfigTest).
6. **Tag 3:** Docs (spec/tools, spec/agents).

## 7. Risiken

- **Endlos-Loop:** Wenn CONTINUE gewaehlt und der Agent die Bedingung nicht erfuellen kann, koennte der Loop bis maxSteps laufen. Mitigation: maxSteps als harte Grenze (bereits vorhanden).
- **False Positives:** Wenn ein Tool mit falschem Namen aufgerufen wurde (z. B. `run_test` statt `run_tests`), schlaegt die Pruefung fehl. Mitigation: Fuzzy-Matching oder Alias-Liste.
- **Token-Kosten:** System-Prompt-Injektion erhoeht den Prompt. Mitigation: nur bei nicht-leerer verifyOnStop.

## 8. Offene Fragen

1. Soll Verify-on-Stop **global** (pro Agent) oder **pro Run** konfigurierbar sein?
2. Soll bei `FAIL` der Run als fehlgeschlagen markiert werden, oder soll er als "unvollstaendig" (mit Warnung) enden?
3. Soll die Pruefung auch **Rueckwaerts** gelten (z. B. "Tool X darf nicht aufgerufen worden sein")?
