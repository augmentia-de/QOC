# Fortschritt: Node-Datenverträge & Context-Ökonomie

**Stand:** 2026-11-15
**Plan:** `docs/plans/node-data-contracts-and-context-economy.md`
**Test-Spec:** Abschnitt 9 des Plans

---

## Abgeschlossen

### Phase 0 — Node-Datenvertrag (KERN) ✅

**Commit:** `3eafb65`
**Dateien:**
- `workflow-runner/.../model/WorkflowDefinition.java` — `NodeData` erweitert um `outputContract`, `contextRefs` (+ Backwards-Compat-Konstruktor)
- `workflow-runner/.../runner/NodeContractValidator.java` (neu) — minimaler JSON-Schema-Validator
- `workflow-runner/.../runner/ContractViolationException.java` (neu) — typisierte Exception mit `path()`/`kind()`
- `workflow-runner/.../runner/DefaultNodeDispatcher.java` — `dispatch()` wrapt `dispatchImpl()` + `validateOutputContract()`; `context_agent_executor` liest `contextRefs` aus `NodeData`
- `workflow-runner/.../runner/ContextAssembler.java` — Node-Outputs in `<node-output id="...">`-Delimiters
- `workflow-runner/.../store/WorkflowDefaults.java` — aktualisiert auf neue Arity

**Tests:**
- Build grün: workflow-runner (95 Tests), engine (1110 Tests)
- Keine neuen Test-Klassen (Phase 0 Tests in `NodeDataContractTest` noch nicht implementiert — geplant)

---

### Phase 4 — Prompt-Injection-Defense ✅

**Commit:** `3eafb65`
**Dateien:**
- `engine/.../runtime/context/ConversationFolding.java` — Tool-Results in `<tool-result name="..." id="...">`-Delimiters gewickelt (Completed + Failed)

**Tests:**
- `ConversationFoldingCoverageTest` — 2 Assertions aktualisiert (begründet: Delimiter-Wrapping ist Design-Änderung)

---

### Phase 1 — Semantische Tool-Outcomes + hasMore ✅

**Commit:** `b5e52ea`
**Dateien:**
- `engine/.../core/contract/tool/ToolOutcome.java` — neue Varianten: `Empty`, `InvalidArgument`, `Unavailable`
- `engine/.../runtime/tool/runtime/ToolOutcomeJson.java` — Encode/Decode für neue Typen
- `engine/.../runtime/tool/fs/GrepTool.java` — `hasMore` in Metadata
- `engine/.../runtime/tool/fs/ReadTool.java` — `hasMore` in Metadata (beide Pfade)
- `engine/.../runtime/tool/fs/GlobTool.java` — `hasMore` in Metadata
- `engine/.../core/ToolOutcomeSemanticTest.java` (neu, 9 Tests)

**Tests:**
- `ToolOutcomeSemanticTest` — 9 Tests grün
- `FsToolsTest` — 18 Tests grün (keine Regression)

---

### Phase 5 — Visual Context-Refs + Output-Contract Selektoren (UI) ✅

**Commit:** `f63167b`
**Dateien:**
- `workflow-ui/src/types/workflow.ts` — `NodeData` erweitert um `outputContract`, `contextRefs`
- `workflow-ui/src/components/PropertyPanel.tsx` — Context-Refs Checkbox-Liste + Output-Contract SchemaSource für `agent`- und `context_agent_executor`-Nodes

**Tests:** Build grün (tsc + vite)

---

### Phase 7 — Contract-Validierung (Generator) ✅

**Commits:** `9940936`, `f238164`
**Dateien:**
- `workflow-runner/.../generate/WorkflowContractValidator.java` — deterministische Prüfung + LLM-basierter semantischer Pass (`validateWithLlm`)
- `workflow-runner/.../generate/WorkflowGenerator.java` — beide Pässe integriert (best-effort, non-blocking)

**Hinweis:** LLM-Pass ist best-effort (swallowed exceptions). Test-Klasse `WorkflowGeneratorContractValidationTest` noch nicht implementiert.

---

## Status

Alle 8 Phasen sind (mindestens teilweise) implementiert. Empfehlungen aus den offenen Fragen umgesetzt:
- #2: `strict`-Flag in `NodeData` (Default: false = warn + continue, true = abort)
- #3: `contextRefs` werden automatisch aus Graph-Edges abgeleitet (`TopologicalSort.incomingSources`)
- #5: Output-Limit konfigurierbar via System-Property `qoc.output.max-bytes` (Default: 50 KB)

Verbleibende Follow-ups: Test-Klassen (NodeDataContractTest, etc.) und vollständige Integration (Config-Flags statt System-Properties, per-Agent outputLimit in AgentConfig).

### Phase 0b — Feingranulare Feld-Auswahl + strukturierter Initial-Input ✅ (Backend)

**Dateien:**
- `workflow-runner/.../model/WorkflowDefinition.java` — `inputSchema` Feld (+ Backwards-Compat-Konstruktoren)
- `workflow-runner/.../runner/WorkflowContext.java` — `initialInput` Feld + Getter/Setter + Persistierung in `restore()`
- `workflow-runner/.../runner/ContextRefResolver.java` (neu) — Auflösung + Validierung von Pfad-Refs
- `workflow-runner/.../runner/ContextAssembler.java` — `appendCustom` nutzt `ContextRefResolver`; Feld-Rendering via `<context-value>`
- `workflow-runner/.../validation/GraphCompiler.java` — `validateContextRefs` + reservierte Node-Namen
- `workflow-runner/.../runner/WorkflowExecutor.java` — `tryParseJson` + `inputSchema`-Validierung via `NodeContractValidator`

**Tests:**
- `ContextRefResolverTest` — 11 Tests grün
- `ContextAssemblerTest` — 5 Tests grün (inkl. Feld-Auswahl)
- `GraphCompilerTest` — +2 Tests (reservierte Node-Namen, unbekannte Nodes in contextRefs)

**Offen (UI):**
- Phase 5b Feld-Selektor (JSON-Tree mit Checkboxen) noch nicht implementiert.

**Verbleibende Follow-ups:** NodeDataContractTest (P0-1..P0-13), vollständige Integration (Config-Flags statt System-Properties, per-Agent outputLimit).

**Phase 3 (Strict Structured Outputs):** Detaillierte Anleitung in `docs/plans/strict-structured-outputs.md` (Status: Offen). Drei Optionen analysiert: Option 1 (aktuelle Umsetzung, non-strict + eigene Validierung), Option 2 mit Weg A (response_format) — nicht empfohlen, Weg B (strict auf ToolSpecification) — empfohlen, aber offen (erfordert Schema-Anpassungen + echtes API-Testing).

**UI (2026-11-16, vierte Runde):**
- PropertyPanel zeigt jetzt pro Vorgänger-Node (wenn `outputContract.properties` vorhanden) Checkboxen für Top-Level-Felder (`nodeId.field`).
- Texteingabe für beliebige Pfade (nodeId.field, initial.field, state.key, lastResult.field) bleibt.

**UI (2026-11-16, sechste Runde):**
- Vollständiger rekursiver JSON-Tree-Feld-Selektor (`FieldTree`-Komponente): beliebige Verschachtelungstiefe, Checkboxen pro Feld, Pfade werden als `nodeId.a.b.c` zu `contextRefs` hinzugefügt.

**Zusätzlich implementiert (2026-11-16, dritte Runde):**
- `WorkflowContractValidatorTest` — 7 Tests (Namenskonvention, fehlender Output-Contract, fehlender System-Prompt, LLM-Parse, null-Caller, null-Def).

**Zusätzlich implementiert (2026-11-16, fünfte Runde):**
- `NodeDataContractTest` — 11 Tests (Contract-Validierung valid/invalid/missing/additional/enum, no-contract passthrough, contextRefs field filtering, unknown ref, template type check/mismatch).
- Phase 5b UI: rekursiver JSON-Tree-Feld-Selektor (Top-Level + verschachtelte Felder) in PropertyPanel.

## Offene Fragen (aus dem Plan)

1. Soll `outputContract` verpflichtend oder optional sein? → Empfehlung: optional (Default = kein Contract → Legacy).
2. Soll Contract-Verstoß den Workflow abbrechen oder nur warnen? → Empfehlung: abbrechen bei `strict: true`-Flag, sonst warnen + weiter.
3. Sollen `contextRefs` automatisch aus Graph-Edges abgeleitet werden? → Empfehlung: ja, als Default.
4. Enum-Conversion in Tools: case-insensitive oder strict? (Phase 1, P1-15)
5. Offloading-Schwellenwert: global oder per-Agent? (Phase 2)
6. ChatContextReducer: default aktiviert oder opt-in? (Phase 6)