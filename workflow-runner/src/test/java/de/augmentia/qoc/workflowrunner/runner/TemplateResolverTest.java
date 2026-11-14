package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the template dialect.
 *
 * <p>Guards the four defects that made placeholders fail silently:</p>
 * <ol>
 *   <li>{@code ${state.key}} never matched, because the .output segment was mandatory.</li>
 *   <li>Node IDs containing hyphens (editor-generated, e.g. {@code erp-prices})
 *       did not match the character class.</li>
 *   <li>A property path on {@code ${lastResult.output.x}} was ignored.</li>
 *   <li>Unknown references turned into the literal string {@code null}.</li>
 * </ol>
 */
class TemplateResolverTest {

    private static WorkflowContext context() {
        WorkflowContext ctx = new WorkflowContext();
        ctx.setState("lastStatus", "SUCCESS");

        Map<String, Object> nodeOutput = new HashMap<>();
        nodeOutput.put("id", "X-1");
        nodeOutput.put("name", "Bob");
        nodeOutput.put("classification", "VIP_ORDER");
        nodeOutput.put("price", 4711L);
        nodeOutput.put("nested", Map.of("deep", "value"));
        ctx.setNodeOutput("node_1", nodeOutput);

        // Editor-generated id with a hyphen.
        ctx.setNodeOutput("erp-prices", Map.of("total", 2500L));

        ctx.setState("lastResult", "plain text result");
        return ctx;
    }

    @Test
    void resolvesStateVariable() {
        assertEquals("SUCCESS", TemplateResolver.resolve("${state.lastStatus}", context()));
    }

    @Test
    void resolvesStateVariableEmbeddedInText() {
        assertEquals("Bearer abc123",
                TemplateResolver.resolve("Bearer ${state.token}", contextWithToken("abc123")));
    }

    @Test
    void resolvesNodeOutputAndField() {
        WorkflowContext ctx = context();
        // Whole-output rendering depends on Map iteration order, so check content
        // rather than an exact string.
        String whole = TemplateResolver.resolve("${node_1.output}", ctx);
        assertTrue(whole.contains("id=X-1"), whole);
        assertTrue(whole.contains("name=Bob"), whole);
        assertTrue(whole.contains("classification=VIP_ORDER"), whole);

        assertEquals("Bob", TemplateResolver.resolve("${node_1.output.name}", ctx));
        assertEquals("value", TemplateResolver.resolve("${node_1.output.nested.deep}", ctx));
    }

    @Test
    void resolvesNodeIdWithHyphen() {
        // Regression: the character class only allowed [a-zA-Z0-9_].
        assertEquals("2500", TemplateResolver.resolve("${erp-prices.output.total}", context()));
    }

    @Test
    void resolvesLastResult() {
        assertEquals("plain text result", TemplateResolver.resolve("${lastResult}", context()));
    }

    @Test
    void resolvesPropertyPathOnLastResult() {
        // Regression: the lastResult branch dropped the property path entirely.
        WorkflowContext ctx = context();
        ctx.setLastStructuredResult(Map.of("status", "OK", "summary", "fine"));
        assertEquals("OK", TemplateResolver.resolve("${lastResult.output.status}", ctx));
    }

    @Test
    void unknownNodeBecomesNullLiteral() {
        // Documented historical behaviour, pinned so a change is deliberate.
        assertEquals("a null b", TemplateResolver.resolve("a ${node_9.output} b", context()));
    }

    @Test
    void mustacheSyntaxIsNotResolved() {
        // ${...} is the only dialect; {{...}} passes through untouched.
        assertEquals("{{lastResult}}", TemplateResolver.resolve("{{lastResult}}", context()));
        assertEquals("Evaluate: {{lastResult}}",
                TemplateResolver.resolve("Evaluate: {{lastResult}}", context()));
    }

    @Test
    void dollarSignsInResolvedValuesAreNotTreatedAsGroups() {
        WorkflowContext ctx = context();
        ctx.setState("lastResult", "cost $5 and $1");
        assertEquals("cost $5 and $1", TemplateResolver.resolve("${lastResult}", ctx));
    }

    @Test
    void unknownPlaceholderIsLeftIntact() {
        assertEquals("${not a placeholder}", TemplateResolver.resolve("${not a placeholder}", context()));
    }

    @Test
    void resolveToObjectKeepsType() {
        Object resolved = TemplateResolver.resolveToObject("${node_1.output.price}", context());
        assertEquals(4711L, resolved, "integer value must not be stringified");
    }

    @Test
    void resolveToObjectReturnsWholeMap() {
        Object resolved = TemplateResolver.resolveToObject("${node_1.output}", context());
        assertTrue(resolved instanceof Map, "whole output must stay a Map");
    }

    @Test
    void resolveToObjectReturnsNullForUnknownNode() {
        assertNull(TemplateResolver.resolveToObject("${node_9.output}", context()));
    }

    @Test
    void resolveToObjectPassesThroughPartialTemplates() {
        assertEquals("prefix ${lastResult}",
                TemplateResolver.resolveToObject("prefix ${lastResult}", context()));
    }

    @Test
    void stateKeyWithDotsIsResolvedAsWholeKey() {
        // Nested keys are looked up verbatim, not navigated.
        WorkflowContext ctx = context();
        ctx.setState("a.b", "dotted");
        assertEquals("dotted", TemplateResolver.resolve("${state.a.b}", ctx));
    }

    @Test
    void nullTemplateIsHandled() {
        assertNull(TemplateResolver.resolve(null, context()));
        assertNull(TemplateResolver.resolveToObject(null, context()));
    }

    @Test
    void textWithoutPlaceholdersIsUnchanged() {
        assertEquals("no placeholders here", TemplateResolver.resolve("no placeholders here", context()));
    }

    private static WorkflowContext contextWithToken(String token) {
        WorkflowContext ctx = context();
        ctx.setState("token", token);
        return ctx;
    }
}
