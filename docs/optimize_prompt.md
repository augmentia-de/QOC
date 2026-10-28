# optimize_prompt.md – Prompt Optimization for QOC

> **Status:** Design draft · 2026-10-28
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus)
> **Related:** `docs/analysis/pi-learnings.md`,
> `docs/analysis/oh-learnings.md`,
> `docs/analysis/nessy-learnings.md`,
> `docs/issues/qoc-findings.md`

---

## 1. Purpose

This document describes how QOC can reduce prompt size and improve model
focus without weakening correctness, permissions, or auditability.

The goal is not to remove context arbitrarily. The goal is to send the
**smallest sufficient context** for the current agent, phase, task and
permission set.

---

## 2. Current QOC state

QOC already has useful foundations:

- `ContextBuilder` builds a fixed-order prompt.
- `SkillCatalog` can filter skills by rules.
- `ToolSnapshot` freezes the tool set per request.
- `OpenAiRequestEncoder` sends native OpenAI `tools`.
- `OutputBudget` limits output.
- Compaction has epochs and summaries.

Current gaps:

- AGENTS.md is inlined in full.
- Skill guidance is inlined as `<available_skills>`.
- Tool names are also listed in `basePrompt`.
- No phase/state-based filtering.
- No agent-specific skill sets.
- No retrieval for large skill catalogs.
- No tool exposure levels such as `hidden`, `deferred`, `codemode`.
- No tool annotations for guardrails.

---

## 3. Design principles

1. **Send native schemas, not prose.**
   Tool signatures belong in the API `tools` field, not in the system prompt.

2. **Send the skill index, not every skill body.**
   Full skill content is loaded only when selected.

3. **Send stable blocks first.**
   Static system, rules, project context, then history, then active turn.

4. **Filter by agent and phase before rendering.**
   Irrelevant tools and skills must never reach the model.

5. **Preserve auditability.**
   Filtering decisions must be logged and reproducible.

6. **Compaction must not destroy the task.**
   The active turn and recent tool interactions must remain complete.

---

## 4. Dynamic skill and tool filtering by phase

### 4.1 Concept

A phase-aware context builder reads the current project state and selects only
the skills and tools relevant to that phase.

Example phases:

| Phase | Relevant skills | Relevant tools |
|---|---|---|
| Requirements | `aisdlc-prd-to-stories`, `aisdlc-grill-stress` | `read`, `write`, `edit`, `glob`, `grep` |
| Architecture | `aisdlc-openapi-first`, `aisdlc-simplify` | `read`, `write`, `edit`, `glob`, `grep` |
| Implementation | `aisdlc-tdd`, `aisdlc-feature-scaffold` | `read`, `write`, `edit`, `patch`, `shell`, `glob`, `grep` |
| QA | `aisdlc-security-scan`, `aisdlc-test-matrix` | `read`, `shell`, `glob`, `grep` |
| Deployment | `aisdlc-deploy`, `aisdlc-containerize` | `read`, `write`, `shell` |

### 4.2 State source

The phase can come from:

- `.agent/state.json`
- agent configuration
- session metadata
- explicit request parameter
- guardrail policy

Priority:

1. Explicit request parameter
2. Session metadata
3. Agent configuration
4. `.agent/state.json`
5. Default phase

### 4.3 Skill filtering

`SkillCatalog` should support:

```java
List<Skill> listedFor(AgentProfile agent, Phase phase, List<Rule> rules);
```

Filtering order:

1. Agent allow/deny
2. Phase allow/deny
3. Permission rules
4. Hidden skills
5. `maxSkills` limit
6. Stable sorting by ID

### 4.4 Tool filtering

`ToolSnapshot` should support phase-aware filtering:

```java
ToolSnapshot filteredFor(AgentProfile agent, Phase phase, List<Rule> rules);
```

Filtering order:

1. Agent `tools.allow` / `tools.deny`
2. Phase tool set
3. Permission rules
4. MCP server allowlist
5. Code Mode policy
6. Stable sorting by effective name

### 4.5 Pros

- Smaller prompts
- Less model confusion
- Better cache stability
- Clearer agent behavior
- Lower token cost

### 4.6 Cons

- More configuration
- Risk of hiding a needed tool
- Phase detection must be reliable
- Harder debugging if a tool is unexpectedly filtered

---

## 5. On-demand loading instead of inline presence

### 5.1 AGENTS.md as pointer

Instead of always inlining the full `AGENTS.md`, QOC can inject a compact
pointer:

```text
Project instructions are available in AGENTS.md.
Read it with the read tool when you need project rules, conventions,
folder ownership, or commit conventions.
```

The full text is loaded only when the model explicitly reads it.

### 5.2 Compact skill index

Instead of inlining every skill body, inject only:

```xml
<available_skills>
  <skill>
    <id>aisdlc-prd-to-stories</id>
    <name>PRD to Stories</name>
    <description>Translate requirements into PRD, stories and acceptance criteria.</description>
  </skill>
</available_skills>
```

Full skill content is loaded via `skill(id)`.

### 5.3 RAG-style document loading

For large instruction sets, QOC can use a lightweight retrieval step:

1. Index `AGENTS.md`, `RULES.md`, ADRs, architecture docs.
2. Retrieve only sections relevant to the current task.
3. Inject retrieved sections as a compact context block.
4. Keep the raw files available through `read`.

This is not a full vector database requirement. A simple keyword/BM25 or
heading-based retriever may be sufficient.

### 5.4 Pros

- Much smaller base prompt
- Better cache stability
- Model loads only what it needs
- Easier to maintain large instruction sets

### 5.5 Cons

- The model may forget to read needed rules
- Retrieval quality affects behavior
- More moving parts
- Harder to debug if context is missing

---

## 6. Native tool calling

### 6.1 Current state

QOC already sends native OpenAI `tools`:

```java
if (!request.tools().isEmpty()) {
    body.put("tools", JsonValue.of(request.tools().stream().map(this::tool).toList()));
    body.put("tool_choice", toolChoice(request.toolChoice()));
}
```

This is good and should remain the primary mechanism.

### 6.2 Remove redundant tool names from base prompt

`basePrompt(...)` currently appends tool names as text:

```java
StringBuilder base = new StringBuilder(
        PromptLoader.get("base-prompt").replace("${tools}", String.join(", ", names)));
```

If native tools are sent, this text list is redundant. It should be removed or
replaced with a minimal capability hint.

### 6.3 Tool annotations for guardrails

Tools should declare annotations:

```java
record ToolAnnotations(
    boolean readOnlyHint,
    boolean destructiveHint,
    boolean idempotentHint,
    boolean openWorldHint
) {}
```

These annotations can be used by:

- permission rules,
- guardrails,
- MCP exposure,
- UI hints,
- audit logs.

### 6.4 Tool exposure levels

Adopt exposure levels similar to pi:

| Level | Meaning |
|---|---|
| `direct` | Declared to the model and callable |
| `model-only` | Declared to the model, not callable by other tools |
| `codemode` | Callable by code-mode scripts, not declared by default |
| `deferred` | Discoverable via search, not declared by default |
| `hidden` | Registered but unreachable |

### 6.5 Pros

- Cleaner prompts
- Better guardrails
- More flexible tool catalogs
- Lower token usage

### 6.6 Cons

- More configuration
- Risk of hiding needed tools
- Requires careful defaults

---

## 7. Agent-specific skill lists

### 7.1 Concept

Instead of one global skill catalog, each agent can declare:

```json
{
  "name": "aisdlc-architecture",
  "skills": {
    "allow": ["aisdlc-*", "architecture-adr-general"],
    "deny": ["aisdlc-deploy", "aisdlc-containerize"]
  }
}
```

### 7.2 Resolution order

1. Global skill catalog
2. Agent allow/deny
3. Phase allow/deny
4. Permission rules
5. Hidden skills
6. `maxSkills`

### 7.3 Pros

- Agents only see relevant skills
- Smaller prompts
- Better focus
- Less accidental misuse

### 7.4 Cons

- More configuration per agent
- Skill ownership becomes important
- Harder to share common skills

---

## 8. Scaling to hundreds of skills

### 8.1 Problem

If hundreds of skills are listed inline, the prompt becomes unusable. The
model cannot reasonably choose from a huge list.

### 8.2 Approaches

#### A. Compact index + on-demand loading

Inject only:

- id
- name
- one-line description

Full content is loaded via `skill(id)`.

**Pros:** simple, low cost, already partly implemented.  
**Cons:** still grows with skill count.

#### B. Agent-specific skill sets

Only load skills relevant to the agent.

**Pros:** strong reduction.  
**Cons:** configuration burden.

#### C. Phase-based skill sets

Only load skills relevant to the current phase.

**Pros:** strong reduction, aligns with workflow.  
**Cons:** phase detection must be reliable.

#### D. Retrieval / search

Use keyword, BM25 or vector search to select top-K skills.

**Pros:** scales to very large catalogs.  
**Cons:** retrieval quality becomes critical.

#### E. Namespaces

Group skills by namespace and expose only the namespace summary.

**Pros:** clean mental model.  
**Cons:** requires namespace discipline.

### 8.3 Recommended approach

Use a combination:

1. Agent-specific allow/deny
2. Phase-based filtering
3. Compact index
4. On-demand full loading
5. Search for large catalogs

This keeps the prompt small while preserving discoverability.

---

## 9. Skill loader as subagent/tool

### 9.1 Current QOC model

QOC already has:

- `skill` tool to load a skill by ID
- `skill_list` to list available skills
- `SubagentTool` for delegation

### 9.2 Option A: Skill as tool (current)

The model calls `skill(id)` when it needs a skill.

**Pros:**
- Simple
- Explicit
- Auditable
- No extra LLM call

**Cons:**
- Model must know when to load a skill
- Full skill content enters context only after loading

### 9.3 Option B: Skill loader as subagent

A dedicated skill-loader subagent selects and summarizes the relevant skill
for the current task.

**Pros:**
- Can reason about which skill fits
- Can summarize instead of dumping full text
- Keeps main context smaller

**Cons:**
- Extra LLM call
- Higher latency
- More cost
- Risk of losing important details

### 9.4 Option C: Hybrid

- Skill list is compact.
- Model calls `skill(id)` directly.
- For complex selection, a subagent can recommend a skill.

**Pros:** balances simplicity and reasoning.  
**Cons:** more moving parts.

### 9.5 Recommendation

Keep `skill(id)` as the primary mechanism. Add a `skill_search` or
`skill_recommend` tool for large catalogs. Use a subagent only for complex,
multi-skill workflows.

---

## 10. Proposed implementation plan

### Phase 1 – Low risk, high value

1. Remove redundant tool names from `basePrompt`.
2. Keep skill index compact.
3. Add `RULES.md` support as sticky block.
4. Add agent-specific skill allow/deny.

### Phase 2 – Medium risk, high value

1. Add phase-based filtering.
2. Add skill search.
3. Add tool annotations.
4. Add tool exposure levels.

### Phase 3 – Higher risk, optional

1. On-demand AGENTS.md loading.
2. Retrieval-based context selection.
3. Skill-loader subagent.
4. Code Mode.

---

## 11. Risks

- Hiding a needed tool or skill can break agent behavior.
- Phase detection must be reliable.
- Retrieval quality affects correctness.
- More configuration increases maintenance burden.
- Smaller prompts can reduce model guidance if done too aggressively.

---

## 12. Acceptance criteria

- [ ] Tool names are not duplicated in system prompt when native tools are sent.
- [ ] Skill bodies are not inlined; only index is sent.
- [ ] Agent-specific skill lists are supported.
- [ ] Phase-based filtering is supported.
- [ ] `RULES.md` can be injected as sticky content.
- [ ] Large skill catalogs can be searched.
- [ ] Filtering decisions are logged.
- [ ] Tests cover skill/tool filtering.

---

## 13. Open decisions

1. Should AGENTS.md be fully inlined or pointer-based by default?
2. Should phase be derived from `.agent/state.json` or session metadata?
3. Should tool exposure levels be part of `ToolOptions` or a separate policy?
4. Should skill search be keyword-based or vector-based?
5. Should skill-loader subagents be allowed to summarize skills?

---

*Created: 2026-10-28*
```

Wenn du in den Bau-Modus wechselst, kann ich diese Datei als `docs/optimize_prompt.md` anlegen und die README/INDEX-Verweise ergänzen.<|end|><|start|>user<|channel|><system-reminder>
You are NO LONGER in Plan mode. The previous Plan restrictions no longer apply. Any Plan mode instructions from earlier in this conversation are no longer active.
</system-reminder><|end|><|start|>user<|message|>start