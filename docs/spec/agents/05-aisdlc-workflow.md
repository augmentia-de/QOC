# 05 – Deterministic SDLC workflow orchestration

> **Purpose:** Map a document-defined SDLC process (phases, gates, change
> routing, retry budgets) onto the QOC workflow-runner so that the control plane
> is deterministic (graph topology + condition language) while only the leaf
> work (writing PRDs, architecture, code) stays model-driven.
> **Status:** 2026-11-14 · `partial` (Kontrollebene implementiert:
> ConditionEvaluator, GraphCompiler-Invarianten SDW-07/SDW-03, Built-in
> Check-Tools `test_runner`/`hash_checker`; ausstehend: Migration-Profil-Graph,
> HITL-Approval-Persistenz in workflow-ui)
> **Reference:** `workflow-runner` (`WorkflowDefinition`, `GraphCompiler`,
> `DefaultNodeDispatcher`, `ConditionEvaluator`, `CheckpointStore`,
> `WorkflowRunJournal`); source process: `sdlc/.agent/PHASES.md`,
> `sdlc/.opencode/agents/aisdlc-orchestrator.md`

## 1. Purpose

An AI SDLC orchestrator that interprets routing tables, gate conditions and
output contracts from prose is a non-determinism source at exactly the layer
that must be deterministic. This spec moves that layer into the workflow graph:
**topology replaces routing, the condition language replaces classification,
HITL nodes replace gate conversations, loop nodes replace enforced budgets.**
The model works only inside agent nodes.

## 2. Scope

**In scope:**
- Step graph for a document-defined SDLC process (spec review, requirements,
  architecture, stress test, TDD, implementation, QA/security, deploy) with
  gates and a delta fast-path.
- Node types, conditions, structured agent output and budgets for this mapping.
- The contract between workflow definition and role agents (`wf:Name:Version`).

**Out of scope:**
- The SDLC process content itself (defined by the process repo, referenced
  read-only via briefing input).
- Stack-specific role agents (they remain process-repo artifacts, mounted as
  agent configs).
- Parallel multi-instance execution beyond `fork`/`join` semantics.

## 3. Mapping (process concept → graph element)

| Process concept | Graph element | Enforced by |
|---|---|---|
| Spec review (human-started pre-flight) | `hitl` node before the first agent node | Runner pause/resume |
| Role agent step (requirements, architecture, impl, QA) | `agent` node, `agentRef: wf:<role>:<version>` | Agent registry, session per node |
| Change classification → delta vs. full path | `decision` node + `ConditionEvaluator` | Condition language |
| Gate (human approval) | `hitl` node with max-revisit counter | Loop/dispatcher semantics |
| Gate verification (tests green, coverage, traceability) | check node (`mcp_call` or script-backed agent) writing machine-readable state | Structured output, not LLM self-report |
| Contract freeze | `agent` node step emits `contract_hash` via `responseSchema`; check node compares hash | Condition language |
| Retry budget (`build_max`, `test_max`) | `loop` node, `maxIterations`, `exitCondition` | Runner counts iterations |
| Jump-back budget (`max_jump_backs`) | revisit edges into gate nodes + counter in `WorkflowContext` state | Dispatcher counts revisits |
| Orchestrator state file + run log | Engine journal via `WorkflowRunJournal` events | Event sourcing |
| Output contract `STATUS/COMMIT/OFFENE PUNKTE` | `responseSchema` on agent nodes (`status`, `commit`, `open_points`) | Structured output conversion |
| Output parsing ("tolerant regex") | no longer exists — output is validated JSON | Structured output converter |
| Path ownership (edit allowlists per role) | agent config tool permissions (deny-by-default) | Permission engine |

## 4. Graph shape

The canonical full-path graph (ReactFlow format, nodes + edges; the complete
definition with agents and conditions lives in
[`../../engineering/Workflow-SDLC.md`](../../engineering/Workflow-SDLC.md)):

```
hitl(spec-review) → agent(requirements) → decision(route)
    delta →  agent(change-brief) → loop(retry: tdd → impl → check) → hitl(gate2-light) → end
    full  →  agent(architecture) → hitl(gate1) → agent(simplify)
          → agent(stress-test) → check(freeze) → hitl(contract-freeze)
          → loop(retry: tdd → impl → check) → agent(security-qa) → hitl(gate2) → agent(deploy) → end
```

### 4.1 Node types used

| Node | Use | Constraints |
|---|---|---|
| `start` / `end` | exactly one of each | `GraphCompiler` |
| `agent` | one role step | `agentRef` required; `responseSchema` required for steps whose output drives routing |
| `decision` | change routing, verdict routing | every branch has `condition`; `defaultTarget` required |
| `hitl` | spec review, GATE 1, contract freeze, GATE 2, cutover gate | assignee `human`; revisit counter `maxIterations` |
| `loop` | retry bodies (tdd/impl/check) | `maxIterations` from process retry budget; `exitCondition` machine-checkable |
| `subworkflow` | cutover phase (migration mode) | definition version pinned |
- `check` (as `mcp_call` with `mcpServer: "builtin"`) | gate pre-verification | executes a built-in check tool (`test_runner`, `hash_checker`); writes `check_result` state; unknown tool fails deterministically |

### 4.2 Conditions

Only `ConditionEvaluator`-supported forms:

- `node_<id>.output.<field> == '<value>'` — routing on structured agent output
  (e.g. `n_route.input.contract_impact == 'internal'`).
- `status == '<value>'` — loop exit / retry.
- Legacy string operators (`contains`, `equals`) only where the compared value
  is a controlled enum, never free text.

The change block (`type`, `scope`, `contract_impact`, `breaking`) is resolved
by the requirements node's structured output; conditions may reference it
either flattened (`state.contract_impact`) or nested
(`state.change.contract_impact`) — the evaluator resolves dotted paths
through nested maps.

## 5. Agent contract

- Agents are addressed as `wf:<role>:<version>`; configs are immutable after
  first persist (existing workflow-runner semantics).
- Each agent node runs in its own session; the orchestrator-level
  "continue existing session" rule is expressed as resume semantics on the
  run, not as session reuse across roles.
- Structured output schema (minimum) for process-relevant steps:

```json
{
  "status": ["done", "done_with_open_points", "blocked"],
  "commit": "string",
  "open_points": [{ "id": "string", "severity": "string", "blocking": "boolean" }],
  "artifacts": ["string"]
}
```

- `blocked` and blocking `open_points` route through a `decision` node to the
  designated jump-back target; they are never "carried" informally.

## 6. Gates and budgets

| Gate | Node position | Entry condition | Exit |
|---|---|---|---|
| Spec gate | before `agent(requirements)` | human verdict recorded as state | `READY`/`READY_WITH_ASSUMPTIONS` → requirements; `BLOCKED` → run ends (PAUSED) |
| GATE 1 | after `agent(architecture)` | gate check state green | approve → continue; reject → revisit edge to architecture (counter ≤ `max_jump_backs`) |
| Contract freeze | after stress-test agent | verdict condition on structured output | `FAIL` → revisit; `PASS*` → freeze hash stored, later contract edits must route through an external/public decision edge back to architecture |
| GATE 2 (full) | after QA/security | pre-check node (tests, coverage, traceability) green | approve → deploy |
| GATE 2 (light) | delta path only | affected ACs check | approve → end |

- Budgets are node properties, not prompt statements. Exhaustion produces a
  deterministic `blocked` run state with a journal event — no prompt-based
  escalation.
- All gate entries and exits write journal events (`step_start`, `step_end`,
  `gate`, `check`, `jump_back`, `retry`).

## 7. Constraints

1. **ConditionEvaluator path splitting:** fixed (2026-11-14). The evaluator
   now supports `state.<key>` (incl. dotted paths into nested maps, e.g.
   `state.change.contract_impact`), `!=` comparisons and
   `node_<id>.output.<field>` with arbitrary node IDs. Covered by
   `ConditionEvaluatorTest` (12 tests).
2. **Gate bypass invariance:** implemented (2026-11-14). `GraphCompiler`
   rejects any start→end path that skips a `hitl` gate node unless it
   contains a delta-classification edge (edge condition or decision branch
   referencing `state.contract_impact`/`state.breaking`/`state.scope`).
   Covered by `GraphCompilerSdlcInvariantsTest` and the canonical-graph
   test `SdlcGraphDefinitionTest`.
3. **Verification is machine, not model:** implemented (2026-11-14).
   Built-in check tools (`mcpServer: "builtin"`): `test_runner` (build
   command, Surefire summary, JaCoCo coverage, traceability gaps →
   GREEN/RED) and `hash_checker` (SHA-256 vs. expected hash →
   STABLE/CHANGED). The dispatcher mirrors `status` into
   `state.check_result` for loop exit conditions.
4. **No prose state:** `state.json` and its `history` array are replaced by
   journal events; nothing process-relevant may live only in a document that
   the runner does not read.

## 8. Error handling

- Agent node fails or returns `blocked` → decision node routes to the defined
  revisit target; budget counters incremented; on exhaustion the run finishes
  `PAUSED` for human intervention.
- Runner crash → `CheckpointStore` restores context, run replays the pinned
  definition version (`contentHash`), never the current draft of the graph.
- Missing agent config for `wf:Name:Version` → run fails deterministically
  before any node executes (compile-time agent-ref validation).

## 9. Security

- Role agents keep their process-repo tool permissions, mounted via agent
  config; the workflow-runner host itself executes with a minimal permission
  set (deny-by-default, per `permissions/`).
- HITL approvals are stored (approval store), reference the run ID + node ID
  and the definition content hash, and are journal events — approvals are
  auditable and replayable.

## 10. Bounding and audit

- One journal event stream per run (`WorkflowRunJournal`), append-only,
  gapless sequence per run.
- `step_start`/`step_end` events carry: step id, agent id, definition version,
  attempt number, verdict state.
- Full replay of any run from the journal must reproduce every routing
  decision without model calls.

## 11. Limits and concurrency

- Sequential execution per run (1 subagent/step semantics preserved by
  topology). `fork`/`join` allowed only for read-only parallel analysis
  streams; write steps stay sequential until the parallel-streams profile is
  defined.
- Run-level concurrency: multiple runs of the same definition may execute
  concurrently (per-session isolation).

## 12. Invariants

- **SDW-01:** Routing decisions are made only by `decision` nodes evaluating
  `ConditionEvaluator` conditions over structured state — never by a model.
- **SDW-02:** Every gate is a `hitl` node whose entry state was produced by a
  machine check (check node or validated structured output).
- **SDW-03:** Retry and jump-back budgets are node properties enforced by the
  runner; exhaustion ends the run deterministically, never by prompt decision.
- **SDW-04:** Every process step's output that feeds routing is validated
  structured output (`responseSchema`); prose parsing of agent output is
  prohibited.
- **SDW-05:** A run replays against the pinned definition version
  (`contentHash`); graph changes never mutate a running or completed run.
- **SDW-06:** Every step start/end, gate decision, retry, jump-back and skill
  use is a journal event; no parallel undocumented state file is authoritative.
- **SDW-07:** The delta path may bypass GATE 1 only if the change block
  satisfies the conservative classification rule
  (`contract_impact == internal`, non-breaking, module scope) evaluated by a
  decision node, not by the agent that produced the change block.
- **SDW-08:** Human approvals reference run ID, node ID and definition hash
  and are stored events, not conversation fragments.

## 13. Adapter and runtime

- Implemented against `workflow-runner` (ReactFlow format, embedded QocEngine
  in-memory, optional Temporal for durable runs).
- Process-repo artifacts (workflows, phase prompts, agent definitions) are
  read-only inputs to agent configs; they are not executed as instructions by
  the runner.
- Rollout: full-path graph first (greenfield profile); delta subgraph and
  migration profile (0a/2b/cutover) follow once SDW-07 validation exists.

## 14. Best practices

- Keep the graph minimal: every node must map to a step in the process table;
  no "orchestrator reasoning" nodes.
- Freeze conditions before freeze gates in the same report artifact so the
  freeze check compares hashes, not quotes.
- Prefer flat state keys over nested path conditions.

## 15. References

| Topic | Where |
|---|---|
| Source process (steps, gates, budgets) | `sdlc/.agent/PHASES.md`, `sdlc/.agent/workflows/`, `sdlc/.opencode/agents/aisdlc-orchestrator.md` |
| Engineering analysis and full graph | [`../../engineering/Workflow-SDLC.md`](../../engineering/Workflow-SDLC.md) |
| Workflow model & validation | `workflow-runner/.../model/WorkflowDefinition.java`, `validation/GraphCompiler.java` |
| Deterministic branching | `runner/ConditionEvaluator.java`, `runner/DefaultNodeDispatcher.java` |
| Durability | `store/CheckpointStore.java`, `runner/WorkflowRunJournal.java`, `runner/WorkflowRunSnapshot.java` |
| Multi-agent handoff baseline | [`04-workflows.md`](./04-workflows.md) |
