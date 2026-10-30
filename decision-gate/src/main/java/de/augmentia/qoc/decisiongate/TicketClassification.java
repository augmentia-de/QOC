package de.augmentia.qoc.decisiongate;

/**
 * A typed ticket classification with the confidence reported by the model.
 *
 * @param category the chosen routing category
 * @param confidence model confidence in the chosen category, from 0 to 1
 * @param source short, loggable model identifier
 */
public record TicketClassification(TicketCategory category, double confidence, String source) {

    public TicketClassification {
        if (category == null) throw new IllegalArgumentException("category must not be null");
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
    }
}
