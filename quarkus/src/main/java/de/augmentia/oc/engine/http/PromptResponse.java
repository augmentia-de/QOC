package de.augmentia.oc.engine.http;

/** Prompt/turn result payload. */
public record PromptResponse(String outcome, String finish, long inputTokens,
                             long outputTokens, String text, Object structured) {}
