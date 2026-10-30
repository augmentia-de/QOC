package de.augmentia.qoc.decisiongate;

import java.util.List;

/**
 * Runnable demonstration of the gate without network access.
 *
 * <p>Run it with:</p>
 * <pre>{@code
 * mvn -pl decision-gate exec:java
 * }</pre>
 *
 * <p>Each prompt produces a single {@link TaskGateDecision}. In QOC the caller
 * would map that decision to a {@code ToolSnapshot}, a skill filter and the
 * configured MCP servers before calling {@code ContextBuilder.prepare()}.</p>
 */
public final class Demo {

    private Demo() {}

    public static void main(String[] args) {
        DecisionGate gate = new LangChain4jDecisionGate(ScriptedDecisionModel.heuristic());

        List<String> prompts = List.of(
                "Lies die README und sage mir, wie das Projekt startet",
                "Analysiere die Session-Engine und dokumentiere die wichtigsten Invarianten",
                "Implementiere ein Decision-Gate-Submodul mit Tests und aktualisiere die Doku",
                "Pruefe per MCP-GitHub die offenen Issues und fasse sie zusammen");

        System.out.println("Decision gate demo (scripted Jev-style model)");
        System.out.println("=============================================");
        for (String prompt : prompts) {
            TaskGateDecision decision = gate.decide(prompt);
            System.out.println();
            System.out.println("prompt: " + prompt);
            System.out.println("  " + decision.summary());
        }
    }
}
