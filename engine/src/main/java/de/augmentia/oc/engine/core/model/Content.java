package de.augmentia.oc.engine.core.model;

import java.time.Instant;

public sealed interface Content {
    record TextContent(String text, Instant time) implements Content {
        public TextContent {
            if (text == null) throw new IllegalArgumentException("text must not be null");
            if (time == null) time = Instant.EPOCH;
        }
    }

    record ReasoningContent(String text, Instant time) implements Content {
        public ReasoningContent {
            if (text == null) throw new IllegalArgumentException("text must not be null");
            if (time == null) time = Instant.EPOCH;
        }
    }

    record ToolCardContent(ToolCard card) implements Content {
        public ToolCardContent {
            if (card == null) throw new IllegalArgumentException("card must not be null");
        }
    }
}