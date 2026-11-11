package de.augmentia.oc.engine.core.port.store;

import java.time.Instant;

/**
 * Durable persistence for question requests (web UI and email).
 */
public interface QuestionStore {

    void persistPending(String id, String sessionId, String questionsJson,
                        Instant createdAt, Instant deadline, String tokenHash);

    String[] pollAnswer(String id);

    void markAnswered(String id, String selected, String text);

    void markExpired(String id);

    String findIdByToken(String tokenHash);

    String sessionIdForQuestion(String id);
}
