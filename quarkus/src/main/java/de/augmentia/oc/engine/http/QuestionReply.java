package de.augmentia.oc.engine.http;

import java.util.List;

/** Reply payload for the question endpoints. */
public record QuestionReply(List<String> selected, String text) {}
