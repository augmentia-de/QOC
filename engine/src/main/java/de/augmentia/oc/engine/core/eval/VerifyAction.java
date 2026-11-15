package de.augmentia.oc.engine.core.eval;

/** Action taken when a Verify-on-Stop rule fails. */
public enum VerifyAction {
    /** Continue the loop and tell the LLM what is missing. */
    CONTINUE,
    /** Mark the run as incomplete but finish normally. */
    WARN,
    /** Mark the run as failed. */
    FAIL
}
