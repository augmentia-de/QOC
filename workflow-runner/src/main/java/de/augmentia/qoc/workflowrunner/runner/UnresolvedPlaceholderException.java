package de.augmentia.qoc.workflowrunner.runner;

/**
 * Raised when a template placeholder cannot be resolved and strict resolution
 * was requested.
 *
 * <p>Exists to turn a silent data-corruption bug into a visible failure: the
 * plain {@link TemplateResolver#resolve} keeps substituting the literal
 * {@code null} for unknown references, which let unresolved placeholders reach
 * LLM prompts disguised as data.</p>
 */
public class UnresolvedPlaceholderException extends RuntimeException {

    public UnresolvedPlaceholderException(String message) {
        super(message);
    }
}
