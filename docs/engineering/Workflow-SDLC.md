# Workflow-SDLC – Steering the AI-SDLC process as a QOC workflow graph

> **Purpose:** Operational implementation of the analysis "model the SDLC
> workflow deterministically": The aisdlc orchestrator (OpenCode) is expressed
> as a `WorkflowDefinition` in the QOC workflow-runner. Control plane = graph
> + conditions, model work = agent nodes.
>
> **Status:** 2026-11-14 (Design; Phase 1 of the migration plan implemented
> — ConditionEvaluator; implementation pending)
>
> **References:** [`Workflow.md`](./Workflow.md),
> [`ReactFlow-temporal.md`](./ReactFlow-temporal.md),
> [`../spec/agents/05-aisdlc-workflow.md`](../spec/agents/05-aisdlc-workflow.md),
> Source of the process: `sdlc/.agent/PHASES.md` (Steps 0s–9, Delta-Flow)

---

## 1. Problem

Today's aisdlc orchestrator is itself an LLM that interprets the control plane
from prose:

| Control | Source | Weakness |
|---|---|---|
| Routing (Steps, Delta-Flow) | `PHASES.md` table + orchestrator prompt | Model "reads" routing rules |
| Change classification | `change` block in `briefing.json` vs. prose rule | Fuzzy comparison |
| Gates | `question` tool + prose verification | Verification = self-attestation |
| Output parsing | `STATUS/COMMIT/OPEN POINTS`, explicitly "tolerant regex" | Parse nondeterminism |
| Budgets (retry, jump-back) | "Enforced" by the orchestrator LLM | Only as hard as the model is disciplined |
| State | `state.json` + `history` + `run-log.jsonl`, written by hand | Events can be missing/wrong (see Spec-Gate incident: `artifact_present: false`) |

The role agents themselves are already well secured (path ownership via
`edit` allowlists, no shell catch-all, `steps` budget). The gap is the
**orchestration**.

## 2. Principle

> The LLM works only **inside** the agent nodes. Routing, branching, gates,
> budgets and audit are deterministic via graph topology, `ConditionEvaluator`
> and runner semantics.

| SDLC concept | QOC mechanism |
|---|---|
| Spec review 0s (human-started) | `hitl` node before the first agent node |
| Role-agent step | `agent` node, `agentRef: wf:<role>:<version>`, session per node |
| Delta vs. Full | `decision` node + `ConditionEvaluator` |
| Gate | `hitl` node (pause/resume), pre-check via check node |
| Contract freeze | Structured output `contract_hash`, comparison via condition |
| Retry budget | `loop` node `maxIterations` + `exitCondition` |
| Jump-back budget | Revisit edges + counter in `WorkflowContext` |
| `state.json`/`run-log` | `WorkflowRunJournal` (event sourcing into the engine journal) |
| Output contract | `responseSchema` → validated JSON instead of regex |
| Path ownership | Agent-config permissions (deny-by-default), unchanged |

## 3. The graph (ReactFlow format, complete definition)

```json
{
  "id": "aisdlc",
  "name": "aisdlc-sdlc",
  "version": "1.0.0",
  "nodes": [
    { "id": "n_start", "type": "start",
      "data": { "title": "Run start (human: spec review done)" } },

    { "id": "n_spec", "type": "hitl",
      "data": { "title": "SPEC-GATE (0s verdict)", "assignee": "human",
                "maxIterations": 1 } },

    { "id": "n_req", "type": "agent",
      "data": { "title": "Step 1 Requirements", "agentRef": "ag_req",
                "responseSchema": { "type": "object",
                  "properties": {
                    "status":  { "type": "string", "enum": ["done", "done_with_open_points", "blocked"] },
                    "commit":  { "type": "string" },
                    "open_points": { "type": "array" },
                    "artifacts": { "type": "array" },
                    "change": { "type": "object",
                      "properties": {
                        "type":           { "type": "string", "enum": ["hotfix","patch","feature","refactor"] },
                        "scope":          { "type": "string", "enum": ["local","module","cross-module"] },
                        "contract_impact":{ "type": "string", "enum": ["none","internal","external","public"] },
                        "breaking":       { "type": "boolean" }
                      } } },
                  "required": ["status","change"] } } },

    { "id": "n_route", "type": "decision",
      "data": { "title": "Change routing (delta vs. full)",
        "branches": [
          { "branchId": "delta_full",
            "condition": "state.breaking == 'true'", "target": "n_arch" },
          { "branchId": "delta_ext",
            "condition": "state.contract_impact == 'external'", "target": "n_arch" },
          { "branchId": "delta_ext2",
            "condition": "state.contract_impact == 'public'", "target": "n_arch" },
          { "branchId": "delta_scope",
            "condition": "state.scope == 'cross-module'", "target": "n_arch" },
          { "branchId": "delta_go",
            "condition": "state.contract_impact == 'internal'", "target": "n_tdd_delta" }
        ],
        "defaultTarget": "n_arch" } },

    { "id": "n_brief", "type": "agent",
      "data": { "title": "Step 1a Change brief", "agentRef": "ag_req",
                "responseSchema": { "type": "object",
                  "properties": { "status": { "type": "string" },
                                  "commit": { "type": "string" },
                                  "affected_paths": { "type": "array" } } } } },

    { "id": "n_tdd_delta", "type": "loop",
      "data": { "title": "Delta TDD (5a) + Impl (6a) + Check",
                "maxIterations": 5,
                "exitCondition": "state.check_result == 'GREEN'",
                "bodySteps": ["n_impl_body_delta", "n_check_delta"] } },

    { "id": "n_impl_body_delta", "type": "agent",
      "data": { "title": "Steps 5a/6a execution", "agentRef": "ag_exec",
                "responseSchema": { "type": "object",
                  "properties": { "status": { "type": "string" },
                                  "commit": { "type": "string" } } } } },

    { "id": "n_check_delta", "type": "mcp_call",
      "data": { "title": "Check: mvn test + affected ACs",
                "mcpTool": "test_runner", "jsonOutput": true } },

    { "id": "n_gate2l", "type": "hitl",
      "data": { "title": "GATE 2 light (delta QA 8a approval)",
                "assignee": "human" } },

    { "id": "n_arch", "type": "agent",
      "data": { "title": "Step 2 Architecture", "agentRef": "ag_arch",
                "responseSchema": { "type": "object",
                  "properties": { "status": { "type": "string" },
                                  "commit": { "type": "string" },
                                  "open_points": { "type": "array" } } } } },

    { "id": "n_gate1", "type": "hitl",
      "data": { "title": "GATE 1 (direction review)", "assignee": "human",
                "maxIterations": 2 } },

    { "id": "n_simplify", "type": "agent",
      "data": { "title": "Step 3 Simplify", "agentRef": "ag_arch",
                "responseSchema": { "type": "object",
                  "properties": { "status": { "type": "string" },
                                  "commit": { "type": "string" } } } } },

    { "id": "n_stress", "type": "agent",
      "data": { "title": "Step 4 Grill-Me stress test", "agentRef": "ag_arch",
                "responseSchema": { "type": "object",
                  "properties": {
                    "status": { "type": "string", "enum": ["PASS","PASS_WITH_FINDINGS","FAIL"] },
                    "commit": { "type": "string" },
                    "contract_hash": { "type": "string" },
                    "open_decisions": { "type": "array" } } } } },

    { "id": "n_route_stress", "type": "decision",
      "data": { "title": "Stress verdict",
        "branches": [
          { "branchId": "fail", "condition": "node_n_stress.output.status == 'FAIL'",
            "target": "n_gate1" },
          { "branchId": "pass", "condition": "node_n_stress.output.status == 'PASS'",
            "target": "n_freeze_check" },
          { "branchId": "pass_w",
            "condition": "node_n_stress.output.status == 'PASS_WITH_FINDINGS'",
            "target": "n_freeze_check" }
        ],
        "defaultTarget": "n_gate1" } },

    { "id": "n_freeze_check", "type": "mcp_call",
      "data": { "title": "Check: contract hash stable",
                "mcpTool": "hash_checker", "jsonOutput": true } },

    { "id": "n_gate_freeze", "type": "hitl",
      "data": { "title": "Contract freeze (human approval)", "assignee": "human" } },

    { "id": "n_impl", "type": "loop",
      "data": { "title": "Steps 5–7: TDD → Impl → Refactor",
                "maxIterations": 5,
                "exitCondition": "state.check_result == 'GREEN'",
                "bodySteps": ["n_impl_body", "n_check"] } },

    { "id": "n_impl_body", "type": "agent",
      "data": { "title": "Steps 5–7 execution", "agentRef": "ag_exec",
                "responseSchema": { "type": "object",
                  "properties": { "status": { "type": "string" },
                                  "commit": { "type": "string" } } } } },

    { "id": "n_check", "type": "mcp_call",
      "data": { "title": "Check: mvn test + coverage + traceability",
                "mcpTool": "test_runner", "jsonOutput": true } },

    { "id": "n_qa", "type": "agent",
      "data": { "title": "Step 8 Security & QA", "agentRef": "ag_qa",
                "responseSchema": { "type": "object",
                  "properties": { "status": { "type": "string" },
                                  "commit": { "type": "string" },
                                  "criticals": { "type": "integer" },
                                  "highs": { "type": "integer" },
                                  "traceability_gaps": { "type": "integer" } } } } },

    { "id": "n_route_qa", "type": "decision",
      "data": { "title": "QA gate condition",
        "branches": [
          { "branchId": "clean",
            "condition": "state.traceability_gaps == '0'", "target": "n_gate2" },
          { "branchId": "gaps", "condition": "state.traceability_gaps != '0'",
            "target": "n_impl" }
        ],
        "defaultTarget": "n_impl" } },

    { "id": "n_gate2", "type": "hitl",
      "data": { "title": "GATE 2 (release approval)", "assignee": "human" } },

    { "id": "n_deploy", "type": "agent",
      "data": { "title": "Step 9 Deploy", "agentRef": "ag_qa",
                "responseSchema": { "type": "object",
                  "properties": { "status": { "type": "string" },
                                  "commit": { "type": "string" } } } } },

    { "id": "n_end", "type": "end", "data": { "title": "Run complete" } }
  ],
  "edges": [
    { "id": "e01", "source": "n_start",  "target": "n_spec" },
    { "id": "e02", "source": "n_spec",   "target": "n_req" },
    { "id": "e03", "source": "n_req",    "target": "n_route" },
    { "id": "e04", "source": "n_route",  "target": "n_brief" },
    { "id": "e05", "source": "n_brief",  "target": "n_tdd_delta" },
    { "id": "e06", "source": "n_tdd_delta", "target": "n_gate2l" },
    { "id": "e07", "source": "n_gate2l", "target": "n_end" },
    { "id": "e08", "source": "n_route",  "target": "n_arch" },
    { "id": "e09", "source": "n_arch",   "target": "n_gate1" },
    { "id": "e10", "source": "n_gate1",  "target": "n_simplify" },
    { "id": "e11", "source": "n_simplify", "target": "n_stress" },
    { "id": "e12", "source": "n_stress", "target": "n_route_stress" },
    { "id": "e13", "source": "n_route_stress", "target": "n_freeze_check" },
    { "id": "e14", "source": "n_freeze_check", "target": "n_gate_freeze" },
    { "id": "e15", "source": "n_gate_freeze",  "target": "n_impl" },
    { "id": "e16", "source": "n_impl",   "target": "n_qa" },
    { "id": "e17", "source": "n_qa",     "target": "n_route_qa" },
    { "id": "e18", "source": "n_route_qa", "target": "n_gate2" },
    { "id": "e19", "source": "n_gate2",  "target": "n_deploy" },
    { "id": "e20", "source": "n_deploy", "target": "n_end" },
    { "id": "r1",  "source": "n_route_stress", "target": "n_gate1" },
    { "id": "r2",  "source": "n_route_qa",     "target": "n_impl" }
  ],
  "agents": [
    { "id": "ag_req",  "name": "aisdlc-requirements", "version": "1.0.0" },
    { "id": "ag_arch", "name": "aisdlc-architecture", "version": "1.0.0" },
    { "id": "ag_exec", "name": "aisdlc-execution",    "version": "1.0.0" },
    { "id": "ag_qa",   "name": "aisdlc-qa",           "version": "1.0.0" }
  ]
}
```

**Cross-references from the prose definition:** The agents keep their
`.opencode` roles (tool allowlists, `docs/*` paths, commit convention from
`PHASES.md`) as **agent config** — the role files remain process-repo
artifacts and are mounted as config, not interpreted as prompts. The per-step
commit (`docs(sdlc-<n>): ...`) stays an agent obligation and is verified via
`commit` in the structured output.

## 4. Topology compilation (GraphCompiler checks, extended)

Existing checks (node types, agentRef resolving, exactly one end node) plus
two new rules — implemented in `GraphCompiler`:

1. **Gate-bypass invariant (SDW-07):** Every start→end path that bypasses a
   `hitl` gate node is only legal if it contains at least one delta edge — an
   edge (edge condition or decision branch) whose condition references the
   conservative change classification (`state.contract_impact`,
   `state.change.contract_impact`, `state.breaking`, `state.scope`). Violation
   → validation error.
2. **Budget binding (SDW-03):** A cycle is legal if it passes at least one
   counted node (`loop`, or `hitl` with `maxIterations > 1`) — whose budget
   bounds the repetition. An edge between uncounted nodes is illegal only when
   it lies on a completely uncounted cycle. Revisit cycles (`r1`, `r2`) are
   therefore valid with counted gates/loops; `TopologicalSort` cycles are
   reported only for uncounted cycles.
3. **Canonical graph as test:** `SdlcGraphDefinitionTest` parses the
   definition from §3 and validates it against the compiler — every graph
   change in the doc must be mirrored by the test (executable contract).

### 4.1 Conditions

Only `ConditionEvaluator` forms (see `ConditionEvaluatorTest`, 12 tests):

- `state.<key> == '<value>'` / `!=` — including dotted paths in nested maps
  (`state.change.contract_impact`). Unknown keys compare as `null`.
- `node_<id>.output[.<field>] == '<value>'` — node-output lookup.
- `status == '<value>'` — legacy alias for `lastStatus`.
- Legacy string operators (`contains:` etc.) only against controlled enums.

**Important for the runtime:** The navigator (`getNextNodeId`) routes via
edge conditions (not `branches`); on no match it falls back to the first edge
in definition order. In the canonical graph this is deliberately the
full-path edge `d1` — the fallback thus conservatively lands on the full
path, never on delta. Recommendation (open item 6): switch the fallback
mechanism to `defaultTarget` semantics.

### 4.2 Check nodes (built-in tools, SDW-02)

Check nodes are `mcp_call` nodes with `mcpServer: "builtin"` and are executed
locally by `DefaultNodeDispatcher` (no MCP transport, deterministic):

| Tool | Purpose | Output state |
|---|---|---|
| `test_runner` | Run build command (default `mvn test`), parse Surefire summary, JaCoCo CSV coverage, traceability gaps | `status` GREEN/RED, `tests_run`, `failures`, `errors`, `coverage`, `traceability_gaps`, `exit_code`, `duration_ms` |
| `hash_checker` | SHA-256 of a file vs. expected hash (arg, state `contract_hash` or node output) | `status` STABLE/CHANGED/MISSING/NO_EXPECTED_HASH, `hash`, `expected_hash` |

- The dispatcher additionally writes the `status` into the state key
  `check_result` — loop `exitCondition`s read exactly this key
  (`state.check_result == 'GREEN'`).
- Unknown `mcpTool` with `mcpServer: "builtin"` → node fails deterministically
  (`UNKNOWN_TOOL`), no silent pass-through.
- Live verification: `SDLC_LIVE_CHECK=1 mvn -pl workflow-runner test
  -Dtest=TestRunnerLiveIT` (real `mvn test` in the module → GREEN).

## 5. Execution

| Aspect | Behaviour |
|---|---|
| Start | `POST /workflows/run` with definition ID + input (raw ticket). Human has completed spec review; verdict is present in state. |
| Pause | `hitl` node → run status `PAUSED`, journal event `gate`; UI approval dialog, resume via signal (`decide`). |
| Retry | `loop` node counts; `exitCondition` reads `state.check_result` from the check node (`jsonOutput: true`). |
| Jump-back | Revisit edges (`r1`, `r2`) increment `state.jump_backs[<target>]`; ≥ `maxIterations` → run ends deterministically `PAUSED` with journal event. |
| Freeze | `n_stress` provides `contract_hash`; `n_freeze_check` compares hash against file system; difference → decision back to `n_arch`. |
| Crash | `CheckpointStore` restore; replay against definition version + `contentHash` (snapshot), never against the current graph draft. |
| Audit | `WorkflowRunJournal`: `step_start/step_end/gate/check/retry/jump_back` per run, append-only. |

## 6. Coverage against `PHASES.md`

| PHASES step | Node(s) | Note |
|---|---|---|
| 0s Spec review | `n_spec` (hitl) | human-started; verdict as state input |
| 0a/0 Migration assessment | (not in greenfield graph) | Migration profile as separate graph (`n_arch_mig`, `n_2b`, cutover `hitl`) — pending |
| 1 Requirements | `n_req` | structured incl. `change` block |
| 1a Change brief | `n_brief` | delta branch |
| 2 Architecture | `n_arch` | |
| 2b Migration plan | — | Migration profile |
| 3 Simplify | `n_simplify` | |
| 4 Grill-Me stress | `n_stress` + `n_route_stress` + `n_freeze_check` | Contract freeze |
| 5 TDD / 5a Delta-TDD | Body of the `loop` (`n_impl_body*`) | |
| 6 Impl / 6a Delta-Impl | Body of the `loop` | |
| 7 Refactoring | In loop body (iteration) | alternatively own node after freeze gate |
| 8 Security | `n_qa` + `n_route_qa` | traceability check as condition |
| 8a Delta-QA | `n_gate2l` | light gate |
| 9 Deploy | `n_deploy` | |
| Cutover gate | — | Migration profile |

## 7. Migration plan

| Phase | Task | Effort |
|---|---|---|
| 1 | ConditionEvaluator: `state.<key>` (dotted), `!=`, `node_<id>.output.<field>`; tests | **done** (2026-11-14) |
| 2 | GraphCompiler extensions: gate-bypass invariant, budget binding | **done** (2026-11-14) — SDW-07 bypass detection (edge conditions + decision branches), SDW-03 cycle anchoring (counted nodes legalise revisit cycles); canonical graph validated in `SdlcGraphDefinitionTest` |
| 3 | Check nodes: `test_runner` and `hash_checker` as built-in check tools (`mcpServer: "builtin"`; `mvn test` parsing, JaCoCo CSV, traceability gaps, SHA-256 contract comparison) | **done** (2026-11-14) — `CheckTools` + `CheckTool` interface; 13 tests |
| 4 | Mount role-agent configs (`wf:aisdlc-*:1.0.0`), responseSchemas per role | 1–2 days |
| 5 | Persist definition above, verify runs in workflow-runner + workflow-ui | 1–2 days |
| 6 | Migration profile (0a/2b/cutover) as second graph | 2–3 days |
| **Total** | | **~8–11 days** (phases 1–3 done) |

## 8. Open items

1. **ConditionEvaluator:** fixed (2026-11-14, `ConditionEvaluatorTest`, 12
   tests): `state.<key>` with dotted paths (`state.change.contract_impact`),
   `!=`, and `node_<id>.output.<field>` with arbitrary node IDs. Unknown keys
   compare as `null` (`==` → false, `!=` → true, conservative routing).
2. **Dedup rule** ("continue existing session") vs. session-per-node:
   decision open — proposal: session reuse as run-resume semantics within the
   same node attempt, not cross-role.
3. **Parallel streams** (`fork`/`join`): only for read-only analysis; real
   parallelism via subworkflow/Temporal activities — profile defined on demand
   only.
4. **Migration profile:** graph 0a/2b/cutover missing (see §6, §7 phase 6).
5. **Approval storage:** HITL approvals must reference run ID + node ID +
   `contentHash` (SDW-08) — UI track (workflow-ui) open.
6. **Runtime navigator fallback:** `WorkflowStepProviderImpl.getNextNodeId`
   falls back to `outgoing.get(0)` on non-matching edge conditions (first
   edge in definition order) — not conservative. Recommendation: on no match
   → `defaultTarget` semantics or deterministic error; in the canonical graph
   the first edge is deliberately the full-path edge (`d1`), so the fallback
   turns out conservative.
7. **Check-tool extensions:** `test_runner` reads coverage from JaCoCo CSV
   (if present) and traceability gaps from
   `docs/requirements/traceability_matrix.json` (`blocking_gaps`); both
   configurable via `mcpArgs`. QOC itself has no JaCoCo — the check then
   stays null and does not block (coverage_min ignored).

## 9. References

| Topic | Where |
|---|---|
| Operative spec | [`../spec/agents/05-aisdlc-workflow.md`](../spec/agents/05-aisdlc-workflow.md) |
| Source process | `sdlc/.agent/PHASES.md`, `sdlc/.agent/workflows/`, `sdlc/.opencode/agents/aisdlc-orchestrator.md` |
| Workflow-runner architecture | [`Workflow.md`](./Workflow.md) |
| ReactFlow + Temporal | [`ReactFlow-temporal.md`](./ReactFlow-temporal.md) |
| Node semantics | `workflow-runner/runner/DefaultNodeDispatcher.java` |
| Conditions | `workflow-runner/runner/ConditionEvaluator.java` |
