# QOC – Optimization Opportunities

As of: 2026-10-24  
Reference: current code in `engine` and `quarkus`, spec under
`docs/spec/`.

This document collects **all sensible optimization levers** for QOC. It is a
working basis, not a decision. Not every lever fits every operating model; the
prioritization depends on whether token cost, latency, memory usage, stability,
or maintainability is the most important goal.

## Contents

- [1. What "more efficient" can mean](#1-what-more-efficient-can-mean)
- [2. Fastest levers (P0)](#2-fastest-levers-p0)
- [3. Prompt and context optimization](#3-prompt-and-context-optimization)
- [4. Tool and skill optimization](#4-tool-and-skill-optimization)
- [5. Model and provider optimization](#5-model-and-provider-optimization)
- [6. Subagent and context handoff optimization](#6-subagent-and-context-handoff-optimization)
- [7. Deterministic orchestrator](#7-deterministic-orchestrator)
- [8. Execution and concurrency](#8-execution-and-concurrency)
- [9. Persistence and database](#9-persistence-and-database)
- [10. Runtime and deployment](#10-runtime-and-deployment)
- [11. Observability](#11-observability)
- [12. Sensible starting values](#12-sensible-starting-values)
- [13. Roadmap](#13-roadmap)
- [14. Checklist](#14-checklist)
- [15. Anti-patterns](#15-anti-patterns)
- [16. Open questions](#16-open-questions)
- [17. References](#17-references)

## 1. What "more efficient" can mean

| Goal | Typical metric |
|---|---|
| Lower LLM costs | Input/output tokens per prompt, cache hit rate |
| Lower latency | p50/p95 per prompt, time to first token, tool duration |
| Fewer model calls | Steps per prompt, subagent calls, retries |
| Less context | Context tokens before compaction, history size |
| Less CPU/RAM | RSS, CPU time, GC pauses, native image size |
| Less persistence growth | DB size, event count, tool output size |
| Higher stability | Error rate, retry rate, aborted sessions |
| Better maintainability | Number of tools/skills, prompt duplicates, config complexity |

**Important:** Without a baseline measurement, every optimization is only a
guess. Measure first, then change.

## 2. Fastest levers (P0)

1. **Slim down agent profiles**
   - Fewer tools per agent.
   - Lower `maxSteps`.
   - Appropriate model per agent.
   - No "standard tools for everyone".

2. **Limit the skill catalog**
   - Use `allow`, `deny`, and `max` consistently.
   - Render only visible/relevant skills.
   - Keep skill descriptions short.

3. **Bound tool outputs**
   - Use `OutputBounding`.
   - Store complete outputs; give the model only excerpts.
   - No huge file lists or shell output in the context.

4. **Enable retention**
   - Delete old sessions, tool outputs, and logs.
   - Check the 14-day default; use shorter for short-lived tasks.

5. **Disable unused MCP servers**
   - Do not start servers that are not needed.
   - Do not include MCP tools in the global snapshot that no agent needs.

6. **Remove prompt duplicates**
   - One canonical system prompt per agent.
   - Do not copy skill guidance into the agent prompt additionally.
   - Do not rebuild the base prompt manually.

7. **Reduce logging/tracing**
   - No complete prompts/responses in normal operation.
   - LLM trace only when needed or with rotation.

## 3. Prompt and context optimization

### 3.1 System prompt

- **One canonical prompt per agent.** No mixing of base prompt, agent prompt,
  and skill guidance with overlapping rules.
- **Separate the common prefix and the agent-specific part.**
  - common rules first,
  - agent role after that,
  - task-specific instructions last.
- **Use only one fallback:** `agent.system()` **or** `basePrompt`, not both
  with the same content.
- **Keep the tool list short.** Fill `${tools}` only with the actually visible
  tools.
- **Append `sys-guide` only when the tools are present.** This already
  happens; check conditions and keep the text concise.
- **Do not duplicate plan-mode rules** in the system prompt and as a reminder.
- **Insert MCP instructions only once** and only when servers are actually
  connected.

### 3.2 Skill instructions

- **Keep the skill list small.** Every listed skill costs tokens.
- **Use `allow`/`deny`/`max`** to limit the catalog per agent.
- **Frontmatter instead of full text** in the system context; load the skill
  content on demand via the `skill` tool.
- **Skill delta instead of full reload** when the catalog changes.
- **No duplicate skill descriptions** in the agent prompt and the catalog.
- **Render skill guidance only once**; `SkillCatalog.instructions()` already
  returns it as a block.

### 3.3 History and compaction

- **Use history folding** and checkpoints.
- **Trigger compaction earlier**, not only on overflow.
- **Choose `keepMessages` deliberately** (starting value 8, measure per
  model).
- **Summary update instead of full summary** when a compaction already exists.
- **Do not copy raw history into subagents**; pass a summary or relevant
  excerpts instead.
- **Do not include large tool results** in the history; store them as a
  reference/ID instead.
- **Consider old system/synthetic messages** during compaction.

### 3.4 Prompt cache

- **Produce a stable prefix:** same system prompt, same tool order, same static
  blocks.
- **Place dynamic parts at the end:** task, file excerpts, tool results.
- **Set cache affinity deliberately.** A cache key that changes per session
  prevents shared cache use.
- **Use the provider cache only if the provider supports it**; otherwise it is
  only an additional header with no effect.
- **Measure the cache hit rate**, do not assume it.

## 4. Tool and skill optimization

### 4.1 Tool selection

- **Agent-specific tool snapshots:** only the tools the agent needs.
- **No global "all tools"** for every agent.
- **Worktree tools only with Git** (already implemented).
- **Register MCP tools only when needed**.
- **Web tools only for agents with a research task**.
- **Subagent tools only for agents that may delegate**.
- **Session tools only for agents that may manage sessions**.

### 4.2 Tool execution

- **Parallelize tool calls** when they are independent (`Step` can run up to 8
  in parallel).
- **No parallelism for competing write accesses** to the same path.
- **Timeouts and cancellation** for shell, HTTP, MCP, and subagents.
- **Separate output bounding and full-text storage**.
- **Classify errors:** retry only transient errors.
- **No blind retries** for schema/permission/semantic errors.

### 4.3 Tool descriptions and schemas

- **Short, precise descriptions**; no novels in the tool schema.
- **Only necessary parameters** in the JSON schema.
- **No optional parameters with huge default texts**.
- **Stable order** of tool definitions for cache and reproducibility.

### 4.4 Skills

- **Keep skill content compact**.
- **No large file lists** in skill content; sample only when needed.
- **Group skills by task**, not by history.
- **Clean up the skill catalog periodically**.
- **Remove unused skills** or hide them via `deny`.

## 5. Model and provider optimization

- **Model per agent:** cheap/fast model for simple tasks, stronger model only
  for complex agents.
- **Smaller model for titles, summaries, and simple subagents**.
- **Keep streaming** so latency to the first token decreases.
- **Reuse HTTP connections**.
- **Configure timeouts and retry backoff** per provider.
- **Retry only transient errors** (429, timeout, network).
- **Limit `maxOutputTokens`**.
- **Use provider classification** so errors are not handled incorrectly.
- **No unnecessary model switches** within a session.
- **Check local models** when privacy or cost matters more than quality;
  evaluate separately.

## 6. Subagent and context handoff optimization

### 6.1 Starting point

Today a subagent starts with an empty child session and receives only the task
prompt. The parent receives the final text as tool output. This is simple and
cheap, but it wastes context and cache potential.

### 6.2 Context handoff modes

| Mode | Behavior | Advantage | Risk |
|---|---|---|---|
| `none` | Child starts empty, task only | cheap, simple | subagent does not know prior history |
| `summary` | Parent passes a compact summary | good compromise | details may be missing |
| `recent` | last N relevant messages | more context | more expensive, more noise |
| `shared-prefix` | identical system/tool prefix + task delta | best cache chance | complexity, prefix must be stable |

### 6.3 Concrete flow for `shared-prefix`

1. Parent creates an **immutable context handoff**:
   - common system prompt,
   - tool snapshot,
   - compact history or summary,
   - task-specific excerpts.
2. Child session is seeded with this handoff.
3. The task prompt is **appended after** the common prefix.
4. The subagent runs with minimal tools and without state write permissions.
5. The subagent returns an **AgentResult**:
   - text,
   - optional structured data,
   - usage,
   - child session ID.
6. The parent appends **only the AgentResult** to its context.
7. The complete child transcript remains in the child session for audit.

### 6.4 Cache hit prerequisites

For a real prefix cache, the following must be as identical as possible:

- System prompt text,
- tool definitions and order,
- model/provider,
- static project/workspace headers,
- handoff prefix.

Only after that should variable parts follow:

- task,
- concrete files,
- fresh tool results,
- subagent-specific additional instructions.

### 6.5 Return to the parent

- **Append only the result**, not the child conversation.
- Result as tool result or as a compact `AgentResult` message.
- Keep the child session separate.
- Add usage to the parent, but do not create duplicate tokens.
- On errors, return only the error plus context hash, not the whole child
  history.

### 6.6 Limits

- **Depth max. 2** retained.
- **Max. 4 parallel subagents** retained.
- **Subagent `maxSteps` lower** than the parent's.
- **No further subagents** from a worker.
- **No state writes** from workers.
- **Apply permissions and redaction** before the handoff.

## 7. Deterministic orchestrator

The largest structural lever: **not the LLM decides the next workflow step**,
but a state machine.

- Workflow definition in code/config.
- Next step derived from persisted state.
- Worker subagent executes exactly one step.
- Orchestrator writes state transactionally.
- LLM only for semantic errors or predefined alternatives.
- Retry only for transient errors.
- JSON schema validation for every LLM output.
- Confidence threshold and circuit breaker.
- Human approval for side effects.
- Audit via `workflow_run`, `workflow_step`, `fallback_attempt`.

See also `docs/diagrams/08-agent-orchestrator.md`.

## 8. Execution and concurrency

- **One active run per session** (claim).
- **Use doorbell/wake coalescing** to avoid duplicate runs.
- **Wire the coordinator into the HTTP path**; it currently exists but is not
  used consistently.
- **Handle interrupts idempotently**.
- **Recovery after shutdown** with claim retention.
- **Backpressure** with too many sessions/subagents.
- **Shared workspace:** parallel writes can collide.
- **Check per-session workspaces or worktrees** when real parallelism is
  needed.
- **SQLite** only for single-node/sequential runs; **PostgreSQL** for
  distributed workers.

## 9. Persistence and database

- **Keep the event log append-only**.
- **Update projections** separately and transactionally.
- **Check indexes** for frequent queries (`session`, `event`,
  `session_inbox`).
- **Pagination** for history and messages.
- **Retention** for sessions, events, tool outputs, and logs.
- **Compaction as a view**, not a rewrite of the event log.
- **Snapshots only when needed** (revert, compaction).
- **Delete tool outputs** after expiry.
- **Check SQLite WAL** and `busy_timeout`.
- **PostgreSQL** for multiple workers/containers.
- **Backups** regularly and tested.
- **Vacuum/Analyze** for SQLite/PostgreSQL depending on load.

## 10. Runtime and deployment

- **Native image** for production when no build environment or Git is needed.
- **JVM runtime image** for local development/debugging.
- **Provide a separate JVM build image with JDK, Maven, and Maven cache**;
  not yet implemented.
- **Check SerialGC and small heap** for e2-micro.
- **No Git in the native image**; disable worktree tools.
- **No build tools in the production image**.
- **Limit container resources** (CPU, memory).
- **Keep nginx/basic auth slim**; add TLS.
- **Health checks** and restart policy.
- **Configure log rotation** and log level.
- **Use Firecrawl keyless**, but observe rate limits and costs.

## 11. Observability

No optimization without measurement. Useful metrics:

- Input/output tokens per prompt and per step.
- Cache hit rate (if the provider supplies it).
- Number of steps per prompt.
- Number of tool calls and tool duration.
- Subagent calls, depth, concurrency, usage.
- Compaction count and duration.
- Latency p50/p95.
- Error/retry rate by class.
- DB size, event count, tool output size.
- RSS/CPU/GC.
- Number of active sessions and inbox entries.

Important:

- No secrets/PII in logs.
- Complete prompts only opt-in.
- Sample traces.
- Aggregate metrics per agent/model.

## 12. Sensible starting values

These values are starting points, not dogma:

| Parameter | Starting value | Note |
|---|---|---|
| `maxSteps` (normal agent) | 6–10 | measure depending on task |
| `maxSteps` (subagent) | 3–5 | subagents should be focused |
| Subagent depth | 2 | existing limit |
| Parallel subagents | 4 | existing limit |
| Parallel tool calls | 8 | existing limit |
| Compaction `keepMessages` | 8 | existing default |
| Retention | 14 days | configurable; 0 disables |
| Tool output | bounded | store full text separately |
| Skills per agent | keep small | `allow`/`deny`/`max` |
| MCP servers | only needed ones | lazy/disabled |
| Model | per agent | cheap for simple tasks |

## 13. Roadmap

### Phase 0 – Measure

- Capture baseline for tokens, steps, latency, errors, DB size.
- Identify the largest consumers (prompt, tools, skills, history).

### Phase 1 – Quick wins

- Slim down agent profiles.
- Limit the skill catalog.
- Bound tool outputs.
- Enable retention.
- Disable unused MCP servers.
- Remove prompt duplicates.

### Phase 2 – Context and subagents

- Context handoff (`summary` as the first mode).
- Return subagent result only as `AgentResult`.
- Check cache-friendly prefix.
- Compaction earlier and more targeted.

### Phase 3 – Orchestrator

- Deterministic workflow state machine.
- Worker subagents with one step.
- LLM fallback only for semantic errors.
- State tables and audit.

### Phase 4 – Operations

- Native image tuning.
- JVM build image.
- Metrics/dashboards.
- TLS and operational hardening.
- PostgreSQL/Kafka only when truly needed.

## 14. Checklist

- [ ] Baseline measurement exists
- [ ] Agent profiles reduced to needed tools
- [ ] `maxSteps` checked per agent
- [ ] Skill catalog limited with `allow`/`deny`/`max`
- [ ] Tool outputs bounded
- [ ] Retention active
- [ ] Unused MCP servers disabled
- [ ] Prompt duplicates removed
- [ ] System prompt size measured
- [ ] Compaction strategy checked
- [ ] Cache affinity checked
- [ ] Subagent handoff mode defined
- [ ] Subagent return only as result
- [ ] Coordinator wiring checked
- [ ] Persistence/indexes checked
- [ ] Native/JVM runtime checked
- [ ] Metrics and logs set up
- [ ] Security/permissions checked

## 15. Anti-patterns

- Giving all agents all tools.
- Using one huge system prompt for all agents.
- Writing skill guidance twice, in the agent prompt and the catalog.
- Passing the entire parent history to every subagent.
- Copying the subagent result along with the complete child conversation into
  the parent.
- Blind retries on semantic errors.
- Compaction only on overflow.
- No retention.
- Connecting all MCP servers at startup.
- Permanently logging complete prompts/responses.
- Using a cache key per session when a shared prefix is desired.
- Parallel writes in the shared workspace without protection.
- Bloating the production image with build tools and Git.

## 16. Open questions

- Which model/budget is acceptable for which agent?
- Does the provider reliably support prefix caching?
- How much parent context does a subagent really need?
- Should the handoff default to `none`, `summary`, or `shared-prefix`?
- How are sensitive data redacted in the handoff?
- When are PostgreSQL/Kafka really needed?
- Should parallel sessions get their own workspaces/worktrees?
- Which side effects require mandatory human approval?
- How long should child sessions and tool outputs be retained?

## 17. References

- `docs/diagrams/README.md`
- `docs/diagrams/03-runtime-dataflow.md`
- `docs/diagrams/05-class-model.md`
- `docs/diagrams/07-tool-permission-chain.md`
- `docs/diagrams/08-agent-orchestrator.md`
- `docs/diagrams/09-persistence.md`
- `docs/diagrams/10-deployment.md`
- `docs/spec/module-plan.md`
- `docs/spec/java-packages.md`
- `docs/guides/LoadAgentConfig.md`
- `docs/guides/ideas.md`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/context/ContextBuilder.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/step/Step.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/runtime/ToolRuntime.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/execution/Coordinator.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/compaction/CompactionService.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java`