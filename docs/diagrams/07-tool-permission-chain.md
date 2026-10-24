# 07 – Tool- und Permission-Kette

## Zweck

Das Diagramm zeigt, was mit einem vom LLM vorgeschlagenen Tool-Call passiert:
Input-Decode, Snapshot-Auflösung, Permission-Entscheidung, Ausführung,
Output-Decode, Bounding und Rückgabe an den Step.

```mermaid
flowchart TD
  Llm["LLM<br/>ToolCallRequested"]:::llm --> Step["Step<br/>mixed"]:::mixed
  Step --> Runtime["ToolRuntime.execute<br/>deterministisch"]:::det
  Runtime --> Hook["Hooks.beforeToolCall"]:::det
  Hook --> Snapshot["ToolSnapshot.require(name)"]:::det
  Snapshot --> Decode["Input gegen JSON-Schema decodieren"]:::det
  Decode --> Auth["ToolAuthorizer.request(...)"]:::det
  Auth --> Perm["PermissionEngine.assertAllowed"]:::det
  Perm --> Rules["PermissionRules.decide"]:::det
  Rules --> Effect{"Effect?"}:::det

  Effect -->|"DENY"| Denied["PermissionDenied<br/>Modell sieht Fehler"]:::det
  Effect -->|"ASK"| Ask["ApprovalSource<br/>UI / Human"]:::ext
  Ask -->|"approve"| Execute
  Ask -->|"reject ohne Feedback"| Aborted["Aborted<br/>Modell sieht nichts"]:::det
  Ask -->|"reject mit Feedback"| Denied
  Effect -->|"ALLOW"| Execute["Tool.execute(decodedInput)"]:::mixed

  Execute --> Outcome["ToolOutcome"]:::det
  Outcome -->|"Failed"| Failed["ToolFailure / SessionError"]:::det
  Outcome -->|"Ok"| Output["Output gegen Schema decodieren"]:::det
  Output --> After["Hooks.afterToolCall"]:::det
  After --> Normalize["normalize"]:::det
  Normalize --> Bound["OutputBounding<br/>Full Output speichern"]:::det
  Bound --> Result["ToolResult an Step"]:::det

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef llm fill:#ffe0b2,stroke:#e65100,stroke-width:1px,color:#111;
  classDef mixed fill:#fff9c4,stroke:#f9a825,stroke-width:1px,color:#111;
  classDef ext fill:#eceff1,stroke:#546e7a,stroke-width:1px,color:#111;
```

## Reihenfolge ist Vertrag

```text
decode → permission → execute → encode
```

Diese Reihenfolge darf nicht vertauscht werden. Ein Tool darf nicht ausgeführt
werden, bevor Input und Permission geprüft sind. Ein Tool schreibt auch keine
Events; die Persistenz liegt beim Step bzw. der Session.

## Permission-Entscheidung

```mermaid
flowchart LR
  Check["PermissionCheck"]:::det --> Policy{"Policy-Deny?"}:::det
  Policy -->|"ja"| Deny1["DENY<br/>final"]:::det
  Policy -->|"nein"| Rules["Regeln:<br/>letzte Übereinstimmung gewinnt"]:::det
  Rules --> DenyPre{"Deny für<br/>eine Ressource?"}:::det
  DenyPre -->|"ja"| Deny2["DENY"]:::det
  DenyPre -->|"nein"| Saved["Saved Approvals<br/>anhängen"]:::det
  Saved --> AskCheck{"Ask vorhanden?"}:::det
  AskCheck -->|"ja"| Ask["ASK"]:::det
  AskCheck -->|"nein"| Allow["ALLOW"]:::det

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
```

Wichtig:

- **Policy-Deny hat Vorrang**, auch vor gespeicherten Approvals.
- **Policy-Allow** kann eine Regel-Deny aufheben (lift), gewährt aber nie
  selbst eine Erlaubnis.
- **Deny vor Saved Approval**: eine Regel-Deny blockiert die Operation.
- **Ask dominiert Allow**: wenn eine Ressource `ASK` ist, wird gefragt.
- **Kein Treffer** bedeutet `ASK` (nicht `ALLOW`).
- **Keine Regeln** bedeutet `DENY` (sichere Voreinstellung).

## Tool-Katalog

| Tool | Ausführung | Nebenwirkung | Determinismus |
|---|---|---|---|
| `read`, `glob`, `grep` | FS-Zugriff | lesen | deterministisch |
| `write`, `edit`, `patch` | FS-Zugriff | schreiben | deterministisch |
| `shell` | Prozess | externer Prozess | deterministisch gerahmt |
| `skill` | Katalog | lesen | deterministisch |
| `mcp` | JSON-RPC/HTTP | externer Dienst | extern |
| `websearch` | Firecrawl | Netzwerk | extern |
| `webfetch` | HTTP | Netzwerk | extern |
| `subagent` | Child-Session | LLM-Lauf | gemischt |
| `todo` | In-Memory/Store | Zustand | deterministisch |
| `question` | UI/Human | Interaktion | extern |
| `session` (rename/move/models) | Store | Metadaten | deterministisch |
| `worktree` | Git-Prozess | Worktree | nur wenn Git vorhanden |

## Parallelität

`Step` führt Tool-Calls aus einem Modell-Stream parallel aus
(`MAX_PARALLEL_TOOLS = 8`) und hält die Ergebnisse in Call-Reihenfolge. Die
Determinismus-Grenze bleibt: Die **Auswahl** kommt vom LLM, die **Ausführung**
und Reihenfolge der Ergebnisse sind kontrolliert.

## Fehlerkanäle

| Ereignis | Kanal |
|---|---|
| Regel-Deny | `PermissionDenied` → Modell sieht den Fehler |
| User-Reject ohne Feedback | `Aborted` → Step endet, Modell sieht nichts |
| User-Reject mit Feedback | `PermissionDenied` mit Feedback |
| Tool-Fehler | `ToolOutcome.Failed` / `SessionError` |
| Schema-Fehler | `ToolFailure` → `ToolOutcome.Failed` |

## Quellen

- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/runtime/ToolRuntime.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/permission/engine/PermissionEngine.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/core/eval/PermissionRules.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/registry/ToolRegistry.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/step/Step.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/`
