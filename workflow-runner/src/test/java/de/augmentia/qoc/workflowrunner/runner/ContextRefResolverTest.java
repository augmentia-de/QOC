package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextRefResolverTest {

    private WorkflowContext ctx() {
        WorkflowContext c = new WorkflowContext();
        c.setInitialPrompt("start");
        c.setInitialInput(Map.of("bestellung", "B-456", "kunde", Map.of("name", "Max")));
        c.setNodeOutput("produce", Map.of("konto", Map.of("saldo", 42.5), "status", "OK"));
        c.setState("bestellung", "B-123");
        c.setLastStructuredResult(Map.of("status", "DONE"));
        return c;
    }

    @Test
    void fieldPath_resolvesNested() {
        WorkflowContext c = ctx();
        assertEquals(42.5, ContextRefResolver.resolve(c, "produce.konto.saldo"));
    }

    @Test
    void statePath_resolvesState() {
        WorkflowContext c = ctx();
        assertEquals("B-123", ContextRefResolver.resolve(c, "state.bestellung"));
    }

    @Test
    void initialPath_resolvesInitialInput() {
        WorkflowContext c = ctx();
        assertEquals("B-456", ContextRefResolver.resolve(c, "initial.bestellung"));
        assertEquals("Max", ContextRefResolver.resolve(c, "initial.kunde.name"));
    }

    @Test
    void lastResultPath_resolvesNested() {
        WorkflowContext c = ctx();
        assertEquals("DONE", ContextRefResolver.resolve(c, "lastResult.status"));
    }

    @Test
    void wholeNode_returnsOutput() {
        WorkflowContext c = ctx();
        assertEquals(Map.of("konto", Map.of("saldo", 42.5), "status", "OK"),
                ContextRefResolver.resolve(c, "produce"));
    }

    @Test
    void wholeInitial_returnsInput() {
        WorkflowContext c = ctx();
        assertEquals(Map.of("bestellung", "B-456", "kunde", Map.of("name", "Max")),
                ContextRefResolver.resolve(c, "initial"));
    }

    @Test
    void unknownPath_returnsNull() {
        WorkflowContext c = ctx();
        assertNull(ContextRefResolver.resolve(c, "produce.nichtVorhanden"));
        assertNull(ContextRefResolver.resolve(c, "unknownNode"));
    }

    @Test
    void findProblems_unknownNode() {
        WorkflowContext c = ctx();
        List<String> problems = ContextRefResolver.findProblems("ghost.field", Set.of("produce"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown node")));
    }

    @Test
    void findProblems_reservedName() {
        List<String> problems = ContextRefResolver.findProblems("state.foo", null);
        assertTrue(problems.isEmpty()); // state. prefix is allowed
        List<String> problems2 = ContextRefResolver.findProblems("stateX", Set.of());
        // "stateX" is a node id, not reserved
        assertTrue(problems2.stream().noneMatch(p -> p.contains("reserved")));
    }

    @Test
    void isResolvable() {
        WorkflowContext c = ctx();
        assertTrue(ContextRefResolver.isResolvable(c, "produce.konto.saldo"));
        assertTrue(!ContextRefResolver.isResolvable(c, "produce.nope"));
    }

    @Test
    void initialInput_storedAndRestored() {
        WorkflowContext c = new WorkflowContext();
        c.setInitialInput(Map.of("bestellung", "B-999"));
        assertEquals("B-999", c.getInitialInput() instanceof Map ? ((Map<?,?>) c.getInitialInput()).get("bestellung") : null);

        // restore via state
        Map<String, Object> state = new java.util.HashMap<>(c.getWorkflowState());
        WorkflowContext c2 = new WorkflowContext();
        c2.restore(Map.of(), state, "n1");
        assertEquals("B-999", c2.getInitialInput() instanceof Map ? ((Map<?,?>) c2.getInitialInput()).get("bestellung") : null);
    }
}
