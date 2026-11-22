# Isomorphic Java Workflow and LLM System – Summary

> **Source:** `docs/next/ideen.txt` (long architecture conversation).  
> **Status:** Concept/reference (2026-11-22) — Not an implementation plan; summarizes ideas from runtime-plan-adaptation.
> **Goal:** Compact extraction of the architectural ideas for QOC.

---

## 1. Core idea: Isomorphic data model

- Workflow structure and LLM context have **exactly the same structure** (AST/JSON tree).
- "The state is the prompt is the plan" – no mapping hell.
- Persistence as a unified tree (JSON), versionable, event-log-capable.
- Java: `sealed interface Node` + Records + Pattern Matching / Visitor.

## 2. Dynamic planning: OODA loop per step

After each node:
1. State update (write result into tree).
2. LLM receives the entire tree as context.
3. Structured decision (JSON Mode / Function Calling): `CONTINUE`, `GO_BACK`, `REPLAN`, `FINISH`.

## 3. Self-correction loop (Self-Correction)

- Native JSON schema enforcement (constrained decoding) for syntactic validity.
- Semantic validation (guardrails) for domain correctness.
- On error: catch exception, enrich prompt with error details, return to LLM (max. N retries).
- In practice, state-of-the-art models correct themselves on the 2nd attempt in >95% of cases.

## 4. Goal definition per node (`NodeGoal`)

```java
public record NodeGoal(String description, List<ValidationRule> rules, EvaluationStrategy strategy) {}
```

- **Deterministic (code):** schema, data types, formats, exact matches.
- **LLM-as-a-Judge:** semantic completeness, tone/compliance, logical consistency.
- **Hybrid (recommended):** code check first (fast, free), then LLM judge (only when necessary).
- Funnel principle: Schema → Programmatic rules → LLM judge (cheap → expensive).

## 5. Persistent chat session per agent/subagent

- Each node keeps its own chat history (DB-persistent).
- **Targeted follow-up questions** without full re-run (`node.chatSession().sendMessage(...)`).
- **Inspect & debug:** developers can step into a node's chat session.
- **Agent-to-agent communication:** peer review (generator & critic), expert consultation (on-demand sparring) via cross-session calls.
- **Subagent purging:** subagents perform many internal tool calls, but only the compact result flows into the parent workflow context (token-bloat prevention).

## 6. Upstream correction & lineage

- **Root-cause discovery:** agent in step N notices missing data → cross-session call to the producer step.
- **Memory patch:** producer corrects output and stores insight in its chat history.
- **Selective rollback/replay:** only consuming downstream nodes are invalidated (`DIRTY`/`PENDING_REPLAY`) and re-executed; unaffected steps are skipped (bypass).
- **Data-scope tracking:** each node knows which JSON paths it consumed → impact-scope calculation.
- **Execution modes:** `NORMAL`, `DRY_RUN`, `IMPACT_ANALYSIS`.

## 7. Hybrid nodes: agent vs. service

```java
public sealed interface WorkflowNode permits AgentNode, SubAgentNode, ServiceNode {}
```

- **AgentNode/SubAgentNode:** chat session, self-healing loop, LLM judge.
- **ServiceNode:** deterministic (REST, DB, code). No chat session.
- **Input hashing:** on upstream change compare hash of new inputs → rerun or bypass.
- **Saga pattern:** for services with side effects (compensating transactions, rollback).

## 8. DB as single source of truth

- React Flow = pure projection layer (UI view) on the DB.
- Each step persisted with its exact input → individually reproducible.
- Versioning and event log for audit trail and replay.

## 9. Critical risks & countermeasures

| Risk | Countermeasure |
|---|---|
| Token bloat / attention drift | Subagent purging, selective data scoping |
| State chaos / race conditions | DB as SSOT, React Flow only view |
| Reproducibility disaster | Step-input persistence, event sourcing |
| Prompt injection in the tree | Little external attack surface (little external access), data/instruction separation |
| Cost/latency killer | Pragmatic validation matrix (funnel), self-correction loop with max. retries |

## 10. Implementation plan (from ideen.txt)

1. **Isomorphic data model** in Java (sealed interface + records) and React Flow (node.data mirrors Java structure).
2. **Separation of chat session (isolation) vs. workflow state (commit)** – sandbox vs. commit.
3. **Hybrid node types** (agent vs. service) in the engine.
4. **Upstream correction & invalidation flow** (epicenter → lineage check → rerun/dry-run).
5. **UI synchronization** in React Flow (color coding, chat drawer per node).

## 11. Relevance for QOC

- **Existing architecture:** QOC already has a workflow engine, agent catalogue, child sessions, journal, resilience. The isomorphic idea (workflow = context) is a possible next step for agent integration.
- **Runtime plan adaptation:** the doc (`runtime-plan-adaptation.md`) covers runtime repair; the isomorphic idea complements this with dynamic planning (OODA loop) and chat-session integration.
- **Recommendation:** Phase 0 (Goal→Plan) could be implemented as an OODA loop on top of the isomorphic model, with the supervisor (doc) taking over the decision logic.
" \
/home/torsten/dev/my-projects/2oc/qoc/docs/next/ideen-summary.md