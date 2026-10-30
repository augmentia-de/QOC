# context-build.md – Context Building Strategies and Skill Permission Gaps in QOC

> **Status:** Design draft · 2026-10-29  
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus)  
> **Related:** `docs/optimize_prompt.md`, `docs/analysis/a2a.md`, `docs/Security.md`  
> **Scope:** Context construction, skill filtering, skill injection, and skill permission comparison with OpenCode (`oc/`).

---

## 1. Purpose

This document describes:

1. Which context-building strategies are **possible and sensible** in QOC,
   especially for skills (selection, injection, dynamic updates).
2. What QOC **lacks compared to OpenCode (`oc/`)** regarding **skill
   permissions**, and how QOC could close or even surpass that gap.

The goal is to keep prompts small, relevant and auditable while never
hiding a skill the model actually needs.

---

## 2. Current QOC state

### 2.1 Context construction

QOC builds the request in `ContextBuilder.prepare()`:

```java
String base = agent.system().orElse(basePrompt(tools));
// instructions from AGENTS.md etc.
for (InstructionLoader.InstructionFile file : instructionLoader.load(...)) { ... }
// skills
String instruction = skills.instructions(rules);
if (!instruction.isBlank()) system.add(instruction);
// compaction checkpoint
checkpoint(session.id()).ifPresent(system::add);
```

Key facts:

- Two system blocks at most, delivered history only, frozen tool snapshot.
- Skills are rendered as one `<available_skills>` block via
  `SkillCatalog.instructions(rules)`.
- The block is added **once** per `prepare()` call.
- `SkillCatalog.listed(rules)` filters by `PermissionRules.evaluate(rules, "skill", id) != DENY`.
- `SkillCatalog.of(skills, allow, deny, max)` applies global allow/deny/max
  at discovery time.

### 2.2 Skill permission model (QOC)

Three layers:

| Layer | Mechanism | Where configured |
|---|---|---|
| 1. Discovery filter | `skills.enabled`, `skills.paths`, `skills.allow`, `skills.deny`, `skills.max` | `settings.json` → `skills` |
| 2. Prompt filter | `listed(rules)` removes `DENY` skills | Agent permissions in `settings.json` → `agents[].permissions` |
| 3. Execution guard | `SkillTool.execute()` checks `DENY` before loading | Same agent permissions |

Permission action: `"skill"` with skill ID as pattern, effect `ALLOW`/`DENY`/`ASK`.

### 2.3 OpenCode (`oc/`) skill permission model

| Aspect | OpenCode |
|---|---|
| Permission action | `"skill"` (generic permission system) |
| Prompt filter | `Skill.available(skills, permissions)` removes `deny` |
| Execution guard | Implicit in tool (permission check) |
| Global allow/deny filter | None (only permission ruleset) |
| Cap (`max`) | None |
| Enable/disable | Implicit (no skills = empty) |
| Permission scope | Agent + Session (merged) |
| Policy plugin | `ManagedPolicy.decision(current, "integration.use", "skill:<id>")` |
| Auto-invoke filter | `autoinvoke !== false` + description present |

---

## 3. Context-building strategies for QOC

### 3.1 Strategy overview

| Strategy | When | Pros | Cons | Effort |
|---|---|---|---|---|
| **A. Full list (status quo)** | Always | Simple, nothing missed | Token-heavy with 20+ skills | None |
| **B. Phase-based filtering** | Per agent phase | Small prompt, clear intent | Phase detection must be reliable | Medium |
| **C. Semantic filtering (BM25/Embeddings)** | Per user turn | High relevance, scalable | Risk of missing skills | Medium–High |
| **D. Hybrid (Core + Semantic)** | Per user turn | Best balance | Needs heuristics for "core" | Medium |
| **E. On-demand `skill_provider` tool** | Model or system triggered | No prompt bloat, flexible | Model may not call it | Low |

### 3.2 Strategy A – Full list (status quo)

**When to use:** Small catalogs (< 15–20 skills), or when correctness > token cost.

**How:** `ContextBuilder` calls `skills.instructions(rules)` and appends the full
`<available_skills>` block. No changes needed.

**Limitation:** With 30+ skills the prompt grows by 5–10 KB per turn, wasting
tokens and increasing confusion.

### 3.3 Strategy B – Phase-based filtering

**Concept:** Filter skills by the current project phase (Requirements,
Architecture, Implementation, QA, Deployment).

**Implementation in QOC:**

1. Add `Phase` enum and `listedFor(agent, phase, rules)` to `SkillCatalog`:
   ```java
   public List<Skill> listedFor(AgentProfile agent, Phase phase, List<Rule> rules) {
       return skills.values().stream()
           .filter(Skill::listed)
           .filter(s -> agent.tools().allowsPhase(phase, s))   // agent-level
           .filter(s -> phase.allowedSkills().contains(s.id().value())) // phase-level
           .filter(s -> PermissionRules.evaluate(rules, "skill", s.id().value()) != Effect.DENY)
           .sorted(Comparator.comparing(s -> s.id().value()))
           .limit(maxSkills > 0 ? maxSkills : Long.MAX_VALUE)
           .toList();
   }
   ```

2. Add `phase` to `AgentConfig` and `SessionInfo` metadata.

3. `ContextBuilder.prepare()` receives `phase` and calls
   `skills.instructions(rules, phase)` instead of `skills.instructions(rules)`.

4. Config:
   ```json
   {
     "agents": [{
       "name": "build",
       "phase": "implementation",
       "skills": { "allow": ["aisdlc-*"], "deny": ["experimental-*"] }
     }]
   }
   ```

**Pros:** Deterministic, auditable, small prompts.  
**Cons:** Phase detection must be reliable; risk of hiding needed skills.

### 3.4 Strategy C – Semantic filtering (BM25 / Embeddings)

**Concept:** Rank skills by relevance to the current user message (or last N
messages) and inject only Top-K.

**Implementation in QOC:**

1. Add a `SkillRanker` interface:
   ```java
   public interface SkillRanker {
       List<Skill> rank(List<Skill> candidates, String query, SkillFilterOptions opts);
   }
   ```

2. Implementations:
   - `Bm25SkillRanker` (no external deps, pure Java)
   - `EmbeddingSkillRanker` (uses provider embedding API or local model)

3. `SkillCatalog` gains:
   ```java
   public List<Skill> relevant(List<Rule> rules, String query, SkillFilterOptions opts) {
       List<Skill> candidates = listed(rules);
       if (candidates.size() <= opts.thresholdCount()) return candidates; // no filtering
       List<Skill> ranked = ranker.rank(candidates, query, opts);
       // always include core
       Set<String> core = new HashSet<>(opts.alwaysInclude());
       List<Skill> result = new ArrayList<>(ranked.stream()
           .filter(s -> s.score() >= opts.minScore())
           .limit(opts.topK())
           .toList());
       // add core skills not already present
       for (String id : core) {
           candidates.stream().filter(s -> s.id().value().equals(id))
               .filter(s -> result.stream().noneMatch(r -> r.id().value().equals(id)))
               .findFirst().ifPresent(result::add);
       }
       return result;
   }
   ```

4. `ContextBuilder.prepare()` receives `lastUserMessage` and calls
   `skills.instructions(rules, lastUserMessage, opts)`.

5. Config:
   ```json
   {
     "skills": {
       "filter": {
         "enabled": true,
         "strategy": "bm25",
         "topK": 12,
         "minScore": 0.25,
         "thresholdCount": 20,
         "alwaysInclude": ["read", "write", "edit", "grep", "glob", "skill"],
         "updateOn": "user_turn"
       }
     }
   }
   ```

**Pros:** Scales to 50+ skills, high relevance.  
**Cons:** Risk of missing skills (mitigated by `alwaysInclude` + fallback).

### 3.5 Strategy D – Hybrid (Core + Semantic)

**Concept:** Always inject "core" skills (project-essential), semantically filter
the rest.

**Implementation:** Combine Strategy B (phase) and Strategy C (semantic):

1. Define `coreSkills` per agent (e.g., `["read", "write", "edit", "grep",
   "glob", "skill", "skill_list"]`).
2. Filter non-core skills by phase + semantic ranking.
3. Merge: core + filtered (deduplicated, sorted by ID).

**Pros:** Best balance of relevance and safety.  
**Cons:** Needs heuristics for "core" definition.

### 3.6 Strategy E – On-demand `skill_provider` tool

**Concept:** Instead of injecting skills into the prompt, provide a tool that
the model (or system) can call to get relevant skills.

**Implementation:**

```java
public final class SkillProviderTool {
    public static ToolDefinition definition(SkillCatalog catalog,
                                            Function<AgentId, List<Rule>> rulesByAgent,
                                            SkillRanker ranker,
                                            SkillFilterOptions opts) {
        Schema input = Schema.obj(Map.of(
            "query", Schema.str().optional(),
            "limit", Schema.integer(1, 30).optional()
        ));
        return new ToolDefinition(new ToolName("skill_provider"),
            "Get relevant skills for the current task. Call if unsure which skill to load.",
            input, Option.none(),
            (value, context) -> {
                List<Rule> rules = rulesByAgent.apply(context.agentId());
                String query = JsonArgs.optionalString(value, "query")
                    .orElse(context.lastUserMessage());
                int limit = JsonArgs.optionalInt(value, "limit").orElse(opts.topK());
                List<Skill> ranked = catalog.relevant(rules, query, opts.withTopK(limit));
                String block = catalog.renderInstructions(ranked);
                return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(block)),
                    Map.of("count", ranked.size(), "strategy", opts.strategy())));
            }, null);
    }
}
```

**Auto-invocation (system-triggered):** The backend calls `skill_provider`
automatically before each model turn and injects the result as a tool result
or system block. This avoids prompt bloat while keeping skills visible.

**Pros:** No prompt bloat, flexible, model can request more.  
**Cons:** Tool results may be weighted less than system prompt by some models.

### 3.7 Injection methods

| Method | Description | OpenCode | QOC feasibility |
|---|---|---|---|
| **Dynamic system block** | Replace skill block in system prompt per turn | ✅ (via `instructions.ts#update`) | ⚠️ Needs `buildSystemPart()` extraction |
| **Auto-invoked tool result** | Inject as synthetic tool result | ⚠️ Possible but unusual | ✅ Easy (tool already exists) |
| **Separate system message** | Additional system message before user turn | ✅ (message model supports it) | ⚠️ Provider-dependent (some use only first system msg) |
| **In user message** | Merge into user text | ❌ Not recommended | ❌ Not recommended |

**Recommendation for QOC:** Start with **dynamic system block** (Strategy A/C
injected via `ContextBuilder`), add `skill_provider` tool as fallback.

### 3.8 Dynamic update support

| Capability | OpenCode | QOC |
|---|---|---|
| Incremental block replacement | ✅ (`instructions.ts#update` computes added/changed/removed) | ❌ (full `instructions()` re-render) |
| Turn-aware re-rendering | ✅ (session prompt builder passes query) | ❌ (`prepare()` has no query param) |
| Skill epoch tracking | ✅ (`epoch` in `SkillCatalog`) | ✅ (`epoch()` exists but unused for diffing) |
| Delta rendering | ✅ (`guidanceDelta(previous)`) | ✅ (exists but not used in `ContextBuilder`) |

**QOC gap:** `ContextBuilder.prepare()` does not accept a `query` parameter and
does not use `guidanceDelta()`. Adding these would close the gap.

### 3.9 Recommended implementation order for QOC

1. **Step 1:** Add `query` parameter to `ContextBuilder.prepare()` (overload).
2. **Step 2:** Add `SkillFilterOptions` config section.
3. **Step 3:** Implement `Bm25SkillRanker` (no external deps).
4. **Step 4:** Add `SkillCatalog.relevant(rules, query, opts)` method.
5. **Step 5:** Wire into `ContextBuilder` with `thresholdCount` guard.
6. **Step 6:** Add `alwaysInclude` core skills.
7. **Step 7:** Add fallback to full list if ranking returns empty.
8. **Step 8:** Implement `SkillProviderTool` (optional, for model-driven use).
9. **Step 9:** Add `guidanceDelta()` usage for incremental updates.
10. **Step 10:** Add embedding-based ranker (optional, provider-dependent).

---

## 4. Skill permissions: QOC vs OpenCode gaps

### 4.1 What OpenCode has that QOC lacks

| Feature | OpenCode | QOC | Impact |
|---|---|---|---|
| **`autoinvoke` flag** | Skills with `autoinvoke: false` are excluded from prompt but still loadable | No equivalent; `listed` flag exists but serves different purpose | QOC cannot hide a skill from prompt while keeping it loadable via tool |
| **Permission scope: Session** | Session-level permissions merged with agent permissions | Only agent permissions (session permissions exist in `ContextBuilder` via `session.permissions()` but not clearly documented for skills) | QOC session permissions may not be fully wired for skills |
| **Policy plugin (ManagedPolicy)** | Removes skills via `integration.use` decision | No policy plugin mechanism | QOC cannot dynamically remove skills in managed environments |
| **Permission: `ask` effect** | `ask` means "prompt user before loading" | `ASK` exists in `Effect` enum but `SkillTool.execute()` only checks `DENY`; `ASK` is treated as allowed | QOC cannot require user approval before loading a skill |
| **Description presence filter** | Skills without description are excluded from prompt | No filter; all `listed` skills included | QOC may inject skills with empty descriptions |
| **Remote skill sources** | Remote skills via `index.json` with version caching | Only local filesystem | QOC cannot load skills from remote URLs |
| **Incremental skill updates** | `instructions.ts#update` computes deltas | `guidanceDelta()` exists but unused in `ContextBuilder` | QOC cannot efficiently update skill list without full re-render |
| **Skill metadata/tags** | Skill info has tags for matching | `Skill` has no tags field | QOC cannot use tags for semantic filtering |

### 4.2 What QOC does better

| Feature | QOC | OpenCode | Advantage |
|---|---|---|---|
| **Global allow/deny filters** | `skills.allow`/`skills.deny` patterns at discovery | None | QOC can filter skills before they enter the catalog |
| **Hard cap (`max`)** | `skills.max` limits loaded skills | None | QOC protects prompt budget at discovery time |
| **Enable/disable toggle** | `skills.enabled` boolean | Implicit | QOC can disable all skills with one flag |
| **Execution guard** | Explicit `DENY` check in `SkillTool.execute()` | Implicit | QOC has clearer separation of concerns |
| **Skill listing tool** | `skill_list` lists all skills including hidden | No equivalent | QOC gives model visibility into hidden skills |
| **Permission rules as data** | `Rule` objects with pattern matching | Permission ruleset | QOC's `Wildcard.match` is more flexible |
| **Audit logging** | `LOG.fine("skill loaded: " + id)` | Not visible in code | QOC has explicit skill load logging |

### 4.3 How QOC could close the gaps

#### 4.3.1 Add `autoinvoke` flag to Skill

**Current:** `Skill.listed()` is a boolean.  
**Gap:** Cannot distinguish "visible in prompt" from "loadable via tool".

**Fix:** Add `autoinvoke` boolean to `Skill` record:

```java
public record Skill(
    SkillId id,
    String name,
    String description,
    String body,
    String baseDirectory,
    List<String> files,
    boolean listed,
    boolean autoinvoke,  // NEW: if false, hidden from prompt but loadable
    String fingerprint
) {}
```

Then in `SkillCatalog.listed()`:
```java
.filter(Skill::listed)
.filter(Skill::autoinvoke)  // NEW
```

And `SkillTool.list()` already shows hidden skills, so `autoinvoke=false` skills
would still be discoverable via `skill_list`.

#### 4.3.2 Wire `ASK` effect in SkillTool

**Current:** `SkillTool.execute()` only checks `DENY`:
```java
if (PermissionRules.evaluate(rules, "skill", id) == Effect.DENY) {
    return ReadTool.failure("skill", "skill is not allowed: " + id);
}
```

**Fix:** Handle `ASK`:
```java
Effect effect = PermissionRules.evaluate(rules, "skill", id);
if (effect == Effect.DENY) {
    return ReadTool.failure("skill", "skill is not allowed: " + id);
}
if (effect == Effect.ASK) {
    // trigger permission prompt via PermissionContext
    if (!permissionContext.ask("skill", id)) {
        return ReadTool.failure("skill", "skill loading denied by user: " + id);
    }
}
```

This requires a `PermissionContext` or callback in `ToolContext`.

#### 4.3.3 Add description-presence filter

**Current:** `SkillCatalog.listed()` includes all `listed` skills.  
**Fix:**
```java
.filter(skill -> skill.description() != null && !skill.description().isBlank())
```

Or better: log a warning for skills without descriptions (like OpenCode's
implicit filter).

#### 4.3.4 Add skill tags for semantic matching

**Current:** `Skill` has no tags.  
**Fix:** Add `List<String> tags` to `Skill` record and use them in `SkillRanker`
for keyword matching.

#### 4.3.5 Add policy plugin mechanism

**Current:** No `ManagedPolicy` equivalent.  
**Fix:** Add a `SkillPolicy` SPI:
```java
public interface SkillPolicy {
    boolean isAllowed(Skill skill, AgentProfile agent, SessionInfo session);
    default void onLoad(Skill skill) {}
    default void onDeny(Skill skill, String reason) {}
}
```

Register via CDI/ServiceLoader. Policies can remove skills from the catalog
at runtime (e.g., for managed environments).

#### 4.3.6 Use `guidanceDelta()` in ContextBuilder

**Current:** `ContextBuilder.prepare()` calls `skills.instructions(rules)` (full
render).  
**Fix:** Track previous catalog epoch in session state and use
`guidanceDelta()` for incremental updates:

```java
SkillCatalog previous = session.skillCatalogEpoch();
String delta = skills.guidanceDelta(previous);
if (!delta.isBlank()) system.add(delta);
```

This reduces token usage on subsequent turns when the catalog hasn't changed.

#### 4.3.7 Wire session permissions for skills

**Current:** `ContextBuilder` merges `agent.permissions()` and `session.permissions()`:
```java
List<Rule> rules = Stream.concat(agent.permissions().stream(), session.permissions().stream()).toList();
```

This already works for skills. But it should be documented and tested explicitly.

**Fix:** Add test coverage for session-level skill permissions and document
the merge semantics.

#### 4.3.8 Add remote skill sources (optional)

**Current:** Only local filesystem.  
**Fix:** Add `SkillRemoteSource` that fetches `index.json` from a URL and caches
by version. Lower priority; only if needed.

### 4.4 Summary: QOC vs OpenCode permission comparison

| Capability | OpenCode | QOC (current) | QOC (after fixes) |
|---|---|---|---|
| Deny filtering | ✅ | ✅ | ✅ |
| Allow filtering | Via permissions | ✅ (global + agent) | ✅ |
| Ask effect | ✅ (prompts user) | ❌ (treated as allow) | ✅ |
| Auto-invoke flag | ✅ | ❌ | ✅ |
| Description filter | ✅ | ❌ | ✅ |
| Global allow/deny | ❌ | ✅ | ✅ |
| Hard cap (max) | ❌ | ✅ | ✅ |
| Enable/disable toggle | ❌ | ✅ | ✅ |
| Skill listing tool | ❌ | ✅ | ✅ |
| Policy plugin | ✅ | ❌ | ✅ (via SPI) |
| Session permissions | ✅ | ⚠️ (wired but undocumented) | ✅ |
| Incremental updates | ✅ | ⚠️ (exists, unused) | ✅ |
| Remote sources | ✅ | ❌ | ⚠️ (optional) |
| Tags/metadata | ✅ | ❌ | ✅ |

---

## 5. Open questions

1. **Semantic filtering threshold:** At what skill count does filtering become
   worthwhile? Suggested: 18–20 skills.
2. **BM25 vs Embeddings:** Start with BM25 (no deps), add embeddings later
   if needed. Embedding quality depends on provider availability.
3. **Core skills definition:** Should core skills be hardcoded, configured
   per agent, or derived from usage frequency?
4. **Permission ASK UX:** How should QOC prompt the user for skill approval?
   Via HTTP API, CLI, or UI?
5. **Policy plugin priority:** Should policies override agent permissions or
   complement them?

---

## 6. References

- `qoc/docs/optimize_prompt.md` – prompt minimization and context economy
- `qoc/docs/Security.md` – security model including A2A
- `qoc/docs/spec/permissions/02-evaluation.md` – permission evaluation
- `qoc/docs/spec/permissions/03-effect-matrix.md` – effect matrix
- `qoc/docs/spec/betrieb/02-skills.md` – skill specification
- `oc/packages/core/src/skill.ts` – OpenCode skill module
- `oc/packages/core/src/skill/instructions.ts` – OpenCode skill instructions
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/skill/SkillCatalog.java` – QOC skill catalog
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/skill/SkillTool.java` – QOC skill tool
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/EngineConfig.java` – QOC config
