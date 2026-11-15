# Workflow-SDLC – Steuerung des AI-SDLC-Prozesses als QOC-Workflow-Graph

> **Zweck:** Operative Umsetzung der Analyse "SDLC-Workflow deterministisch
> abbilden": Der aisdlc-Orchestrator (OpenCode) wird als `WorkflowDefinition`
> im QOC workflow-runner ausgedrückt. Kontrollebene = Graph + Conditions,
> Modellarbeit = Agent-Nodes.
>
> **Status:** 2026-11-14 (Design; Phase 1 des Migrationsplans umgesetzt
> — ConditionEvaluator; Umsetzung ausstehend)
>
> **Referenzen:** [`Workflow.md`](./Workflow.md),
> [`ReactFlow-temporal.md`](./ReactFlow-temporal.md),
> [`../spec/agents/05-aisdlc-workflow.md`](../spec/agents/05-aisdlc-workflow.md),
> Quelle des Prozesses: `sdlc/.agent/PHASES.md` (Steps 0s–9, Delta-Flow)

---

## 1. Problem

Der heutige aisdlc-Orchestrator ist selbst ein LLM, das die Kontrollebene aus
Prosa interpretiert:

| Kontrolle | Quelle | Schwäche |
|---|---|---|
| Routing (Steps, Delta-Flow) | `PHASES.md`-Tabelle + Orchestrator-Prompt | Modell "liest" Routing-Regeln |
| Change-Klassifikation | `change`-Block in `briefing.json` vs. Prosa-Regel | Fuzzy-Vergleich |
| Gates | `question`-Tool + Prosa-Verifikation | Verifikation = Selbstzuschreibung |
| Output-Parsing | `STATUS/COMMIT/OFFENE PUNKTE`, explizit "tolerant regex" | Parse-Nichtdeterminismus |
| Budgets (retry, jump-back) | Vom Orchestrator-LLM "erzwungen" | Nur so hart wie das Modell diszipliniert ist |
| State | `state.json` + `history` + `run-log.jsonl`, von Hand geschrieben | Events können fehlen/falsch sein (siehe Spec-Gate-Vorfall: `artifact_present: false`) |

Die Rollen-Agenten selbst sind schon gut abgesichert (Path-Ownership via
`edit`-Allowlists, kein Shell-Catch-all, `steps`-Budget). Die Lücke ist die
**Orchestrierung**.

## 2. Prinzip

> Das LLM arbeitet nur **innerhalb** der Agent-Nodes. Routing, Verzweigung,
> Gates, Budgets und Audit werden durch Graph-Topologie, `ConditionEvaluator`
> und Runner-Semantik deterministisch.

| SDLC-Konzept | QOC-Mechanismus |
|---|---|
| Spec-Review 0s (human-started) | `hitl`-Node vor dem ersten Agent-Node |
| Role-Agent-Step | `agent`-Node, `agentRef: wf:<role>:<version>`, Session pro Node |
| Delta vs. Full | `decision`-Node + `ConditionEvaluator` |
| Gate | `hitl`-Node (Pause/Resume), Vorcheck durch Check-Node |
| Contract-Freeze | Strukturierter Output `contract_hash`, Vergleich per Condition |
| Retry-Budget | `loop`-Node `maxIterations` + `exitCondition` |
| Jump-Back-Budget | Revisit-Kanten + Counter im `WorkflowContext` |
| `state.json`/`run-log` | `WorkflowRunJournal` (Event-Sourcing ins Engine-Journal) |
| Output-Contract | `responseSchema` → validiertes JSON statt Regex |
| Path-Ownership | Agent-Config-Permissions (deny-by-default), unverändert |

## 3. Der Graph (ReactFlow-Format, vollständige Definition)

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

**Querverweise aus der Prosa-Definition:** Die Agenten behalten ihre
`.opencode`-Rollen (Tool-Allowlists, `docs/*`-Pfade, Commit-Konvention aus
`PHASES.md`) als **Agent-Config** — die Rollen-Dateien bleiben
Prozess-Repo-Artefakte und werden als Config gemountet, nicht als Prompt
interpretiert. Der Commit je Step (`docs(sdlc-<n>): ...`) bleibt Agent-Pflicht
und wird über `commit` im strukturierten Output verifiziert.

## 4. Topologie-Kompilierung (GraphCompiler-Checks, erweitert)

Bestehende Checks (Node-Typen, agentRef-Resolving, genau ein End-Node) plus
zwei neue Regeln — implementiert in `GraphCompiler`:

1. **Gate-Bypass-Invariante (SDW-07):** Jeder Start→End-Pfad, der einen
   `hitl`-Gate-Node umgeht, ist nur legal, wenn er mindestens eine
   Delta-Kante enthält — eine Kante (Edge-Condition oder Decision-Branch),
   deren Condition die konservative Change-Klassifikation referenziert
   (`state.contract_impact`, `state.change.contract_impact`, `state.breaking`,
   `state.scope`). Verstoß → Validierungsfehler.
2. **Budget-Bindung (SDW-03):** Ein Zyklus ist legal, wenn er mindestens einen
   gezählten Node passiert (`loop`, oder `hitl` mit `maxIterations > 1`) —
   dessen Budget begrenzt die Wiederholung. Eine Kante zwischen unzählten
   Nodes ist illegal nur dann, wenn sie auf einem komplett unzählten Zyklus
   liegt. Revisit-Zyklen (`r1`, `r2`) sind daher mit gezählten Gates/Loops
   valide; `TopologicalSort`-Cycles werden nur noch für unzählte Zyklen
   gemeldet.
3. **Kanonischer Graph als Test:** `SdlcGraphDefinitionTest` parst die
   Definition aus §3 und validiert sie gegen den Compiler — jede Graph-
   Änderung im Doc muss den Test spiegeln (ausführbarer Contract).

## 4.1 Conditions

Nur `ConditionEvaluator`-Formen (siehe `ConditionEvaluatorTest`, 12 Tests):

- `state.<key> == '<value>'` / `!=` — inkl. dotted paths in nested maps
  (`state.change.contract_impact`). Unknown keys vergleichen als `null`.
- `node_<id>.output[.<field>] == '<value>'` — Node-Output-Lookup.
- `status == '<value>'` — Legacy-Alias für `lastStatus`.
- Legacy-String-Operatoren (`contains:` etc.) nur gegen kontrollierte Enums.

**Wichtig für die Runtime:** Der Navigator (`getNextNodeId`) routet über
Kanten-Conditions (nicht `branches`); ohne Match fällt er auf die erste
Kante in Definitionsreihenfolge zurück. Im kanonischen Graph ist das
bewusst die Full-Path-Kante `d1` — der Fallback fällt damit konservativ
auf den vollständigen Pfad, nie auf Delta. Empfehlung (offener Punkt 6):
Fallback-Mechanik auf `defaultTarget`-Semantik umstellen.

## 4.2 Check-Nodes (Built-in-Tools, SDW-02)

Check-Nodes sind `mcp_call`-Nodes mit `mcpServer: "builtin"` und werden vom
`DefaultNodeDispatcher` lokal ausgeführt (kein MCP-Transport, deterministisch):

| Tool | Zweck | Output-State |
|---|---|---|
| `test_runner` | Build-Command ausführen (Default `mvn test`), Surefire-Summary parsen, JaCoCo-CSV-Coverage, Traceability-Gaps | `status` GREEN/RED, `tests_run`, `failures`, `errors`, `coverage`, `traceability_gaps`, `exit_code`, `duration_ms` |
| `hash_checker` | SHA-256 einer Datei vs. erwarteter Hash (Arg, State `contract_hash` oder Node-Output) | `status` STABLE/CHANGED/MISSING/NO_EXPECTED_HASH, `hash`, `expected_hash` |

- Der Dispatcher schreibt den `status` zusätzlich in den State-Key
  `check_result` — Loop-`exitCondition`s lesen genau diesen Key
  (`state.check_result == 'GREEN'`).
- Unbekanntes `mcpTool` bei `mcpServer: "builtin"` → Node schlägt
  deterministisch fehl (`UNKNOWN_TOOL`), kein stiller Durchlauf.
- Live-Verifikation: `SDLC_LIVE_CHECK=1 mvn -pl workflow-runner test
  -Dtest=TestRunnerLiveIT` (echtes `mvn test` im Modul → GREEN).

## 5. Ausführung

| Aspekt | Verhalten |
|---|---|
| Start | `POST /workflows/run` mit Definition-ID + Input (Rohticket). Human hat Spec-Review abgeschlossen; Verdict liegt als State vor. |
| Pause | `hitl`-Node → Run-Status `PAUSED`, Journal-Event `gate`; UI-Approval-Dialog, Resume via Signal (`decide`). |
| Retry | `loop`-Node zählt; `exitCondition` liest `state.check_result` aus dem Check-Node (`jsonOutput: true`). |
| Jump-Back | Revisit-Kanten (`r1`, `r2`) inkrementieren `state.jump_backs[<target>]`; ≥ `maxIterations` → Run endet deterministisch `PAUSED` mit Journal-Event. |
| Freeze | `n_stress` liefert `contract_hash`; `n_freeze_check` vergleicht Hash gegen File-System; Differenz → Decision zurück zu `n_arch`. |
| Crash | `CheckpointStore` restore; Replay gegen Definition-Version + `contentHash` (Snapshot), nie gegen den aktuellen Graph-Draft. |
| Audit | `WorkflowRunJournal`: `step_start/step_end/gate/check/retry/jump_back` je Run, append-only. |

## 6. Abdeckung gegenüber `PHASES.md`

| PHASES-Step | Node(s) | Anmerkung |
|---|---|---|
| 0s Spec review | `n_spec` (hitl) | human-gestartet; Verdict als State-Eingabe |
| 0a/0 Migration assessment | (nicht im Greenfield-Graph) | Migration-Profil als eigener Graph (`n_arch_mig`, `n_2b`, Cutover-`hitl`) — ausstehend |
| 1 Requirements | `n_req` | strukturiert inkl. `change`-Block |
| 1a Change brief | `n_brief` | Delta-Zweig |
| 2 Architecture | `n_arch` | |
| 2b Migration plan | — | Migration-Profil |
| 3 Simplify | `n_simplify` | |
| 4 Grill-Me stress | `n_stress` + `n_route_stress` + `n_freeze_check` | Contract-Freeze |
| 5 TDD / 5a Delta-TDD | Body des `loop` (`n_impl_body*`) | |
| 6 Impl / 6a Delta-Impl | Body des `loop` | |
| 7 Refactoring | im Loop-Body (Iteration) | alternativ eigener Node nach Freeze-Gate |
| 8 Security | `n_qa` + `n_route_qa` | Traceability-Check als Condition |
| 8a Delta-QA | `n_gate2l` | light gate |
| 9 Deploy | `n_deploy` | |
| Cutover-Gate | — | Migration-Profil |

## 7. Migrationsplan

| Phase | Aufgabe | Aufwand |
|---|---|---|
| 1 | ConditionEvaluator: `state.<key>` (dotted), `!=`, `node_<id>.output.<field>`; Tests | **done** (2026-11-14) |
| 2 | GraphCompiler-Erweiterungen: Gate-Bypass-Invariante, Budget-Bindung | **done** (2026-11-14) — SDW-07-Bypass-Erkennung (Edge-Conditions + Decision-Branches), SDW-03-Zyklus-Anchoring (gezählte Nodes legalisieren Revisit-Zyklen); kanonischer Graph validiert in `SdlcGraphDefinitionTest` |
| 3 | Check-Nodes: `test_runner` und `hash_checker` als Built-in-Check-Tools (`mcpServer: "builtin"`; `mvn test`-Parsing, JaCoCo-CSV, Traceability-Gaps, SHA-256-Contract-Vergleich) | **done** (2026-11-14) — `CheckTools` + `CheckTool`-Interface; 13 Tests |
| 4 | Rollen-Agent-Configs mounten (`wf:aisdlc-*:1.0.0`), responseSchemas je Rolle | 1–2 Tage |
| 5 | Definition oben persistieren, Runs in workflow-runner + workflow-ui verifizieren | 1–2 Tage |
| 6 | Migration-Profil (0a/2b/Cutover) als zweiter Graph | 2–3 Tage |
| **Gesamt** | | **~8–11 Tage** (Phasen 1–3 done) |

## 8. Offene Punkte

1. **ConditionEvaluator:** fixed (2026-11-14, `ConditionEvaluatorTest`, 12
   Tests): `state.<key>` mit dotted paths (`state.change.contract_impact`),
   `!=`, und `node_<id>.output.<field>` mit beliebigen Node-IDs. Unknown keys
   vergleichen als `null` (`==` → false, `!=` → true, konservatives Routing).
2. **Dedup-Regel** ("bestehende Session fortsetzen") vs. Session-per-Node:
   Entscheidung offen — Vorschlag: Session-Reuse als Run-Resume-Semantik
   innerhalb desselben Node-Attempts, nicht rollenübergreifend.
3. **Parallel streams** (`fork`/`join`): nur für read-only Analyse; echte
   Parallelität über Subworkflow/Temporal-Aktivitäten — Profil definiert
   erst nach Bedarf.
4. **Migration-Profil:** Graph 0a/2b/Cutover fehlt (siehe §6, §7 Phase 6).
5. **Approval-Speicherung:** HITL-Approvals müssen Run-ID + Node-ID +
   `contentHash` referenzieren (SDW-08) — UI-Schiene (workflow-ui) offen.
6. **Runtime-Navigator-Fallback:** `WorkflowStepProviderImpl.getNextNodeId`
   fällt bei nicht matchenden Kanten-Conditions auf `outgoing.get(0)` zurück
   (erste Kante in Def-Reihenfolge) — nicht konservativ. Empfehlung: ohne
   Match → `defaultTarget`-Semantik oder deterministischer Fehler; im
   kanonischen Graph ist die erste Kante bewusst die Full-Path-Kante
   (`d1`), wodurch der Fallback konservativ ausfällt.
7. **Check-Tool-Erweiterungen:** `test_runner` liest Coverage aus JaCoCo-CSV
   (falls vorhanden) und Traceability-Gaps aus
   `docs/requirements/traceability_matrix.json` (`blocking_gaps`); beides
   konfigurierbar via `mcpArgs`. QOC selbst hat kein JaCoCo — der Check
   bleibt dann null und blockiert nicht (coverage_min wird ignoriert).

## 9. Referenzen

| Thema | Wo |
|---|---|
| Operative Spec | [`../spec/agents/05-aisdlc-workflow.md`](../spec/agents/05-aisdlc-workflow.md) |
| Quellprozess | `sdlc/.agent/PHASES.md`, `sdlc/.agent/workflows/`, `sdlc/.opencode/agents/aisdlc-orchestrator.md` |
| Workflow-Runner-Architektur | [`Workflow.md`](./Workflow.md) |
| ReactFlow + Temporal | [`ReactFlow-temporal.md`](./ReactFlow-temporal.md) |
| Node-Semantik | `workflow-runner/runner/DefaultNodeDispatcher.java` |
| Conditions | `workflow-runner/runner/ConditionEvaluator.java` |
