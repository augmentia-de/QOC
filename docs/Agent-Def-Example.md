# Agent-Def-Example.md — vollständige Agent-Definition

Diese Datei ist eine Referenz **und** ein lauffähiges Beispiel. Sie zeigt alle
Felder, die der Markdown-Agent-Loader heute auswertet, alle internen Tools und
die wichtigsten Kombinationen.

Quellen im Code:

- `quarkus/src/main/java/de/augmentia/oc/engine/http/AgentMarkdownLoader.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/EngineConfig.java` (`AgentConfig`, `AgentSkillsConfig`)
- `quarkus/src/main/java/de/augmentia/oc/engine/http/AgentCatalogue.java` (`isSubagent`)
- `quarkus/src/main/java/de/augmentia/oc/engine/core/eval/PermissionRules.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/runtime/context/AgentProfile.java`

> **Wichtig — Parser-Einschränkungen.** Der Frontmatter-Parser ist bewusst
> minimal und **kein YAML-Parser**:
>
> - Kommentare (`# …`) nur auf **eigenen Zeilen**; **kein Inline-Kommentar**
>   hinter einem Wert (`mode: all  # x` würde den Wert verfälschen).
> - Listen nur als Block (`- wert`) oder als Inline-JSON (`["a", "b"]`).
> - Keine verschachtelten YAML-Objekte. Das `skills`-Objekt wird deshalb
>   **nicht** aus dem Frontmatter gelesen (siehe §5).
> - Kommentare innerhalb einer Blockliste oder eines `permissions`-Blocks
>   können die Liste ungültig machen. Kommentare daher nur zwischen
>   vollständigen Schlüssel-Blöcken platzieren.

---

## 1. Vollständiges Beispiel (Markdown-Frontmatter)

Ablage z. B. unter `.opencode/agents/example-full.md`. Der Body nach dem
schließenden `---` ist der System-Prompt.

```markdown
---
# ===========================================================================
# 1. IDENTITAET
# ===========================================================================
name: example-full
description: Vollstaendiges Beispiel mit allen unterstuetzten Feldern.
color: "#16a085"
hidden: false
disabled: false
mode: all
model: openai/gpt-4o-mini
steps: 0
maxSteps: 0

# ===========================================================================
# 2. TOOLS
# toolAllow: Tool-Namen oder "*"; leer bedeutet keine Tools.
# toolDeny:  wird nach allow entfernt; deny gewinnt immer.
# Alle internen Tools sind in Abschnitt 2 dieser Datei aufgelistet.
# ===========================================================================
toolAllow:
  - "*"
toolDeny: []

# ===========================================================================
# 3. MCP-SERVER
# "*" = alle konfigurierten Server; leere Liste = keine MCP-Tools.
# ===========================================================================
mcpServers:
  - "*"

# ===========================================================================
# 4. ROLLEN (Autorisierung)
# "*" = alle Rollen. Ohne Angabe: build/plan = "*", sonst "admin".
# ===========================================================================
roles:
  - "*"

# ===========================================================================
# 5. PERMISSIONS
# Reihenfolge: agent rules -> session rules -> stored approvals.
# Innerhalb der Regeln gewinnt die LETZTE passende Regel.
# Ein DENY irgendeiner Ressource blockiert die ganze Operation.
# Kein Treffer = ASK. ASK schlaegt ALLOW.
# effect: allow | deny | ask
# ===========================================================================
permissions:
  - action: "*"
    resource: "*"
    effect: allow
  - action: external_directory
    resource: "*"
    effect: deny
  - action: read
    resource: "*.env"
    effect: ask
  - action: edit
    resource: "*.env"
    effect: deny
  - action: shell
    resource: "*"
    effect: ask
  - action: question
    resource: "*"
    effect: allow
  - action: subagent
    resource: "*"
    effect: allow
---

You are a fully configured example agent. This Markdown body becomes the
system prompt.

## Rules

- Follow the permission rules from the frontmatter.
- Prefer read-only operations unless a write action is explicitly allowed.
- Report what you did and why.
```

### Hinweise zu einzelnen Feldern

| Feld | Typ | Wirkung |
|---|---|---|
| `name` | String | Eindeutiger Name. Fehlt er, gilt der relative Dateiname ohne `.md`. |
| `description` | String | **Nur Metadaten.** Wird vom Loader gelesen, aber nicht in `AgentConfig` übernommen. |
| `color` | String | **Nur Metadaten.** Wird nicht ausgewertet. |
| `hidden` | boolean | Blendet den Agenten im UI-Selektor aus. |
| `disabled` | boolean | `true` überspringt die Datei beim Laden. |
| `mode` | String | `all` (Default), `primary`, `subagent`. Siehe §4. |
| `model` | String | Überschreibt das Session-Modell. **Weglassen** = Default. `model: null` wäre der Literal-String `"null"`. |
| `steps` | int | Max. Modell-Runden pro Prompt. `0` = kein Limit. |
| `maxSteps` | int | Veralteter Alias; `steps` gewinnt, wenn gesetzt. |
| `toolAllow` / `allow` | String[] | Erlaubte Tools oder `*`. Leer = keine Tools. |
| `toolDeny` / `deny` | String[] | Entfernt Tools nach `allow`; deny gewinnt. |
| `tools` | JSON-Objekt | Inline-Alternative: `{"allow": ["read"], "deny": ["shell"]}`. |
| `mcpServers` | String[] | MCP-Servernamen oder `*`. Leer = keine MCP-Tools. |
| `roles` / `rolesAllowed` / `roles-allowed` | String[] | Rollen, die den Agenten nutzen dürfen. |
| `permissions` | Rule[] | Inline-JSON-Array oder Block mit `action`/`resource`/`effect`. |
| Body | Markdown | System-Prompt des Agenten. |

**Default-Regeln**, wenn `permissions` fehlt
(`AgentMarkdownLoader.defaultRules()`):

1. `*` / `*` → `allow`
2. `external_directory` / `*` → `deny`
3. `read` / `*.env` → `ask`
4. `edit` / `*.env` → `deny`
5. `shell` / `ls` → `allow`
6. `shell` / `ls *` → `allow`
7. `shell` / `*` → `ask`
8. `external_directory` / `<plan-dir>/*` → `allow`

---

## 2. Alle internen Tools

Die Liste entspricht den aktuell registrierten `ToolName`-Werten. Ob ein Tool
tatsächlich im Katalog landet, hängt zusätzlich von der Laufzeit-Konfiguration
ab (z. B. Git, E-Mail, MCP).

### Dateisystem und Suche

| Tool | Zweck |
|---|---|
| `read` | Datei lesen |
| `write` | Datei schreiben |
| `edit` | Gezielte Textänderung |
| `patch` | Mehrteiliger Patch |
| `glob` | Dateien per Muster finden |
| `grep` | Inhalt per Muster suchen |

### Ausführung und Interaktion

| Tool | Zweck |
|---|---|
| `shell` | Shell-Befehl ausführen |
| `process` | Prozess-Registry verwalten (nur wenn registriert) |
| `question` | Nutzer interaktiv fragen |
| `todowrite` | Todo-Liste schreiben |

### Web

| Tool | Zweck |
|---|---|
| `webfetch` | HTTP-Inhalt abrufen |
| `websearch` | Websuche (Firecrawl, nur mit Key) |

### Skills und Subagents

| Tool | Zweck |
|---|---|
| `skill` | Skill-Body laden |
| `skill_list` | Verfügbare Skills auflisten |
| `subagent` | Task an Subagent delegieren (sync oder `background=true`) |
| `task` | Background-Task steuern (`status`/`wait`/`cancel`/`list`) |

### Sessions, Worktrees, Modelle

| Tool | Zweck |
|---|---|
| `session_move` | Session in anderes Verzeichnis verschieben |
| `session_rename` | Session umbenennen |
| `worktree_create` | Git-Worktree anlegen (nur bei Git-Workspace) |
| `worktree_list` | Worktrees auflisten |
| `worktree_remove` | Worktree entfernen |
| `models` | Verfügbare Modelle auflisten |

### MCP

| Tool | Zweck |
|---|---|
| `list_mcp_resources` | MCP-Ressourcen auflisten |
| `read_mcp_resource` | MCP-Ressource lesen |
| `<server>_<tool>` | Dynamisch pro konfiguriertem MCP-Server |

### Agenten

| Tool | Zweck |
|---|---|
| `agent_create` | Agent zur Laufzeit definieren |
| `agent_list` | Agenten auflisten |

### E-Mail (nur bei IMAP/SMTP-Konfiguration)

| Tool | Zweck |
|---|---|
| `email_fetch` | E-Mail abrufen |
| `email_read` | E-Mail lesen |
| `email_send` | E-Mail senden |

### Bedingt / spezifiziert

| Tool | Zweck |
|---|---|
| `submit_result` | Strukturiertes Ergebnis bei `responseSchema` |
| `remote.exec`, `remote.read`, `remote.write`, `remote.list`, `remote.stat`, `remote.download`, `remote.upload` | Nur Spezifikation, derzeit nicht implementiert |

---

## 3. Permission-Actions und Effects

`action` ist ein Tool-Name oder `*`. Zusätzlich kennt die Engine die
Pseudo-Action `external_directory` für Pfade außerhalb des Workspace.

Vollständige Action-Liste (entspricht den Tool-Namen plus Pseudo-Action):

```text
*  external_directory
read  write  edit  patch  glob  grep
shell  process  question  todowrite
webfetch  websearch
skill  skill_list  subagent  task
session_move  session_rename
worktree_create  worktree_list  worktree_remove
models  list_mcp_resources  read_mcp_resource
agent_create  agent_list
email_fetch  email_read  email_send
submit_result
```

Effects:

| Effect | Bedeutung |
|---|---|
| `allow` | Erlaubt |
| `deny` | Verweigert; blockiert die Operation |
| `ask` | Fragt den Nutzer (bzw. `AskMode`) |

`resource` ist ein Glob-Muster (`*`, `**`, `*.env`, `pfad/*`).

---

## 4. Kombinationen

### Mode

| `mode` | Haupt-Agent | Subagent | Besonderheit |
|---|---|---|---|
| `all` | ja | ja | Default; erbt globalen `AskMode` |
| `primary` | ja | nein | `AgentCatalogue.isSubagent()` = `false` |
| `subagent` | nein | ja | erzwingt `AskMode.DENY` (keine Fragen) |

Fehlt eine Config, gelten `build` und `plan` als primär
(`isSubagent()`-Fallback). **Plan-Modus** wird automatisch erkannt, wenn
`name: plan` gesetzt ist **oder** alle `edit`-Regeln global `deny` sind und
kein `edit`-`allow` existiert (`AgentProfile.forAgent`).

### Tools

| Kombination | Wirkung |
|---|---|
| `toolAllow: ["*"]`, `toolDeny: []` | alle Tools |
| `toolAllow: []` | keine Tools |
| `toolAllow: ["read","glob","grep"]` | nur Lesen/Suchen |
| `toolAllow: ["*"]`, `toolDeny: ["shell","write","edit"]` | alles außer Schreiben/Shell |
| `tools: {"allow":["*"],"deny":["shell"]}` | Inline-JSON-Variante |

### Permissions

| Muster | Regeln |
|---|---|
| Allow-all | `*`/`*` → `allow` |
| Deny-all | `*`/`*` → `deny` |
| Ask-all | `*`/`*` → `ask` |
| Least-Privilege | `*`/`*` → `deny`, dann `read`/`*` → `allow` |
| Plan | `edit`/`*` → `deny`, optional `edit`/`<plan-dir>/*` → `allow` |
| Shell nur lesend | `shell`/`*` → `deny`, dann `shell`/`git *` → `allow` |
| Env schützen | `read`/`*.env` → `ask`, `edit`/`*.env` → `deny` |
| Extern sperren | `external_directory`/`*` → `deny` |

Merksatz: **letzte passende Regel gewinnt**, aber ein `DENY` blockiert die
Operation vorher. Kein Treffer = `ASK`.

### MCP

| Kombination | Wirkung |
|---|---|
| `mcpServers: ["*"]` | alle Server |
| `mcpServers: []` | keine MCP-Tools |
| `mcpServers: ["playwright","firecrawl"]` | nur diese Server |

### Rollen

| Kombination | Wirkung |
|---|---|
| `roles: ["*"]` | alle |
| `roles: ["admin"]` | nur Admin |
| `roles: ["qoc-user","qoc-admin"]` | mehrere Rollen |
| Feld weglassen | `build`/`plan` → `*`, sonst `admin` |

### Steps

| Wert | Wirkung |
|---|---|
| `steps: 0` oder weglassen | kein Limit |
| `steps: 5` | max. 5 Modell-Runden |
| `steps: 20` | längerer Lauf |
| `maxSteps: 5` | Alias, falls `steps` fehlt |

### Hidden / Disabled

| Feld | Kombination | Wirkung |
|---|---|---|
| `hidden` | `true` | nicht im UI-Selektor |
| `hidden` | `false` | sichtbar |
| `disabled` | `true` | Datei wird übersprungen |
| `disabled` | `false` | normal geladen |

---

## 5. Skills (nur in `settings.json`)

Der Markdown-Loader liest **kein** `skills`-Objekt. Skills werden pro Agent in
`settings.json`/`settings.jsonc` konfiguriert (`EngineConfig.parseAgentSkills`):

```json
{
  "agents": [
    {
      "name": "build",
      "skills": {
        "enabled": true,
        "allow": ["code-review", "tdd"],
        "deny": ["deploy-*"],
        "max": 8,
        "prompt": "top-k",
        "core": ["code-review"],
        "filter": {
          "enabled": true,
          "strategy": "bm25",
          "topK": 5,
          "minScore": 0.3,
          "thresholdCount": 10,
          "alwaysInclude": ["code-review"]
        }
      }
    }
  ]
}
```

| Feld | Werte | Bedeutung |
|---|---|---|
| `enabled` | boolean | Skills für diesen Agenten aktiv |
| `allow` | String[] | sichtbare/ladbare Skill-IDs oder Muster |
| `deny` | String[] | versteckt und blockiert; deny gewinnt |
| `max` | int | Cap für den Skill-Block; `0` = unbegrenzt |
| `prompt` | `none`, `core`, `top-k`, `all` | Umfang des Skill-Blocks |
| `core` | String[] | Muster für `prompt: core` |
| `filter.enabled` | boolean | Relevanzfilter an/aus |
| `filter.strategy` | String | Default `bm25` |
| `filter.topK` | int | Anzahl der Top-Treffer (≥ 1) |
| `filter.minScore` | double | Mindestscore (≥ 0) |
| `filter.thresholdCount` | int | Ab dieser Kataloggröße filtern |
| `filter.alwaysInclude` | String[] | immer einbeziehen |

Ein **globales** `skills`-Objekt (ohne `agents[]`) ist ebenfalls möglich; es
setzt die Defaults für alle Agenten.

---

## 6. Äquivalentes `settings.json`-Agent-Objekt

Derselbe Agent als JSON (praktisch für serverless/inline, siehe
`EngineConfig.agentFromJson`):

```json
{
  "agents": [
    {
      "name": "example-full",
      "system": "You are a fully configured example agent.",
      "hidden": false,
      "mode": "all",
      "model": "openai/gpt-4o-mini",
      "steps": 0,
      "tools": { "allow": ["*"], "deny": [] },
      "permissions": [
        { "action": "*", "resource": "*", "effect": "allow" },
        { "action": "external_directory", "resource": "*", "effect": "deny" },
        { "action": "read", "resource": "*.env", "effect": "ask" },
        { "action": "edit", "resource": "*.env", "effect": "deny" },
        { "action": "shell", "resource": "*", "effect": "ask" }
      ],
      "mcpServers": ["*"],
      "roles": ["*"]
    }
  ]
}
```

---

## 7. Fundort und Ladereihenfolge

Der Loader durchsucht:

1. `~/.config/opencode/agents` (global)
2. Projektverzeichnisse `.opencode/agents` – vom Workspace aufwärts bis `/`

Alle `.md`-Dateien werden rekursiv geladen. Der Agent-Name ist der relative
Pfad ohne `.md`, sofern kein `name` gesetzt ist (z. B. `stack/reviewer.md` →
`stack/reviewer`). Projekt-Agenten überschreiben gleichnamige globale Agenten;
`build` und `plan` werden als Baseline ergänzt, falls sie fehlen.

---

## 8. Kurzreferenz

```text
mode:        all | primary | subagent
tools:       "*" | [] | ["read","glob","grep"]
deny:        [] | ["shell"] | ["write","edit"]
permissions: allow-all | deny-all | ask-all | granular
effects:     allow | deny | ask
mcp:         ["*"] | ["playwright","firecrawl"] | []
roles:       ["*"] | ["admin"] | ["qoc-user","qoc-admin"]
steps:       0 (unbegrenzt) | 5 | 10 | 20 | 50
skills:      nur settings.json (enabled, allow, deny, max, prompt, core, filter)
hidden:      true | false
disabled:    true | false
```
