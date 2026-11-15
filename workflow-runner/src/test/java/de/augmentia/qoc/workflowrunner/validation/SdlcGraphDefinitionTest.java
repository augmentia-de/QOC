package de.augmentia.qoc.workflowrunner.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SdlcGraphDefinitionTest {

    /**
     * The canonical SDLC workflow definition from
     * docs/engineering/Workflow-SDLC.md (§3). Any change there must be
     * mirrored here; this test is the executable contract between the
     * documented graph and the compiler.
     */
    private static final String SDLC_GRAPH_JSON = """
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
          "data": { "title": "Step 1 Requirements", "agentRef": "ag_req" } },

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
          "data": { "title": "Step 1a Change brief", "agentRef": "ag_req" } },

        { "id": "n_tdd_delta", "type": "loop",
          "data": { "title": "Delta TDD (5a) + Impl (6a) + Check",
                    "maxIterations": 5,
                    "exitCondition": "state.check_result == 'GREEN'",
                    "bodySteps": ["n_impl_body_delta", "n_check_delta"] } },

        { "id": "n_impl_body_delta", "type": "agent",
          "data": { "title": "Steps 5a/6a execution", "agentRef": "ag_exec" } },

        { "id": "n_check_delta", "type": "mcp_call",
          "data": { "title": "Check: mvn test + affected ACs",
                    "mcpTool": "test_runner", "jsonOutput": true } },

        { "id": "n_gate2l", "type": "hitl",
          "data": { "title": "GATE 2 light (delta QA 8a approval)",
                    "assignee": "human", "maxIterations": 2 } },

        { "id": "n_arch", "type": "agent",
          "data": { "title": "Step 2 Architecture", "agentRef": "ag_arch" } },

        { "id": "n_gate1", "type": "hitl",
          "data": { "title": "GATE 1 (direction review)", "assignee": "human",
                    "maxIterations": 2 } },

        { "id": "n_simplify", "type": "agent",
          "data": { "title": "Step 3 Simplify", "agentRef": "ag_arch" } },

        { "id": "n_stress", "type": "agent",
          "data": { "title": "Step 4 Grill-Me stress test", "agentRef": "ag_arch" } },

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
          "data": { "title": "Contract freeze (human approval)", "assignee": "human",
                    "maxIterations": 2 } },

        { "id": "n_impl", "type": "loop",
          "data": { "title": "Steps 5-7: TDD -> Impl -> Refactor",
                    "maxIterations": 5,
                    "exitCondition": "state.check_result == 'GREEN'",
                    "bodySteps": ["n_impl_body", "n_check"] } },

        { "id": "n_impl_body", "type": "agent",
          "data": { "title": "Steps 5-7 execution", "agentRef": "ag_exec" } },

        { "id": "n_check", "type": "mcp_call",
          "data": { "title": "Check: mvn test + coverage + traceability",
                    "mcpTool": "test_runner", "jsonOutput": true } },

        { "id": "n_qa", "type": "agent",
          "data": { "title": "Step 8 Security & QA", "agentRef": "ag_qa" } },

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
          "data": { "title": "GATE 2 (release approval)", "assignee": "human",
                    "maxIterations": 2 } },

        { "id": "n_deploy", "type": "agent",
          "data": { "title": "Step 9 Deploy", "agentRef": "ag_qa" } },

        { "id": "n_end", "type": "end", "data": { "title": "Run complete" } }
      ],
      "edges": [
        { "id": "e01", "source": "n_start",  "target": "n_spec" },
        { "id": "e02", "source": "n_spec",   "target": "n_req" },
        { "id": "e03", "source": "n_req",    "target": "n_route" },
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
        { "id": "r2",  "source": "n_route_qa",     "target": "n_impl" },
        { "id": "d1",  "source": "n_route", "target": "n_arch",
          "condition": "state.breaking == 'true' || state.contract_impact == 'external' || state.contract_impact == 'public' || state.scope == 'cross-module'" },
        { "id": "d2",  "source": "n_route", "target": "n_brief",
          "condition": "state.contract_impact == 'internal' || state.contract_impact == 'none'" }
      ],
      "agents": [
        { "id": "ag_req",  "name": "aisdlc-requirements", "version": "1.0.0" },
        { "id": "ag_arch", "name": "aisdlc-architecture", "version": "1.0.0" },
        { "id": "ag_exec", "name": "aisdlc-execution",    "version": "1.0.0" },
        { "id": "ag_qa",   "name": "aisdlc-qa",           "version": "1.0.0" }
      ]
    }
    """;

    private static WorkflowDefinition parse() {
        try {
            return new ObjectMapper().readValue(
                    SDLC_GRAPH_JSON.getBytes(StandardCharsets.UTF_8), WorkflowDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse SDLC graph JSON", e);
        }
    }

    @Test
    void canonicalSdlcGraphPassesValidation() {
        GraphCompiler.ValidationResult r = GraphCompiler.validate(parse());
        assertTrue(r.valid(), () -> String.join("\n", r.errors()));
    }

    @Test
    void canonicalSdlcGraphStructure() {
        WorkflowDefinition def = parse();
        assertEquals(24, def.nodes().size(), "node count");
        assertEquals(23, def.edges().size(), "edge count");
        assertEquals(4, def.agents().size(), "agent count");
        assertEquals(1, def.nodes().stream().filter(n -> "start".equals(n.type())).count());
        assertEquals(1, def.nodes().stream().filter(n -> "end".equals(n.type())).count());
        assertEquals(5, def.nodes().stream().filter(n -> "hitl".equals(n.type())).count(),
                "SPEC-GATE, GATE1, freeze, GATE2, GATE2-light");
        assertEquals(3, def.nodes().stream().filter(n -> "decision".equals(n.type())).count());
        assertEquals(2, def.nodes().stream().filter(n -> "loop".equals(n.type())).count());
    }

    @Test
    void mutationRemovingGate2KeepsValidationBecauseRouteIsMachineChecked() throws Exception {
        // Mutation test: rewire the QA route directly to deploy (removing the
        // human gate). The compiler cannot know that GATE 2 is semantically
        // required - this documents the current limit: structural validation
        // passes, semantic gate requirements live in the spec (SDW-02), not
        // the compiler. Deleting n_gate2 and rewiring e18->n_deploy.
        String json = SDLC_GRAPH_JSON
                .replace("\"target\": \"n_gate2\" }", "\"target\": \"n_deploy\" }")
                .replace("{ \"id\": \"e19\", \"source\": \"n_gate2\",  \"target\": \"n_deploy\" },", "")
                .replace("{ \"id\": \"n_gate2\", \"type\": \"hitl\",", "{ \"id\": \"n_gate2_dead\", \"type\": \"hitl\",");
        WorkflowDefinition mutated = new ObjectMapper().readValue(
                json.getBytes(StandardCharsets.UTF_8), WorkflowDefinition.class);
        GraphCompiler.ValidationResult r = GraphCompiler.validate(mutated);
        assertTrue(r.valid(), () -> String.join("\n", r.errors()));
    }
}
