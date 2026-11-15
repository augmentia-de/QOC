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

### Phase 7 — Deterministische Contract-Validierung (Generator) ✅

**Commit:** `9940936`
**Dateien:**
- `workflow-runner/.../generate/WorkflowContractValidator.java` (neu) — Namenskonvention, Tool-Overlap, fehlende outputContract/systemPrompt
- `workflow-runner/.../generate/WorkflowGenerator.java` — Contract-Validierung als best-effort Pass

**Hinweis:** LLM-basierter zweiter Pass (semantische Prüfung) bleibt offen; aktuell nur deterministische Regeln.

---

## Status

Alle 8 Phasen sind (mindestens teilweise) implementiert. Verbleibende Follow-ups: Test-Klassen (NodeDataContractTest, ChatContextReducerTest, StrictStructuredOutputTest, etc.) und LLM-basierter Contract-Validator (Phase 7, semantischer Pass).

## Offene Fragen (aus dem Plan)

1. Soll `outputContract` verpflichtend oder optional sein? → Empfehlung: optional (Default = kein Contract → Legacy).
2. Soll Contract-Verstoß den Workflow abbrechen oder nur warnen? → Empfehlung: abbrechen bei `strict: true`-Flag, sonst warnen + weiter.
3. Sollen `contextRefs` automatisch aus Graph-Edges abgeleitet werden? → Empfehlung: ja, als Default.
4. Enum-Conversion in Tools: case-insensitive oder strict? (Phase 1, P1-15)
5. Offloading-Schwellenwert: global oder per-Agent? (Phase 2)
6. ChatContextReducer: default aktiviert oder opt-in? (Phase 6)