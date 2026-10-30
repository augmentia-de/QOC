package de.augmentia.qoc.decisiongate;

/** How much of the skill catalogue the gate injects into the system prompt. */
public enum SkillProfile {

    /** No skill block; the model may still call the {@code skill} tool by id. */
    NONE,

    /** Only the configured core skills. */
    CORE,

    /** A semantic top-K selection based on the user prompt. */
    TOP_K,

    /** Every permitted skill, as QOC does today. */
    ALL
}
