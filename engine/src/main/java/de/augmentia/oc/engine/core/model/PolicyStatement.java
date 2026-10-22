package de.augmentia.oc.engine.core.model;


/**
 * A binary organization rule. It has final authority and is applied after all
 * other catalog transformations.
 *
 * <p>Actions: {@code provider.use}, {@code tool.use}, {@code integration.use}.
 * A {@code tool.use} resource has the form {@code <action>:<resource>}.
 */
public record PolicyStatement(Effect effect, String action, String resource) {

    public PolicyStatement {
        if (effect == null) throw new IllegalArgumentException("effect must not be null");
        if (effect == Effect.ASK) {
            throw new IllegalArgumentException("policy rules are binary (allow or deny)");
        }
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action must not be blank");
        if (resource == null || resource.isBlank()) throw new IllegalArgumentException("resource must not be blank");
    }

    public static PolicyStatement allow(String action, String resource) {
        return new PolicyStatement(Effect.ALLOW, action, resource);
    }

    public static PolicyStatement deny(String action, String resource) {
        return new PolicyStatement(Effect.DENY, action, resource);
    }
}
