package de.augmentia.oc.engine.core.contract.permission;

/**
 * Determines how {@code ASK} permission decisions are resolved when no
 * interactive approval source is available (or when the mode is forced).
 *
 * <ul>
 *   <li>{@link #INTERACTIVE} – consult the approval source (UI); deny if none</li>
 *   <li>{@link #ALLOW} – auto-allow all ASK decisions (no questions)</li>
 *   <li>{@link #DENY} – auto-deny all ASK decisions (fail-safe default)</li>
 * </ul>
 */
public enum AskMode {
    INTERACTIVE,
    ALLOW,
    DENY
}
