# Agent-Def-Example.md — complete agent definition

This file is both a reference **and** a runnable example. It shows all fields
that the Markdown agent loader currently evaluates, all internal tools, and the
most important combinations.

Sources in code:

- `quarkus/src/main/java/de/augmentia/oc/engine/http/AgentMarkdownLoader.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/EngineConfig.java` (`AgentConfig`, `AgentSkillsConfig`)
- `quarkus/src/main/java/de/augmentia/oc/engine/http/AgentCatalogue.java` (`isSubagent`)
- `quarkus/src/main/java/de/augmentia/oc/engine/core/eval/PermissionRules.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/runtime/context/AgentProfile.java`

> **Important — parser limitations.** The frontmatter parser is deliberately
> minimal and **not a YAML parser**:
>
> - Comments (`# …`) only on **their own lines**; **no inline comment**
>   after a value (`mode: all  # x` would corrupt the value).
> - Lists only as blocks (`- value`) or as inline JSON (`["a", "b"]`).
> - No nested YAML objects. The `skills` object is therefore **not**
>   read from the frontmatter (see §5).
> - Comments inside a block list or a `permissions` block can invalidate the
>   list. Place comments only between complete key blocks.

---

## 1. Complete example (Markdown frontmatter)

Stored e.g. under `.opencode/agents/example-full.md`. The body after the
closing `---` is the system prompt.

```markdown
---
# ===========================================================================
# 1. IDENTITY
# ===========================================================================
name: example-full
description: Complete example with all supported fields.
color: "#16a085"
hidden: false
disabled: false
mode: all
model: openai/gpt-4o-mini
steps: 0
maxSteps: 0

# ===========================================================================
# 2. TOOLS
# toolAllow: tool names or "*"; empty means no tools.
# toolDeny:  removed after allow; deny always wins.
# All internal tools are listed in section 2 of this file.
# ===========================================================================
toolAllow:
  - "*"
toolDeny: []

# ===========================================================================
# 3. MCP SERVERS
# "*" = all configured servers; empty list = no MCP tools.
# ===========================================================================
mcpServers:
  - "*"

# ===========================================================================
# 4. ROLES (authorization)
# "*" = all roles. Without a setting: build/plan = "*", otherwise "admin".
# ===========================================================================
roles:
  - "*"

# ===========================================================================
# 5. PERMISSIONS
# Order: agent rules -> session rules -> stored approvals.
# Within the rules, the LAST matching rule wins.
# A DENY of any resource blocks the whole operation.
# No match = ASK. ASK beats ALLOW.
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

### Notes on individual fields

| Field | Type | Effect |
|---|---|---|
| `name` | String | Unique name. If missing, the relative filename without `.md` is used. |
| `description` | String | **Metadata only.** Read by the loader but not carried into `AgentConfig`. |
| `color` | String | **Metadata only.** Not evaluated. |
| `hidden` | boolean | Hides the agent in the UI selector. |
| `disabled` | boolean | `true` skips the file when loading. |
| `mode` | String | `all` (default), `primary`, `subagent`. See §4. |
| `model` | String | Overrides the session model. **Omit** = default. `model: null` would be the literal string `"null"`. |
| `steps` | int | Max. model rounds per prompt. `0` = no limit. |
| `maxSteps` | int | Deprecated alias; `steps` wins if set. |
| `toolAllow` / `allow` | String[] | Allowed tools or `*`. Empty = no tools. |
| `toolDeny` / `deny` | String[] | Removes tools after `allow`; deny wins. |
| `tools` | JSON object | Inline alternative: `{"allow": ["read"], "deny": ["shell"]}`. |
| `mcpServers` | String[] | MCP server names or `*`. Empty = no MCP tools. |
| `roles` / `rolesAllowed` / `roles-allowed` | String[] | Roles permitted to use the agent. |
| `permissions` | Rule[] | Inline JSON array or block with `action`/`resource`/`effect`. |
| Body | Markdown | System prompt of the agent. |

**Default rules** when `permissions` is missing
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

## 2. All internal tools

The list corresponds to the currently registered `ToolName` values. Whether a
tool actually ends up in the catalog additionally depends on runtime
configuration (e.g. Git, E-Mail, MCP).

### Filesystem and search

| Tool | Purpose |
|---|---|
| `read` | Read file |
| `write` | Write file |
| `edit` | Targeted text change |
| `patch` | Multi-part patch |
| `glob` | Find files by pattern |
| `grep` | Search content by pattern |

### Execution and interaction

| Tool | Purpose |
|---|---|
| `shell` | Execute shell command |
| `process` | Manage process registry (only if registered) |
| `question` | Ask user interactively |
| `todowrite` | Write todo list |

### Web

| Tool | Purpose |
|---|---|
| `webfetch` | Fetch HTTP content |
| `websearch` | Web search (Firecrawl, only with key) |

### Skills and subagents

| Tool | Purpose |
|---|---|
| `skill` | Load skill body |
| `skill_list` | List available skills |
| `subagent` | Delegate task to subagent (sync or `background=true`) |
| `task` | Control background task (`status`/`wait`/`cancel`/`list`) |

### Sessions, worktrees, models

| Tool | Purpose |
|---|---|
| `session_move` | Move session to another directory |
| `session_rename` | Rename session |
| `worktree_create` | Create git worktree (only with Git workspace) |
| `worktree_list` | List worktrees |
| `worktree_remove` | Remove worktree |
| `models` | List available models |

### MCP

| Tool | Purpose |
|---|---|
| `list_mcp_resources` | List MCP resources |
| `read_mcp_resource` | Read MCP resource |
| `<server>_<tool>` | Dynamically per configured MCP server |

### Agents

| Tool | Purpose |
|---|---|
| `agent_create` | Define agent at runtime |
| `agent_list` | List agents |

### E-mail (only with IMAP/SMTP configuration)

| Tool | Purpose |
|---|---|
| `email_fetch` | Fetch e-mail |
| `email_read` | Read e-mail |
| `email_send` | Send e-mail |

### Conditional / specified

| Tool | Purpose |
|---|---|
| `submit_result` | Structured result with `responseSchema` |
| `remote.exec`, `remote.read`, `remote.write`, `remote.list`, `remote.stat`, `remote.download`, `remote.upload` | Specification only, not currently implemented |

---

## 3. Permission actions and effects

`action` is a tool name or `*`. Additionally, the engine knows the
pseudo-action `external_directory` for paths outside the workspace.

Complete action list (matches tool names plus pseudo-action):

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

| Effect | Meaning |
|---|---|
| `allow` | Allowed |
| `deny` | Denied; blocks the operation |
| `ask` | Asks the user (or `AskMode`) |

`resource` is a glob pattern (`*`, `**`, `*.env`, `path/*`).

---

## 4. Combinations

### Mode

| `mode` | Primary agent | Subagent | Special note |
|---|---|---|---|
| `all` | yes | yes | Default; inherits global `AskMode` |
| `primary` | yes | no | `AgentCatalogue.isSubagent()` = `false` |
| `subagent` | no | yes | forces `AskMode.DENY` (no questions) |

If a config is missing, `build` and `plan` are treated as primary
(`isSubagent()` fallback). **Plan mode** is detected automatically when
`name: plan` is set **or** all `edit` rules are globally `deny` and no
`edit`-`allow` exists (`AgentProfile.forAgent`).

### Tools

| Combination | Effect |
|---|---|
| `toolAllow: ["*"]`, `toolDeny: []` | all tools |
| `toolAllow: []` | no tools |
| `toolAllow: ["read","glob","grep"]` | read/search only |
| `toolAllow: ["*"]`, `toolDeny: ["shell","write","edit"]` | everything except write/shell |
| `tools: {"allow":["*"],"deny":["shell"]}` | inline JSON variant |

### Permissions

| Pattern | Rules |
|---|---|
| Allow-all | `*`/`*` → `allow` |
| Deny-all | `*`/`*` → `deny` |
| Ask-all | `*`/`*` → `ask` |
| Least-privilege | `*`/`*` → `deny`, then `read`/`*` → `allow` |
| Plan | `edit`/`*` → `deny`, optionally `edit`/`<plan-dir>/*` → `allow` |
| Shell read-only | `shell`/`*` → `deny`, then `shell`/`git *` → `allow` |
| Protect env | `read`/`*.env` → `ask`, `edit`/`*.env` → `deny` |
| Lock external | `external_directory`/`*` → `deny` |

Remember: **last matching rule wins**, but a `DENY` blocks the operation
beforehand. No match = `ASK`.

### MCP

| Combination | Effect |
|---|---|
| `mcpServers: ["*"]` | all servers |
| `mcpServers: []` | no MCP tools |
| `mcpServers: ["playwright","firecrawl"]` | only these servers |

### Roles

| Combination | Effect |
|---|---|
| `roles: ["*"]` | all |
| `roles: ["admin"]` | admin only |
| `roles: ["qoc-user","qoc-admin"]` | several roles |
| Omit field | `build`/`plan` → `*`, otherwise `admin` |

### Steps

| Value | Effect |
|---|---|
| `steps: 0` or omitted | no limit |
| `steps: 5` | max. 5 model rounds |
| `steps: 20` | longer run |
| `maxSteps: 5` | alias if `steps` missing |

### Hidden / Disabled

| Field | Combination | Effect |
|---|---|---|
| `hidden` | `true` | not in UI selector |
| `hidden` | `false` | visible |
| `disabled` | `true` | file is skipped |
| `disabled` | `false` | loaded normally |

---

## 5. Skills (only in `settings.json`)

The Markdown loader reads **no** `skills` object. Skills are configured per
agent in `settings.json`/`settings.jsonc` (`EngineConfig.parseAgentSkills`):

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

| Field | Values | Meaning |
|---|---|---|
| `enabled` | boolean | Skills active for this agent |
| `allow` | String[] | visible/loadable skill IDs or patterns |
| `deny` | String[] | hidden and blocked; deny wins |
| `max` | int | Cap for the skill block; `0` = unlimited |
| `prompt` | `none`, `core`, `top-k`, `all` | Scope of the skill block |
| `core` | String[] | Patterns for `prompt: core` |
| `filter.enabled` | boolean | Relevance filter on/off |
| `filter.strategy` | String | Default `bm25` |
| `filter.topK` | int | Number of top hits (≥ 1) |
| `filter.minScore` | double | Minimum score (≥ 0) |
| `filter.thresholdCount` | int | Filter from this catalog size |
| `filter.alwaysInclude` | String[] | always include |

A **global** `skills` object (without `agents[]`) is also possible; it sets
defaults for all agents.

---

## 6. Equivalent `settings.json` agent object

The same agent as JSON (practical for serverless/inline, see
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

## 7. Location and load order

The loader searches:

1. `~/.config/opencode/agents` (global)
2. Project directories `.opencode/agents` — from the workspace upward to `/`

All `.md` files are loaded recursively. The agent name is the relative path
without `.md`, unless `name` is set (e.g. `stack/reviewer.md` →
`stack/reviewer`). Project agents override global agents of the same name;
`build` and `plan` are added as a baseline if missing.

---

## 8. Quick reference

```text
mode:        all | primary | subagent
tools:       "*" | [] | ["read","glob","grep"]
deny:        [] | ["shell"] | ["write","edit"]
permissions: allow-all | deny-all | ask-all | granular
effects:     allow | deny | ask
mcp:         ["*"] | ["playwright","firecrawl"] | []
roles:       ["*"] | ["admin"] | ["qoc-user","qoc-admin"]
steps:       0 (unlimited) | 5 | 10 | 20 | 50
skills:      only settings.json (enabled, allow, deny, max, prompt, core, filter)
hidden:      true | false
disabled:    true | false
```
