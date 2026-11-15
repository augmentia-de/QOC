package de.augmentia.qoc.workflowrunner.runner;

/**
 * Thrown when a node's output does not conform to its declared
 * {@code outputContract} schema. Carries the JSON path of the violation
 * and a human-readable message so callers can distinguish missing fields,
 * type mismatches and additional-property violations.
 */
public class ContractViolationException extends RuntimeException {

    private final String path;
    private final String kind;

    public ContractViolationException(String path, String kind, String message) {
        super(message);
        this.path = path == null ? "$" : path;
        this.kind = kind == null ? "violation" : kind;
    }

    public String path() { return path; }
    public String kind() { return kind; }
}