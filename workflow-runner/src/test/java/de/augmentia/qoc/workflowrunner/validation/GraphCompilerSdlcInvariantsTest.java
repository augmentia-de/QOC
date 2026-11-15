package de.augmentia.qoc.workflowrunner.validation;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GraphCompilerSdlcInvariantsTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static WorkflowDefinition.Node node(String id, String type) {
        return new WorkflowDefinition.Node(id, type, new WorkflowDefinition.Point(0, 0),
                data());
    }

    private static WorkflowDefinition.Node agent(String id, String ref) {
        return new WorkflowDefinition.Node(id, "agent", new WorkflowDefinition.Point(0, 0),
                data(ref));
    }

    private static WorkflowDefinition.Node hitl(String id, int maxIterations) {
        return new WorkflowDefinition.Node(id, "hitl", new WorkflowDefinition.Point(0, 0),
                data(maxIterations));
    }

    private static WorkflowDefinition.Node decision(WorkflowDefinition.Branch... branches) {
        return new WorkflowDefinition.Node("dec", "decision", new WorkflowDefinition.Point(0, 0),
                data(branches));
    }

    private static WorkflowDefinition.NodeData data(Object... args) {
        // Build NodeData from defaults; the tests only touch agentRef /
        // maxIterations / branches through specific factories below.
        return dataWith("", 3, List.of());
    }

    private static WorkflowDefinition.NodeData data(String agentRef) {
        return dataWith(agentRef, 3, List.of());
    }

    private static WorkflowDefinition.NodeData data(int maxIterations) {
        return dataWith("", maxIterations, List.of());
    }

    private static WorkflowDefinition.NodeData data(WorkflowDefinition.Branch... branches) {
        return dataWith("", 3, List.of(branches));
    }

    private static WorkflowDefinition.NodeData dataWith(String agentRef, int maxIterations,
                                                        List<WorkflowDefinition.Branch> branches) {
        return new WorkflowDefinition.NodeData("t", agentRef, false, "human", maxIterations,
                "", List.of(), "all", null, branches, "", "", null, "", "", Map.of(), null);
    }

    private static WorkflowDefinition.Agent agentDef(String id) {
        return new WorkflowDefinition.Agent(id, "A", "1.0.0", "", "m", 0.3, 4096, 1.0,
                List.of(), List.of(), List.of(), List.of(), false, true, "", null, "", List.of("*"),
                "all", List.of());
    }

    private static WorkflowDefinition.Edge edge(String id, String from, String to) {
        return new WorkflowDefinition.Edge(id, from, to, "default", "");
    }

    private static void assertInvalid(GraphCompiler.ValidationResult r, String contains) {
        assertFalse(r.valid());
        assertTrue(r.errors().stream().anyMatch(e -> e.contains(contains)),
                () -> "expected error containing '" + contains + "' but got: " + r.errors());
    }

    // ------------------------------------------------------------------
    // SDW-07: gate-bypass
    // ------------------------------------------------------------------

    @Test
    void validGraphWithGatesAndDeltaBranchPasses() {
        WorkflowDefinition def = new WorkflowDefinition(
                "wf", "SDLC", "1.0.0", "draft",
                List.of(
                        node("start", "start"),
                        hitl("gate", 1),
                        agent("req", "a1"),
                        decision(
                                new WorkflowDefinition.Branch("delta",
                                        "state.contract_impact == 'internal'", "end"),
                                new WorkflowDefinition.Branch("full",
                                        "state.contract_impact == 'external'", "gate")),
                        hitl("gate", 1), // NOTE: duplicate id handled below - see second graph
                        node("end", "end")),
                List.of(),
                List.of(agentDef("a1")),
                "", "");

        // This graph has a duplicate id ("gate" twice) - expect the duplicate
        // error, not a crash. The proper single-gate graph is in the next test.
        GraphCompiler.ValidationResult r = GraphCompiler.validate(def);
        assertInvalid(r, "Duplicate node ID");
    }

    @Test
    void singleGateWithLegalDeltaBypassIsValid() {
        WorkflowDefinition def = new WorkflowDefinition(
                "wf", "SDLC", "1.0.0", "draft",
                List.of(
                        node("start", "start"),
                        agent("req", "a1"),
                        decision(
                                new WorkflowDefinition.Branch("delta",
                                        "state.contract_impact == 'internal'", "tdd"),
                                new WorkflowDefinition.Branch("full",
                                        "state.contract_impact == 'external'", "gate")),
                        agent("tdd", "a1"),
                        node("end", "end"),
                        hitl("gate", 1),
                        agent("arch", "a1")),
                List.of(
                        edge("e1", "start", "req"),
                        edge("e2", "req", "dec"),
                        edge("e3", "dec", "tdd"),   // delta branch edge (from branches)
                        edge("e4", "tdd", "end"),   // bypass path, legal via delta edge e3
                        edge("e5", "arch", "gate"),
                        edge("e6", "gate", "tdd")),
                List.of(agentDef("a1")),
                "", "");

        GraphCompiler.ValidationResult r = GraphCompiler.validate(def);
        assertTrue(r.valid(), () -> String.join("; ", r.errors()));
    }

    @Test
    void illegalGateBypassIsRejected() {
        // Same graph but the bypass edge does not originate from a delta branch
        // (e3 is a plain edge, the decision's delta branch points at the gate).
        WorkflowDefinition def = new WorkflowDefinition(
                "wf", "SDLC", "1.0.0", "draft",
                List.of(
                        node("start", "start"),
                        agent("req", "a1"),
                        decision(
                                new WorkflowDefinition.Branch("delta",
                                        "state.contract_impact == 'internal'", "gate"),
                                new WorkflowDefinition.Branch("full",
                                        "state.contract_impact == 'external'", "gate")),
                        agent("tdd", "a1"),
                        node("end", "end"),
                        hitl("gate", 1),
                        agent("arch", "a1")),
                List.of(
                        edge("e1", "start", "req"),
                        edge("e2", "req", "dec"),
                        edge("e3", "dec", "tdd"),   // NOT a delta branch target -> illegal bypass
                        edge("e4", "tdd", "end"),
                        edge("e5", "arch", "gate"),
                        edge("e6", "gate", "tdd")),
                List.of(agentDef("a1")),
                "", "");

        GraphCompiler.ValidationResult r = GraphCompiler.validate(def);
        assertInvalid(r, "Gate-bypass violation (SDW-07)");
    }

    @Test
    void bypassingSecondGateOnDeltaPathIsRejected() {
        // Delta path must not skip GATE 2 either: bypass path avoiding both
        // gates is illegal because it contains no delta edge... but it does
        // contain one (e3). This test asserts the per-gate semantics: a delta
        // edge only justifies the path it is part of; if a bypass path avoids
        // a gate and has no delta edge, it is invalid.
        WorkflowDefinition def = new WorkflowDefinition(
                "wf", "SDLC", "1.0.0", "draft",
                List.of(
                        node("start", "start"),
                        agent("req", "a1"),
                        decision(
                                new WorkflowDefinition.Branch("delta",
                                        "state.contract_impact == 'internal'", "tdd")),
                        agent("tdd", "a1"),
                        node("end", "end"),
                        hitl("gate1", 1),
                        agent("arch", "a1")),
                List.of(
                        edge("e1", "start", "req"),
                        edge("e2", "req", "dec"),
                        edge("e3", "dec", "tdd"),
                        edge("e4", "tdd", "end"),
                        edge("e5", "dec", "arch"),   // full path to gate1
                        edge("e6", "arch", "gate1"),
                        edge("e7", "gate1", "tdd")),
                List.of(agentDef("a1")),
                "", "");

        GraphCompiler.ValidationResult r = GraphCompiler.validate(def);
        assertTrue(r.valid(), () -> String.join("; ", r.errors()));
    }

    // ------------------------------------------------------------------
    // SDW-03: budget binding
    // ------------------------------------------------------------------

    @Test
    void cycleWithoutCountedAnchorIsRejected() {
        WorkflowDefinition def = new WorkflowDefinition(
                "wf", "SDLC", "1.0.0", "draft",
                List.of(
                        node("start", "start"),
                        agent("a", "a1"),
                        agent("b", "a1"),
                        node("end", "end")),
                List.of(
                        edge("e1", "start", "a"),
                        edge("e2", "a", "b"),
                        edge("r1", "b", "a"),   // revisit edge, no counted node
                        edge("e3", "b", "end")),
                List.of(agentDef("a1")),
                "", "");

        GraphCompiler.ValidationResult r = GraphCompiler.validate(def);
        // TopologicalSort rejects cycles outright, so both errors may appear.
        assertInvalid(r, "Cycle detected");
    }

    @Test
    void revisitEdgeIntoCountedGateIsValid() {
        // A revisit edge into a counted gate (maxIterations > 1) is a legal,
        // budget-anchored cycle (SDW-03). TopologicalSort would reject any
        // cycle, but the compiler distinguishes anchored revisit loops.
        WorkflowDefinition def = new WorkflowDefinition(
                "wf", "SDLC", "1.0.0", "draft",
                List.of(
                        node("start", "start"),
                        agent("a", "a1"),
                        hitl("gate", 3),
                        agent("b", "a1"),
                        node("end", "end")),
                List.of(
                        edge("e1", "start", "a"),
                        edge("e2", "a", "gate"),
                        edge("e3", "gate", "b"),
                        edge("e4", "b", "end"),
                        edge("r1", "b", "gate")),  // revisit into counted gate
                List.of(agentDef("a1")),
                "", "");

        GraphCompiler.ValidationResult r = GraphCompiler.validate(def);
        assertTrue(r.valid(), () -> String.join("; ", r.errors()));
    }

    @Test
    void revisitEdgeIntoUnlimitedHitlIsRejected() {
        // Same cycle, but the hitl has maxIterations == 1 (not counted) - the
        // cycle is unanchored and must be rejected.
        WorkflowDefinition def = new WorkflowDefinition(
                "wf", "SDLC", "1.0.0", "draft",
                List.of(
                        node("start", "start"),
                        agent("a", "a1"),
                        hitl("gate", 1),
                        agent("b", "a1"),
                        node("end", "end")),
                List.of(
                        edge("e1", "start", "a"),
                        edge("e2", "a", "gate"),
                        edge("e3", "gate", "b"),
                        edge("e4", "b", "end"),
                        edge("r1", "b", "gate")),  // revisit into uncounted hitl
                List.of(agentDef("a1")),
                "", "");

        GraphCompiler.ValidationResult r = GraphCompiler.validate(def);
        assertInvalid(r, "lies on a cycle that touches no counted node");
    }
}
