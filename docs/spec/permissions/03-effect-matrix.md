# 03 – Effect matrix

> Which actions exist, which resource they carry, where the boundaries
> lie – and what distinguishes “catalog visibility" from “authorization".

## 1. Action names

| Action | Tool / family | Resource | `save` suggestion |
|---|---|---|---|
| `read` | `read` | location-relative path | `["*"]` |
| `edit` | `write`, `edit`, `patch` | target path | `["*"]` |
| `glob` | `glob` | the **pattern** | `["*"]` |
| `grep` | `grep` | the **regex** (not the path!) | `["*"]` |
| `shell` | `shell` | the **scanned command string** | `["<prefix> *"]` per command |
| `external_directory` | filesystem, shell | `<directory>/*` | `<project root>/*` |
| `subagent` | `subagent` | target agent ID | `["*"]` |
| `skill` | `skill` | skill ID | `["*"]` |
| `question` | `question` | `*` | `["*"]` |
| `webfetch` | `webfetch` | the URL | `["*"]` |
| `websearch` | `websearch` | the search query | `["*"]` |
| `execute` | Code Mode | `*` | `["*"]` |
| `<server>_<tool>` | MCP tools | `*` | `["*"]` |
| `opencode_list_mcp_resources` | MCP resources | `*` | `["*"]` |
| `opencode_read_mcp_resource` | MCP resources | `*` | `["*"]` |

> **The list is deliberately incomplete** – `action` is a free string.
> The table describes the **built-in** actions, not the allowed ones.

> **`edit` for three tools:** `write`, `edit` and `patch` share an
> action. Anyone writing a rule for `edit` means all three. A tool that
> needs a **own** action (because it does something different) declares
> it – `write` does not, because it has the same effect.

## 2. V1 renames

When an old configuration is migrated:

| Old action | New action |
|---|---|
| `write` | `edit` |
| `patch` | `edit` |
| `task` | `subagent` |
| `bash` | `shell` |

> `doom_loop` and `lsp` are **no** V2 actions anymore – they exist only
> in the legacy configuration.

## 3. Default rules per agent

```
{action: "*",       resource: "*",         effect: Allow}
{action: "read",    resource: "*.env",     effect: Ask}
{action: "read",    resource: "*.env.*",   effect: Ask}
{action: "read",    resource: "*.env.example", effect: Allow}
```

| Rule | Meaning |
|---|---|
| `*:*=allow` | default: everything allowed. The permission check is **user-friendly**, not restrictive. |
| `*.env` = ask | secrets are **asked about**. |
| `*.env.*` = ask | variants too (`prod.env`, `.env.local`). |
| `*.env.example` = allow | templates are **public** – no prompt. |

> **Why `*.env` is a pattern and not a path:** the file can lie at any
> depth. `*.env` matches, because of anchoring, only files whose **name**
> ends in `.env` – at any directory depth.

### Additional rules of built-in agents

| Agent | Addition |
|---|---|
| `build` | `question:* = allow` (questions are allowed) |
| `plan` | `edit` **denied** (exception: `~/.opencode/plan/*`), `question:* = allow` |
| `explore` | `*` denied, exception: `shell`, `grep`, `glob`, `webfetch`, `websearch`, `read`; `*.env` = **ask** |
| `general` | `question` and `subagent` denied |
| `title`, `summary` | `*` denied |

> **`plan` is the only agent with a real write lock** – and it is
> **deliberate**: in plan mode nothing is changed. The exception for
> `~/.opencode/plan/*` allows writing the plan notes.

## 4. Catalog visibility ≠ authorization

```
Level 1 – CATALOG:
  rule {action: <toolname or permission>, resource: "*", effect: Deny}
  → the tool disappears from the offer (and from the Code Mode catalog)

Level 2 – DECISION:
  the full rule chain (agent ++ session ++ stored ++ policy)

Level 3 – EXECUTION:
  the leaf checks itself (Permission.assert)
```

> **Why three levels are needed:**
>
> * **Level 1** is a *filter*: the tool should not even be offered (it
>   costs context and confuses the model).
> * **Level 2** is the *decision*: the actual question “may this run now?"
> * **Level 3** is the *safeguard*: even if a call somehow passes level 2,
>   the leaf checks again.
>
> **Level 1 alone is not enough** – it is only visibility. **Level 2
> alone is not enough either** – a tool that is never offered cannot be
> executed, but an *offered* tool with `deny` at level 2 must be able to
> **tell** the model about the denial.

### Code mode

```
When {action: "execute", resource: "*", effect: Deny}:
  → the Code Mode tool disappears
  → and with it ALL tools reachable only through Code Mode
     (including MCP tools of servers without codemode:false)
  → the nested tools keep their own rules
```

> **This is a cascade, not a chain.** An MCP server in Code Mode
> disappears **as a whole** – its tools are reachable through Code Mode,
> and Code Mode is off.

## 5. The sandbox boundary (directory boundary)

```
internal  = path lies inside location.directory
         OR inside the non-root project worktree

external  = everything else
```

| Property | Value |
|---|---|
| Order | `external_directory` **before** `read`/`edit` |
| Resource of the boundary | `<directory>/*` |
| `save` suggestion of the boundary | `<project root>/*` |
| Default effect | **per allow-list** – access only to the global working directory and the configured additional folders (each with Read/Write flag) |

> **Decision (2026-10-22, O-07).** There is a globally defined working
> directory (default: current directory if unset) plus a configurable
> allow-list of additional folders, each with Read and/or Write flags.
> Accesses outside this set are **denied** (no silent success, no
> implicit `allow`). This variant replaces the former “ask"/“allow"
> model; the reference `allow` default
> (`packages/schema/src/agent.ts:47`) no longer applies. Configuration
> happens through the configuration schema (L-2).

> **Mnemonic:** The directory boundary is **not a sandbox**. It is an
> additional question in the permission process. Whoever works outside
> the location is not “locked in" – depending on configuration they are
> simply not asked.

## 6. Extensions affecting permissions

| Extension | Intervention point | What it may do |
|---|---|---|
| **Plugin hook** `permission.evaluate` | after the rule chain | change effect **and** message |
| **Policy plugin** | four points | remove providers/MCP/skills from the catalog; enforce `tool.use` |
| **Tool author** | at registration | set a `permission` option (only for level 1) |
| **HITL provider** | in the `ask` branch | obtain the answer (console, UI, ticket) |

> **What extensions may NOT do:** add, remove or override policy
> statements. The organization rules are protected against change
> (`packages/core/src/plugin/internal.ts:277-279`).

## 7. Environment variables

| Variable | Effect |
|---|---|
| `OPENCODE_PASSWORD` / `OPENCODE_SERVER_PASSWORD` | server authentication – **no** permissions |
| `--auto` (CLI) | answer all permissions automatically (foreground) |
| `--dangerously-skip-permissions` (V1) | legacy name for the same |

> **There is no environment variable for permissions themselves.** Anyone
> wanting to set permissions via the environment uses the configuration.

## 8. Verified semantics (reference checkout)

| Concept | Location |
|---|---|
| Default rules per agent | `packages/schema/src/agent.ts:46-51` |
| Agent additional rules (`build`, `plan`, `explore`) | `packages/core/src/plugin/agent.ts:56-116`, `plugin/plan.ts:34-41` |
| Action names of the tools | `packages/core/src/tool/plugin/*.ts` (`export const name`) |
| `edit` for `write`/`edit`/`patch` | `packages/core/src/tool/AGENTS.md` |
| `external_directory` (boundary, order) | `packages/core/src/file-access.ts:93-161` |
| Action list (Code Mode cascade) | `packages/core/src/tool.ts:239`, `:293-296` |
| V1 renames | `packages/core/src/v1/config/migrate.ts:28-33` |
| `save` suggestion per tool | `packages/core/src/shell/parse.ts:195-199`, `packages/core/src/tool/plugin/write.ts:78-86` |