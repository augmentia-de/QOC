package de.augmentia.qoc.decisiongate;

/** Overall size of a task, as decided before the agent loop starts. */
public enum TaskComplexity {

    /** A direct answer or a single lookup. No code change is expected. */
    TRIVIAL,

    /** A small change or analysis, usually one or two files. */
    MODERATE,

    /** Many files, design decisions, tests, or several tool steps. */
    COMPLEX;

    /**
     * Parses the option name used in the decision question.
     *
     * @throws IllegalArgumentException for unknown names
     */
    public static TaskComplexity parse(String value) {
        if (value == null) throw new IllegalArgumentException("complexity must not be null");
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "trivial" -> TRIVIAL;
            case "moderate" -> MODERATE;
            case "complex" -> COMPLEX;
            default -> throw new IllegalArgumentException("unknown complexity: " + value);
        };
    }
}
