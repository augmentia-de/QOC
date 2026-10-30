# Skill Loading in QOC

> **Status:** Implemented · 2026-10-30
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus)
> **Related:** [`Understand.md`](./Understand.md), [`context-build.md`](./context-build.md),
> [`optimize_prompt.md`](./optimize_prompt.md), [`spec/betrieb/02-skills.md`](./spec/betrieb/02-skills.md)

---

## 1. Overview

A skill is text with metadata. The model sees only ID, name and description in
the system prompt and loads the body on demand via the `skill` tool. Nothing is
executed.

Skills are configured on two levels:

| Level | Scope | Config |
|---|---|---|
| Global | all agents | `skills.*` in `settings.json` |
| Per agent | one agent | `agents[].skills` |

Permissions remain the hard boundary. Neither level can grant a skill that a
`permission` rule denies.

---

## 2. Discovery

The engine scans these roots in order (`Engine.java:371–385`):

| Root | Source |
|---|---|
| `<workspace>/skill` | project |
| `<workspace>/skills` | project |
| `<workspace>/.opencode/skills` | project |
| `skills.paths[]` | configuration, absolute or relative |

Later roots win on ID collisions (SK-05). `skills.enabled=false` yields an
empty catalog.

`SkillScanner` rules:

| Rule | Value |
|---|---|
| maximum depth | 6 |
| maximum files per skill | 10 |
| directory with `SKILL.md` | one skill, no recursion below it |
| flat `*.md` | only at the root level |
| invalid skill | logged, skipped |

`SkillParser` reads the frontmatter:

```yaml
---
name: deploy
description: Use when the user asks to deploy.
metadata:
  opencode/autoinvoke: true
disable-model-invocation: false
---
```

- `name` defaults to the ID.
- `description` is the only text the model sees beforehand.
- `description` ≤ 500 characters, body ≤ 8 KiB.
- `metadata.opencode/autoinvoke: false` or `disable-model-invocation: true`
  hides the skill from the prompt.

---

## 3. Visibility

A skill is **listed** when it has a description and `autoinvoke` is not false
(`Skill.java:27–29`):

```java
public boolean listed() {
    return !description.isBlank() && autoinvoke;
}
```

Hidden skills stay loadable via `skill(id)` and appear in `skill_list` with
`(hidden)`.

---

## 4. Global configuration

```json
{
  "skills": {
    "enabled": true,
    "paths": [".agents/skills"],
    "allow": ["aisdlc-*"],
    "deny": ["experimental-*"],
    "max": 30,
    "filter": {
      "enabled": true,
      "strategy": "bm25",
      "topK": 12,
      "minScore": 0.25,
      "thresholdCount": 20,
      "alwaysInclude": ["code-review"]
    }
  }
}
```

| Field | Meaning |
|---|---|
| `enabled` | `false` disables discovery completely |
| `paths` | additional roots, later wins |
| `allow` | empty = all; wildcard patterns |
| `deny` | wins over `allow` |
| `max` | caps loaded skills, `0` = unlimited; applied after sorting by ID |
| `filter` | BM25 options for prompt injection |

`skills.max` is **alphabetical**, not relevance-based. With active filtering,
prefer a per-agent `max` instead.

---

## 5. Per-agent configuration

```json
{
  "agents": [
    {
      "name": "plan",
      "tools": { "allow": ["read", "glob", "grep", "list", "skill", "skill_list"], "deny": [] },
      "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
      "skills": {
        "allow": ["plan-*", "review"],
        "deny": ["deploy-*"],
        "max": 8,
        "prompt": "top-k",
        "core": ["review"],
        "filter": {
          "topK": 5,
          "minScore": 0.3,
          "thresholdCount": 10,
          "alwaysInclude": ["review"]
        }
      }
    }
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `enabled` | boolean | `false` disables the block; default `true` when the block exists |
| `allow` | string[] | empty = all; only matching IDs are visible and loadable |
| `deny` | string[] | wins over `allow` |
| `max` | int | cap for the prompt block, `0` = unlimited |
| `prompt` | string | `none`, `core`, `top-k` (default), `all` |
| `core` | string[] | patterns used by `prompt: core` |
| `filter` | object | per-agent override of the global `skills.filter` |

### 5.1 Semantics

- `allow`/`deny` are compiled into `permission` rules with action `skill`:
  - `allow` non-empty → `deny *`, then one `allow <pattern>` per entry.
  - `deny` → `deny <pattern>`.
  - The last matching rule wins, so a later `allow` can override an earlier
    `deny` and vice versa.
- The rules are applied to:
  - the `<available_skills>` block,
  - `skill(id)` (load),
  - `skill_list`.
- `prompt` modes:
  - `none` – no skill block; the `skill` tool still works.
  - `core` – only `core` patterns (after permissions).
  - `top-k` – BM25 selection with the per-agent or global filter.
  - `all` – every permitted, listed skill.
- `max` caps the selected list after ranking.

### 5.2 Interaction with permissions

The effective rule chain is:

```
agent skill allow/deny  →  agent permissions  →  session permissions
```

Deny still wins whenever the final matching rule is a deny. A per-agent
`skills.allow` cannot grant a skill that an agent permission or session
permission denies.

`ASK` is enforced by the normal permission gate before the tool executes.

### 5.3 `tools.allow` vs. skills

`tools.allow` controls which tools exist, not which skills are listed. If
`tools.allow` omits `skill`, the model can still see the skill block but cannot
load a skill. Configure both:

```json
"tools": { "allow": ["read", "grep", "glob", "list", "skill", "skill_list"], "deny": [] }
```

An empty or missing `tools.allow` means **no tools** (AG-03).

---

## 6. Prompt injection

`ContextBuilder.prepare(...)` builds the system blocks in this order:

1. base prompt or `agent.system`
2. project instructions (AGENTS.md etc.)
3. skill block (`<available_skills>`)
4. compaction checkpoint
5. history

With a `SkillSelection`, `SkillCatalog.select(...)` applies:

1. `listed(rules)` – description, autoinvoke, permissions,
2. mode (`NONE`, `CORE`, `TOP_K`, `ALL`),
3. `max` cap,
4. render as `<available_skills>` sorted by ID.

For `TOP_K`, `SkillCatalog.relevant(...)`:

- uses BM25 over ID, name and description,
- keeps `topK` results above `minScore`,
- adds `alwaysInclude`,
- falls back to all listed skills when nothing matches.

Without a per-agent block the previous behaviour is unchanged: the engine uses
the global filter with the user prompt as query.

---

## 7. On-demand loading

| Tool | Behaviour | Permission |
|---|---|---|
| `skill(id)` | returns `<skill_content>` with body, base directory and up to 10 files | `skill` / skill ID |
| `skill_list` | lists every catalog entry, including hidden ones | `skill` / `*` |

`SkillAuthorizer` maps the permission resource to the skill ID so rules such as
`{"action":"skill","resource":"deploy-*","effect":"deny"}` work.

Permissions are checked before the executor. The `DENY` check inside
`SkillTool` is defense in depth.

---

## 8. Reload

There is no file watcher. After changing `settings.json` or skills:

```bash
curl -X POST http://localhost:8081/api/config/reload
```

`Engine.reloadConfig()` (`Engine.java:1484`):

1. reloads `settings.json`,
2. reloads the agent catalogue,
3. re-scans all skill roots,
4. rebuilds the `SkillCatalog`,
5. rebuilds the `ContextBuilder`.

The next prompt sees the new catalog. Running sessions keep their history.

---

## 9. Limitations

| Limitation | Impact | Workaround |
|---|---|---|
| No per-agent skill roots | all agents share `skills.paths` | separate workspaces or global `deny` |
| Global `skills.max` is alphabetical | may drop relevant skills | use per-agent `max` with `top-k` |
| `tools.deny: ["skill"]` does not hide the prompt block | model sees skills it cannot load | also add a `skill` permission deny |
| No file watcher | skill edits need a reload | `POST /api/config/reload` |
| `UserItem.skills` not wired | prompt-side skill attachments are spec-only | use the `skill` tool |
| `skills.filter` global default | shared by agents without their own block | per-agent `filter` |

---

## 10. Examples

### Plan agent: no deploy/release skills

```json
{
  "agents": [{
    "name": "plan",
    "tools": { "allow": ["read", "glob", "grep", "list", "skill", "skill_list"], "deny": ["write", "edit", "patch", "shell"] },
    "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
    "skills": {
      "deny": ["deploy-*", "release-*"],
      "prompt": "top-k",
      "max": 6
    }
  }]
}
```

### Build agent: small curated set

```json
{
  "agents": [{
    "name": "build",
    "tools": { "allow": ["*"], "deny": [] },
    "permissions": [{ "action": "*", "resource": "*", "effect": "allow" }],
    "skills": {
      "allow": ["code-review", "tdd", "security-*"],
      "prompt": "core",
      "core": ["code-review"],
      "max": 5
    }
  }]
}
```

### No skill block, tool still available

```json
{"name": "router", "skills": {"prompt": "none"}}
```

The model sees no `<available_skills>` block but can still call `skill(id)` if
it knows the ID.

---

## 11. Code map

| Concern | File |
|---|---|
| Discovery | `engine/src/main/java/de/augmentia/oc/engine/runtime/skill/SkillScanner.java` |
| Parsing | `engine/src/main/java/de/augmentia/oc/engine/runtime/skill/SkillParser.java` |
| Visibility | `engine/src/main/java/de/augmentia/oc/engine/runtime/skill/Skill.java` |
| Catalog, selection, rendering | `engine/src/main/java/de/augmentia/oc/engine/runtime/skill/SkillCatalog.java` |
| BM25 | `engine/src/main/java/de/augmentia/oc/engine/runtime/skill/Bm25SkillRanker.java` |
| Filter options | `engine/src/main/java/de/augmentia/oc/engine/runtime/skill/SkillFilterOptions.java` |
| Selection model | `engine/src/main/java/de/augmentia/oc/engine/runtime/skill/SkillSelection.java` |
| Prompt injection | `engine/src/main/java/de/augmentia/oc/engine/runtime/context/ContextBuilder.java` |
| Load/list tools | `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/skill/SkillTool.java` |
| Permission mapping | `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/skill/SkillAuthorizer.java` |
| Config | `quarkus/src/main/java/de/augmentia/oc/engine/http/EngineConfig.java` |
| Wiring | `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java` |

---

## 12. Tests

| Test | Covers |
|---|---|
| `SkillCatalogTest` | visibility, permissions, BM25, `SkillSelection` modes, `max` |
| `ContextBuilderTest` | prompt block, query filtering, `NONE`/`CORE` selection |
| `EngineConfigTest` | global and per-agent skill configuration |
| `EngineAgentProfileTest` | per-agent allow/deny compiled into permission rules |
| `EngineConfigTest` | prompt normalization, defaults |
