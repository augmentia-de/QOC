package de.augmentia.qoc.decisiongate;

import java.util.Locale;

/** Routing category for an incoming support ticket. */
public enum TicketCategory {
    SUPPORT,
    BILLING,
    SALES,
    SPAM;

    /**
     * Parses the option name used in the decision question.
     *
     * @throws IllegalArgumentException for unknown categories
     */
    public static TicketCategory parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("category must not be blank");
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown ticket category: " + value, e);
        }
    }
}
