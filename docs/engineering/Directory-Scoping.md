# Per-Task Directory-Scoping (nur bestimmte Verzeichnisse schreibbar)

> Status: Plan (2026-11-14)
> Bezug: `Two-Agent-Systems.md`, `Workflow.md`, `CONFIG.md`

## 1. Ziel

Bei einer Aufgabe (Workflow-Knoten) sollen nur Code-Änderungen in bestimmten
Verzeichnissen erlaubt sein, alle anderen Pfade sind **read-only**. Der Agent
muss diese Einschränkung **kennen** (Bewusstsein), darf nicht drumherum
arbeiten (kein Code-Duplizieren, keine Wrapper/Abstraktionen, kein
Ausweichen), und die Einschränkung muss **pro Aufgabe anpassbar** sein bzw.
über `reloadConfig` neu gesetzt werden können.

## 2. Problem

- OpenCode-Permissions sind **statisch pro Agent-ID**, nicht session- oder
  aufgabenabhängig. Eine Session erbt die Permissions ihres Agents.
- Workflow-Agenten bekommen im `Engine.runPrompt` keine Permission-Rules, weil
  `agentCatalogue.config(prefixedName)` null liefert → `agentRules =
  ContextBuilder.defaults(...)` (alles erlaubt). `sessionRules` ist leer.
- Wenn der Agent nur ein generisches "permission denied" bekommt, versucht er
  typischerweise, drumherum zu arbeiten (Code duplizieren, Wrapper bauen,
  in erlaubte Pfade ausweichen) — genau das soll verhindert werden.

## 3. Datenmodell

### 3.1 `WorkflowDefinition.Agent`

Neues Feld:

- `allowedWritePaths: List<String>` — Default `List.of()` = unbeschränkt.
  Nicht-leer = Allowlist (nur diese Pfade schreibbar).

### 3.2 `WorkflowDefinition.NodeData`

Neues Feld:

- `allowedWritePaths: List<String>` — Default `null` = erbt vom Agent.
  Nicht-null = Override für diesen Knoten (pro Aufgabe anpassbar).

### 3.3 `QocEngine.AgentConfig`

Neues Feld:

- `allowedWritePaths: List<String>` — wird zur Laufzeit an das
  Permission-System weitergegeben.

## 4. Agent-Bewusstsein (System-Prompt-Injektion)

In `DefaultNodeDispatcher` (beim `runAgent`/`toAgentConfig`):

- Effektive Pfade = `node.data().allowedWritePaths()` falls nicht-null,
  sonst `agent.allowedWritePaths()`.
- Wenn nicht-leer: an den System-Prompt anhängen:

> ## Writable directories
> You may only modify files under: <paths>. All other paths are read-only.
> IMPORTANT: Do not duplicate code into writable directories, do not create
> wrapper/abstraction layers to bypass this restriction, and do not rename/move
> files to work around it. If a required change falls in a read-only path, stop
> and report it to the user instead of working around it.

So plant der Agent von vornherein nur in den erlaubten Pfaden.

## 5. Enforcement

### 5.1 Problem

Workflow-Agenten bekommen keine Permission-Rules (siehe Abschnitt 2).

### 5.2 Lösung (Option A — empfohlen)

`Engine.createSession(...)` um einen `List<Rule> permissions`-Parameter erweitern
(oder `SessionInfo` nachträglich mit Rules versehen). In `runAgent` die
effektiven `allowedWritePaths` in Rules übersetzen:

- `deny edit *`
- `allow edit <path>/**` für jeden Pfad

und an die Session hängen. Der bestehende `permissionGate` prüft dann
`sessionRules` automatisch (letzter Treffer gewinnt, deny überschreibt
gespeicherte Approvals).

Vorteile:
- Nutzt die bestehende Permission-Infrastruktur (sessionRules, deny-Override,
  Audit).
- Keine Sonderbehandlung im Engine.
- Rules sind pro Session, also pro Aufgabe; ein neuer Run nutzt automatisch
  die aktuellen Werte.

### 5.3 Deny-Text (instruktiv)

Wenn ein Schreibversuch außerhalb der `allowedWritePaths` liegt, soll der
Permission-Check nicht nur "denied" zurückgeben, sondern eine explizite
Anweisung:

> Write denied: `<path>` is read-only. Allowed write paths: `<paths>`.
> Do NOT duplicate code, create wrappers, or work around this restriction.
> If the required change is in a read-only path, stop and report the limitation
> to the user.

### 5.4 Deny vs. Ask

- **Empfehlung: `deny` (hart)**, nicht `ask`. Grund: `ask` würde den User
  fragen, und der Agent könnte bei einer Freigabe trotzdem versuchen,
  Workarounds zu bauen. Ein hartes `deny` mit klarer Meldung macht dem
  Agenten unmissverständlich, dass der Pfad tabu ist.
- Der User kann bei Bedarf `allowedWritePaths` für die Aufgabe anpassen und
  neu starten (oder `reloadConfig`).

### 5.5 Shell-Schutz

Der Shell-Scanner prüft inferierte Verzeichnisse, aber `echo > file` oder
`sed -i` in einem read-only Pfad könnte durchrutschen. Deshalb:

- Wenn `allowedWritePaths` nicht-leer: `shell` standardmäßig auf `ask` (oder
  `deny` für bekannte Schreibkommandos).
- Alternativ: Shell-Befehle, die in read-only Pfade schreiben, im Scanner als
  `deny` markieren.

### 5.6 Symlinks / Traversal

Pfade kanonisieren (wie `external_directory`), sonst Umgehung.

## 6. Per-Task-Anpassbarkeit + Reload

- `NodeData.allowedWritePaths` ist pro Knoten definiert → verschiedene
  Aufgaben (Nodes) können unterschiedliche Scopes haben.
- `Agent.allowedWritePaths` als Default für alle Knoten, die diesen Agenten
  nutzen.
- Override in der UI (PropertyPanel) pro Node.
- Bei Änderung: `reloadConfig()` oder neuer Workflow-Run nutzt die neuen
  Werte (kein Cache-Problem, da pro Run neu berechnet).

## 7. Fehlerfall: Agent-Verhalten bei Deny

- Der Agent soll **nicht** Code duplizieren, Wrapper bauen oder in erlaubte
  Pfade ausweichen.
- Bei einem Deny: den betroffenen Teil **überspringen** und im Ergebnis
  berichten, statt zu improvisieren.
- NodeResult mit Warnung festhalten, damit der User sieht:
  - Welcher Pfad blockiert wurde,
  - Welche `allowedWritePaths` aktiv waren,
  - Dass der Agent gestoppt/gemeldet hat.
- Der Workflow-Run soll **weiterlaufen** (nicht sofort fehlschlagen), aber
  die Warnung im Log festhalten.

## 8. Import/Export (SSOT)

- `QocAgentConverter.fromQoc` / `fromQocRuntime`: `allowedWritePaths`
  übernehmen (Default leer).
- `AgentMarkdownExporter.toMarkdown`: `allowedWritePaths` als
  Frontmatter-Liste (`allowedWritePaths:`) schreiben.
- `AgentMarkdownLoader`: parsen (analog `toolAllow`-Block).

## 9. UI

- `workflow-ui`: `PropertyPanel.tsx` — Feld "Writable directories" (Liste von
  Pfaden) für Agent-Knoten.
- `Agents.tsx` — Feld für globale Agenten.
- TypeScript-Types (`workflow.ts`) erweitern.

## 10. Tests

- Unit: `DefaultNodeDispatcher` — `allowedWritePaths` aus Node überschreibt
  Agent; leer = unbeschränkt.
- Enforcement: Permission-Check deny bei Schreibversuch außerhalb der Pfade
  (Integration mit PermissionGate).
- Round-trip: Markdown-Export/Import der Pfade.
- Verhalten: Agent erhält instruktiven Deny-Text und System-Prompt-Hinweis.

## 11. Reihenfolge der Umsetzung

1. Datenmodell (Agent, NodeData, QocEngine.AgentConfig) — kompiliert.
2. System-Prompt-Injektion (Bewusstsein) — low risk.
3. Enforcement via Session-Rules (Option A) — core.
4. Import/Export + UI.
5. Tests.

## 12. Risiken / zu klären

- **Shell-Tools:** Der Shell-Scanner prüft inferierte Verzeichnisse; `allowedWritePaths`
  als `edit`-Rules decken `write`/`edit`/`patch` ab, aber nicht
  `shell`-basierte Schreiboperationen (z. B. `echo > file`). Dafür bräuchte
  man zusätzlich `shell`-Rules oder den Scanner.
- **Symlinks / Traversal:** Pfade kanonisieren (wie `external_directory`),
  sonst Umgehung.
- **Leer = unbeschränkt vs. read-only überall:** Default sollte `[]` =
  unbeschränkt sein (Rückwärtskompatibilität). Wenn gewünscht "nur Lesen",
  explizit `[]` mit einer `deny edit *`-Regel — aber das ist eine andere
  Semantik. Empfehlung: `[]` = unbeschränkt, nicht-leer = Allowlist.
- **Teilweise Einschränkung:** Wenn der Agent eine Änderung braucht, die
  sowohl in erlaubten als auch in read-only Pfaden liegt, soll er die
  Änderung in den erlaubten Pfaden machen, wo möglich, und für die read-only
  Teile stoppen und melden — nicht versuchen, die Funktionalität in erlaubte
  Pfade zu "verlagern".
EOF
echo "written"