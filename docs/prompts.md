# Engine Prompt Files

> **Purpose:** Overview of all prompt resources (`engine/src/main/resources/prompts/`),
> their role, trigger, and influence — plus analysis of how they can become less
> developer-specific and more dynamic for different purposes.
>
> Detailed documentation: `promptlib.md` (root, checked against the code) and
> `docs/spec/session-engine/13-internal-prompts.md`.

---

## 1. Loading Mechanism

- `PromptLoader.get(id)` loads `/prompts/<id>.txt` from the classpath, caches the
  result, and throws an `IllegalArgumentException` if a prompt is missing
  (defect, no fallback).
- `PromptLoader.render(id, vars)` replaces `{key}` placeholders.
- `stripTrailing()` is applied on load.
- There is no `manifest.json`, no version, and no checksum.
  The identifier is the file name; Golden tests lock down the texts.

---

## 2. System Part (Level 1)

| File | ID | Trigger | Role |
|---|---|---|---|
| `base-prompt.txt` | `SYS-BASE` | Every request without `agent.system` | Base system prompt: role ("OpenCode session engine"), rules, tool list via `${tools}`. Defines the base behavior. |
| `sys-guide.txt` | `SYS-GUIDE` | At least one of `shell`, `write`, `edit` in the snapshot | Tool hints. Contains marker comments (`# only if "shell" is available`), but is **appended as a whole** — the sections are not filtered per tool (known gap). |

Note: `agent.system` replaces the base entirely (PR-3/PR-4).
Provider templates have been removed (PRM-A/PRM-B).

---

## 3. Messages (Level 2)

| File | ID | Trigger | Role |
|---|---|---|---|
| `step-limit.txt` | `CTX-MAX-STEPS` | Last allowed step | Assistant message; tools are locked for this step (PR-13). Demands a text conclusion: summary of work done, open points, next recommendations. No tool calls. |
| `mode-plan-enter.txt` | – | Agent mode switches to `plan` | Reminder: do not edit, write the plan to `~/.opencode/plan/<name>.md`, only paths listed in the plan file may be edited. Transient, not persisted, no `SyntheticRaised` event. |
| `mode-plan-leave.txt` | – | Agent mode leaves `plan` | Reminder: normal editing is allowed again. |
| `server-restarted.txt` | – | Recovery after process abort | Synthetic message (`SyntheticRaised` or `runPrompt`), informs the model about the restart. |
| `ttl-message.txt` | – | New session | Title generation from the **first message** (`{text}`); response = only the title (2000 characters input, 100 characters output). |
| `ttl-conversation.txt` | – | Regeneration | Title generation from the history (`{text}`, 8000-character context), likewise "Reply with only the title". |

---

## 4. Tool and Structure Hints (Level 3)

| File | ID | Trigger | Role |
|---|---|---|---|
| `skill-guidance.txt` | `SKILL-GUIDANCE` | Visible skills | Explanation of the skill mechanism in the `<available_skills>` block: load via the Skill tool, direct user invocation appears as a `<skill_content>` block, no double invocation. |
| `no-skills.txt` | `NO-SKILLS` | No visible skills | Fallback text: `No skills are currently available.` |
| `unable-to-load-skill.txt` | – | Unknown skill ID | Skill tool error message: `Unable to load skill {id}`. |
| `bounding-hint.txt` | – | Output > 2000 lines or > 50 KiB | Truncation hint: `[showing lines 1-{shown} of {total}; full output saved to {path}]`. |
| `mcp-instructions.txt` | – | MCP servers with not fully denied tools | Wrapper `<mcp_instructions>{servers}</mcp_instructions>`; blocked servers are omitted entirely. |

Not extracted as resources: the **22 static tool descriptions**
(Java literals in `ToolDefinition`). They are part of the model contract
(PR-10), 9 of them golden-tested.

---

## 5. Compaction (Level 4)

| File | ID | Trigger | Role |
|---|---|---|---|
| `ctx-summary.txt` | `CTX-SUMMARY` | Compaction without predecessor | Structured summary according to a fixed template (Objective, Requirements, Decisions, Work State, Next Move, Relevant Files, Important Context) including rules: brevity, exact paths/identifiers, carry over only open user questions, do not mention process/compaction, no tools. |
| `ctx-summary-update.txt` | `CTX-SUMMARY-UPDATE` | Compaction with predecessor (`hasPredecessor`) | Consolidates the existing summary: newer history wins, remove outdated/irrelevant parts, do not reproduce wrapper tags (PR-8). |
| `ctx-nudge.txt` | `CTX-NUDGE` | Answer violates the summary template | One-time re-prompt: no tools, only the summary with exact section headings. Requires a provider. |
| `ctx-checkpoint.txt` | – | Every `CompactionMessage` | Wrapper `<conversation-checkpoint>` with `<summary>` and `<recent-context>`. Gap: `recent` is currently always passed as an empty string. |

---

## 6. Wiring (Code Locations)

| Class | Prompt | Status |
|---|---|---|
| `ContextBuilder` | `base-prompt`, `sys-guide`, `step-limit`, `mode-plan-*`, `ctx-checkpoint` | ✅ loaded from resources; `recent` empty |
| `ConversationFolding` | `ctx-checkpoint` | ✅ loaded from resource; `recent` empty |
| `CompactionService` | `ctx-summary`, `ctx-summary-update` | ✅ loaded from resources |
| `CtxNudge` | `ctx-nudge` | ✅ loaded from resource |
| `SkillCatalog` | `skill-guidance`, `no-skills` | ✅ loaded from resources |
| `SkillTool` | `unable-to-load-skill` | ✅ loaded from resource |
| `OutputBounding` | `bounding-hint` | ✅ loaded from resource |
| `McpInstructions` | `mcp-instructions` | ✅ loaded from resource |
| `Recovery` / `QocEngine` | `server-restarted` | ✅ loaded from resource |
| `TitleGenerator` | `ttl-message`, `ttl-conversation` | ✅ loaded from resources |
| `ToolDefinition` (all tools) | Tool descriptions | ⚠️ Java literals, not extracted |

---

## 7. Known Gaps

| Gap | Detail |
|---|---|
| `SYS-GUIDE` filtering | The entire file is appended as soon as one of `shell`/`write`/`edit` is present; the `# only if` markers end up in the prompt. |
| `CTX-CHECKPOINT` recent | `ConversationFolding` passes `recent=""`; the block stays empty. |
| `MODE-PLAN` persistence | Reminders are transient and are not logged as `SyntheticRaised`. |
| `TTL` model | `Engine.java` creates a `ModelRef` with random IDs; problematic with a real provider. |
| `CTX-SUMMARY-UPDATE` | Wired up and golden-tested, but no end-to-end test of the predecessor path. |
| Manifest/checksum | Not implemented (see §1). |
| Tool descriptions | 22 static tools, 9 of them golden-tested; no resources. |

---

## 8. Dynamization: Less Developer-Specific, Adaptable for Different Purposes

Current limitations: texts are hard-coded, English-language, tailored to a
specific product identity ("OpenCode", `~/.opencode/plan/…`) and fixed
language/rule constants.

### 8.1 Product Identity, Paths, and Limits as Variables

- `base-prompt.txt`: "You are the OpenCode session engine" → `{product}` or
  `{role}` from configuration (persona/deployment dependent).
- `mode-plan-enter.txt`: hard-coded `~/.opencode/plan/<name>.md` →
  `{planDir}` from config (workspace/product dependent).
- Summary/TTL limits (8000/2000/100 characters, max. 15 files) as
  `{maxChars}` variables instead of fixed in the text — adjustable per deployment.

### 8.2 Layered Loader (Overrides Instead of Classpath Only)

Fallback chain for `PromptLoader`:

```
CLI flag → Config directory (~/.<app>/prompts/) → Workspace override (.prompts/) → Classpath default
```

This lets users/deployments override prompts without rebuilding the engine;
the engine still knows only IDs (PR-12 consistently continued).
Plus a `manifest.json` with ID → version → checksum to validate overrides
and make deviations detectable.

### 8.3 Language and Audience Adaptation

- All engine prompts are English; a `lang` rendering parameter
  (`ctx-summary.en.txt` / `ctx-summary.de.txt` or a translation table per
  key) makes compaction, nudge, and step-limit texts language-dependent
  without code changes.
- Alternatively "Reply in the user's language" as a variable.

### 8.4 Composition Instead of Monoliths

- Split `sys-guide.txt` into fragments (`sys-guide/shell.txt`,
  `sys-guide/write.txt`, …) that the loader assembles per available tool
  — the marker gap disappears structurally.
- `ctx-summary.txt`: template (sections) and rules (brevity, paths,
  reticence) as separate fragments, composable or selectively overridable
  per use case.
- `step-limit.txt` mixes prohibition rules, required structure, and escalation
  language ("CRITICAL"); as fragments the tone is replaceable per deployment
  without endangering the contract semantics (no tools, text conclusion).

### 8.5 Profiles / PromptContext

A central `PromptContext` (product name, toolset, plan-mode behavior,
language, limits, target audience) populates `PromptLoader.render()`. Every file
then becomes a neutral template: the same `ctx-summary` serves the coding
engine, a support bot, or a research agent — depending on the profile.

### 8.6 Keep Structure Safeguards

Template texts like the summary format are a **contract** between the engine
(post-processing, `CtxNudge` detection based on section headings) and the
model. Rules for every dynamization:

- Pin Golden tests (`PromptGoldenTest`, `ToolDescriptionGoldenTest`) to the
  *rendered* structure (section names, wrapper tags), not to exact bytes.
- Validate overrides via checksum/manifest.
- Allow only semantically equivalent variants for behavior-critical prompts
  (`step-limit`, `ctx-nudge`).
- No provider control tokens (e.g. `|`) in prompt files (PR-1…PR-13,
  maintenance rules in `promptlib.md` §9).