# Plan Status Overview

Generated: 2026-11-22

## Done (fully implemented)

| File | Status |
|---|---|
| `plans/done/Hybrid.md` | Implemented — context_agent_executor node, systemPrompt, initialPrompt, compactionEnabled, buildFullContext, tests |
| `plans/done/WorkflowMode-ContextAgent.md` | Implemented — ArtifactRef/Store, ContextAssembler, guard/inject nodes, dynamic jumps, postGuard, ContextBudgetGate |
| `plans/done/ext-01-execution-isolation.md` | Implemented (S1-S4, S6) — SandboxedFileSystem, EgressProxy, SandboxLifecycle, SandboxReconciler, config. Open: S5 MicroVM, reconciler wiring, integration tests | Implemented — ArtifactRef/Store, ContextAssembler, guard/inject nodes, dynamic jumps, postGuard, ContextBudgetGate |
| `plans/done/ext-03-semantic-memory.md` | Implemented (core) — Domain, Port, Adapter (SQL+SQLite+InMemory), Tools, Importer, Extraction, Retrieval, ContextBuilder. Open: ToolRegistry agent-level gating, startup import, agent-loop preflight, StorageFactory |
| `plans/done/mobile-workflow-ui.md` | Implemented — Mobile support in reactflow (useIsMobile, responsive layout) |
| `plans/done/node-data-contracts-and-context-economy.md` | Implemented — All 8 phases (0-7), node data contract, tool outcomes, context economy |
| `plans/done/node-data-contracts-progress.md` | Implemented — Progress tracking for above plan |
| `plans/done/qoc-weaknesses-remediation.md` | Implemented — P0 (security/core) + P1 (performance/observability) done; P2 deferred |
| `plans/done/reactflow-menu-pages.md` | Implemented — All 10+ pages ported (Workflows, Agents, MCP, Generate, Runs, Observe, HITL, Testbench, Settings, Dashboard) |
| `plans/done/saga-undo-tools.md` | Implemented — M1-M4 (contract, registry, compensator, Engine wiring, tests); M5 deferred |
| `plans/done/strict-structured-outputs.md` | Implemented — Approach B (strict on ToolSpecification) via `qoc.structured-output.strict` flag |
| `plans/done/structured-output-schema-integration.md` | Implemented — Java source parsing, YAML parsing, SchemaResource endpoints, UI, tests |
| `plans/done/tool-call-leak-detection-and-retry.md` | Implemented — Tool-call leak detection, repair, retry |
| `plans/done/typesafe-decision-model-integration.md` | Implemented — `classify` node type, TypeSafeDecisionClient, tests |
| `plans/done/workflow-generate.md` | Implemented — WorkflowGenerator, WorkflowPlanParser, GenerateConfig, POST /workflows/generate, UI |

## Partially implemented

| File | Status |
|---|---|
| `plans/acp-adapter.md` | Partial — Transport + session CRUD (~30%); missing event translator, permission/elicitation bridges, advanced features, tests |
| `plans/async-approval.md` | Partial — Phase 1 (infra) done; Phase 2 (suspend/resume) partial; Phase 3 (granular) not started |
| `plans/rbac-implementation-plan.md` | Partial — Steps 1-7 done; Step 8 partial (ownerId only in WorkflowRunStarted); Steps 9-12 not done |
| `plans/subagent-crash-recovery.md` | Partial — Step 1 done; Step 2 (exact resume, durable invocation, turn checkpoint) open |
| `plans/tool-configuration.md` | Partial — Core (dynamic fetch, two-pane, permission chips, rule editor, presets) done; virtualization, accessibility, tests open |
| `plans/verify-on-stop-guards.md` | Partial — Data model, 1/3 loop points, prompt injection done; 2 loop points, exporter, paramPattern, tests open |

## Not implemented / Draft / Concept / Backlog

| File | Status |
|---|---|
| `plans/saga-orchestration.md` | Draft — M5-M10 not implemented (depends on saga-undo-tools M1-M4) |
| `plans/ui-improvements.md` | Backlog — Collection of UI improvement items; some partially addressed in reactflow |
| `plans/e2e-security-test.md` | Test plan — E2E verification scenarios for security paths |
| `plans/ideen2.md` | Structuring document — Bundles existing state, benefit, priorities |
| `plans/next/goal-driven-planning-comparison.md` | Concept — Compares goal-driven AI planning with deterministic workflow orchestration |
| `plans/next/ideen-summary.md` | Concept/Reference — Summarizes ideas from runtime-plan-adaptation |
| `plans/next/ideen.txt` | Ideas collection |
| `plans/next/runtime-plan-adaptation.md` | Concept — Supervisor/orchestrator for runtime plan adaptation (not implemented) |
| `plans/tests/ui-security-tests.md` | Test plan — UI security test scenarios |
| `plans/tests/ui-workflow-editor-tests.md` | Test plan — UI workflow editor test scenarios |

## Excluded (per user instruction)

| File | Reason |
|---|---|
| `plans/ext-02-provenance-governance.md` | Excluded |
| `plans/ext-04-decision-layer.md` | Excluded |
| `plans/Learnings.md` | Excluded |