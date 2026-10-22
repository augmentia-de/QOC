package de.augmentia.oc.engine.core;

public record Usage(long inputTokens, long outputTokens, long reasoningTokens) {
    public Usage {
        if (inputTokens < 0 || outputTokens < 0 || reasoningTokens < 0) {
            throw new IllegalArgumentException("Usage tokens must not be negative");
        }
    }

    public long totalTokens() {
        return inputTokens + outputTokens + reasoningTokens;
    }

    public static Usage zero() {
        return new Usage(0, 0, 0);
    }

    public TokenCount toTokenCount() {
        return new TokenCount(inputTokens, outputTokens);
    }
}