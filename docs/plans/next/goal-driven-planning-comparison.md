# Goal-Driven AI Planning + Deterministic Workflow Orchestration

> **Status:** Concept/comparison (2026-11-22) — Not an implementation plan; compares goal-driven AI planning with deterministic workflow orchestration.
> **Sources:** User idea (Goal-Driven AI Planning) + `docs/plans/runtime-plan-adaptation.md`.  
> **Goal:** Merge both approaches and describe them as a unified roadmap.

---

## Part 1 – Current Idea: Goal-Driven AI Planning + Deterministic Orchestration

### Core Principle (Two Worlds)

1. **AI Layer (Planning):** The AI understands the initial state and the desired goal, uses its knowledge of third-party systems and tools, and breaks the path down into logical intermediate steps (decomposition).
2. **Deterministic Layer (Execution):** The designed path is translated into a fixed, reproducible format (workflow, code script, state machine), so that given the same initial state it is always executed exactly the same way and without errors – without the AI having to guess again at every step.

### Architecture (Four Phases)

1. **State & Goal Input:** The user defines the current state (e.g. "Unstructured PDF invoice sits in the S3 bucket") and the goal (e.g. "Data booked in SAP, customer has received confirmation email, case marked as 'done' in the CRM").
2. **Tool-Registry & Capability Mapping:** The AI has access to a list of known third-party systems and their interfaces (APIs, DBs, RPA tools). It knows which parameters a tool needs and what it returns.
3. **Pathfinding & Graph Generation (LLM/Agent):** The AI plans the sequence. Modern frameworks use structured outputs (JSON Mode / Pydantic) to create a directed graph (DAG) or a strict step-by-step sequence.
4. **Deterministic Execution Engine:** A workflow engine (no AI anymore!) works through this plan step by step. On errors, defined error-handling routines apply.

### Relevant Frameworks

**AI Planning (Drafting):**
- LangGraph (LangChain): state-based, deterministic graphs.
- CrewAI / AutoGen: specialized agents in a team (planner, API specialist, reviewer).
- Semantic Kernel (Microsoft): native planners from registered functions (plugins/APIs).

**Workflow Orchestration (Determinization):**
- Temporal.io / Camunda: top tier for deterministic workflows (retries, status tracking).
- n8n / Make: API-heavy workflows; AI can generate/adapt workflows.
- Apache Airflow: data-heavy / ETL-like paths.

**Interface Standardization:**
- OpenAPI / Swagger: AI reads interface descriptions and knows which endpoints to use for which sub-step.

### Typical Flow (Practical Example)

Request: "Migrate customer data from legacy system X into CRM Y and validate the email addresses."

1. AI planning recognizes:
   - Step 1: API query against legacy system X (Tool: `Legacy_API_Connector`).
   - Step 2: Data cleanup and validation (Tool: `Email_Validation_Service`).
   - Step 3: Record creation in CRM Y (Tool: `CRM_REST_API`).
2. Output as deterministic code/workflow: valid JSON or Python script (or n8n workflow).
3. Execution: classic script runner/engine executes the exact code – fast, safe, without unexpected AI creativity at runtime.

---

## Part 2 – Comparison with `runtime-plan-adaptation.md`

### Agreements

| Aspect | Pasted Ideas | runtime-plan-adaptation.md |
|---|---|---|
| Separation of planning vs. execution | AI plans, engine executes deterministically | Supervisor plans adaptation, engine executes deterministically |
| Structured plans (DAG/Graph) | JSON/Pydantic → DAG/step sequence | AdaptationPlan + GraphCompiler validation |
| Tool-Registry / Capabilities | OpenAPI/Swagger for interfaces | MCP-Tools, Agent-Refs, Output-Contracts |
| Validation before execution | implicit (structured output) | explicit: GraphCompiler + PlanValidator |
| Resilience/Retry | defined error handling | ErrorClassifier, RetryPolicy, TriageService, Circuit-Breaker |
| Journaling/Replay | not explicit | central: PlanVersionChanged, Adaptation*-Events, Resume |

### Differences & Gaps

**What the pasted ideas have that the doc (still) does not cover:**
1. **Goal→Plan Generation (Phase 0):** The doc assumes a Base-Plan already exists. What is missing is a module that generates an initial workflow DAG from current state + goal (LLM with structured output, validated by GraphCompiler).
2. **Tool-Registry as OpenAPI/Swagger Source:** The doc uses MCP-Tools/Agent-Refs, but no explicit interface standardization (OpenAPI) for AI planning.
3. **Framework Integrations:** LangGraph/CrewAI/Semantic Kernel and Temporal/Camunda/n8n are named in the pasted ideas; QOC has its own engine + workflow-runner, no external orchestrators.
4. **State & Goal Input as Explicit Phase:** The doc has `inputSchema` and `initial` state, but no dedicated "goal definition" phase.

**What the doc has that the pasted ideas do not cover:**
1. **Runtime Repair / Plan-Adaptation:** Base-Plan immutability, derived plans with lineage, rejoin anchors.
2. **Requirement-Gap Model:** Explicit input requirements, gap reasons, severity (ERROR/INFO).
3. **Blast Radius & Divergence Handling:** Static dependency analysis, deterministic repair modes (PATCH_ONLY, REPLAY_FROM, COMPENSATE_AND_REPLAY, ESCALATE).
4. **Session-Requery:** Resuming persisted child sessions to query missing fields.
5. **Idempotency/Side-Effect Gates:** Auto-replay only with declared idempotency/compensation (SAGA).
6. **Resume after Crash:** Journal-based recovery of the last accepted plan.

---

## Part 3 – Integration Plan (Phases)

| Phase | Content | Source |
|---|---|---|
| **Phase 0 (new)** | Goal→Plan generation: LLM with Tool-Registry (MCP/OpenAPI) produces initial DAG; validation by GraphCompiler; Base-Plan version 1.0.0 | Pasted Ideas |
| **Phase A (Doc)** | RequirementChecker + Session-Requery + Reroute-Fallback | Doc |
| **Phase B (Doc)** | AdaptationPlan + RuntimePlan + PlanValidator + InsertNode/PatchNode; journal versioning; Resume | Doc |
| **Phase C (Doc)** | LlmSupervisor via TriageService; HITL; UI/API | Doc |
| **Phase D (Doc)** | Budgets, metrics, hardening | Doc |

**Core Decision:** The Supervisor (Doc) is not only used for repair, but also for Phase 0 (Goal→Plan) as a `HumanSupervisor`/`LlmSupervisor` variant that generates an initial adaptation plan on an empty Base-Plan. This keeps the architecture unified: everything runs through `AdaptationPlan` + `GraphCompiler`.

### Next Steps (Plan, no changes)

1. **Specify Phase 0:** Define Tool-Registry schema (MCP-Tools + OpenAPI); LLM prompt template for Goal→DAG; output schema (JSON) validatable by GraphCompiler.
2. **Implement Phase A:** RequirementChecker, Session-Requery, Reroute; tests (Doc test plan points 1–3).
3. **UI/API:** `GET /run/{id}/plan`, badge "Adapted (n)" (Doc §10).
4. **No external orchestrators (Temporal/Camunda):** QOC keeps its own engine; n8n export optional as a later feature.

---

## References

- `docs/plans/runtime-plan-adaptation.md` (original concept)
- `docs/engineering/ReAct-Workflow.md`
- `docs/resilience.md`
- `docs/engineering/Task-Orchestration.md`
- `docs/engineering/SAGA.md`