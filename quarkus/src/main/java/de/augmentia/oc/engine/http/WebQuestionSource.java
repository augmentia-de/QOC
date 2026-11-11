package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.human.QuestionSource;

import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Human-in-the-loop questions for the web UI. {@link #ask} blocks until the UI
 * posts an answer or the timeout expires; a timeout returns an empty answer.
 *
 * <p>Questions are stored globally (not just per-session) so that pending
 * questions from background sessions are visible in the UI panel.</p>
 */
@ApplicationScoped
public class WebQuestionSource implements QuestionSource {

    private static final long TIMEOUT_SECONDS = 300;

    public record Pending(String id, SessionId session, String sessionTitle,
                          List<Question> questions, Instant createdAt) {}

    private final Map<String, CompletableFuture<Answer>> futures = new ConcurrentHashMap<>();
    private final Map<String, Pending> pendingById = new ConcurrentHashMap<>();
    private final Map<String, String> sessionToQuestionId = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    @jakarta.inject.Inject
    EventBus eventBus;

    /**
     * Registers an externally created future so that the web UI / REST API can
     * resolve it. Returns the assigned question ID.
     */
    public String registerExternalFuture(CompletableFuture<Answer> future, SessionId session,
                                         List<Question> questions) {
        if (future == null || session == null || questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("future, session and questions must be set");
        }
        String id = "q_" + sequence.incrementAndGet();
        futures.put(id, future);
        pendingById.put(id, new Pending(id, session, session == null ? "" : session.value(),
                List.copyOf(questions), Instant.now()));
        sessionToQuestionId.put(session.value(), id);
        if (eventBus != null) {
            try {
                String json = "{\"type\":\"question_pending\",\"id\":\"" + id + "\",\"sessionId\":\"" + (session == null ? "" : session.value()) + "\"}";
                eventBus.publishLive("questions", json);
            } catch (Exception ignored) { }
        }
        return id;
    }

    @Override
    public Answer ask(SessionId session, List<Question> questions) {
        if (session == null || questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("session and questions must be set");
        }
        String id = "q_" + sequence.incrementAndGet();
        CompletableFuture<Answer> future = new CompletableFuture<>();
        futures.put(id, future);
        pendingById.put(id, new Pending(id, session, session == null ? "" : session.value(),
                List.copyOf(questions), Instant.now()));
        sessionToQuestionId.put(session.value(), id);
        // Publish SSE event for real-time UI update
        if (eventBus != null) {
            try {
                String json = "{\"type\":\"question_pending\",\"id\":\"" + id + "\",\"sessionId\":\"" + (session == null ? "" : session.value()) + "\"}";
                eventBus.publishLive("questions", json);
            } catch (Exception ignored) { }
        }
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new Answer(List.of(), "");
        } finally {
            futures.remove(id);
            pendingById.remove(id);
            sessionToQuestionId.remove(session.value(), id);
        }
    }

    /** All currently pending questions (across all sessions). */
    public List<Pending> listPending() {
        return new ArrayList<>(pendingById.values());
    }

    /** The current pending question for a session, if any. */
    public de.augmentia.oc.engine.core.model.Option<Pending> pending(SessionId session) {
        if (session == null) return de.augmentia.oc.engine.core.model.Option.none();
        String id = sessionToQuestionId.get(session.value());
        if (id == null) return de.augmentia.oc.engine.core.model.Option.none();
        return de.augmentia.oc.engine.core.model.Option.of(pendingById.get(id));
    }

    /** Resolves a pending question by its ID. Returns true when it was still open. */
    public boolean resolve(String questionId, List<String> selected, String text) {
        if (questionId == null) return false;
        CompletableFuture<Answer> future = futures.remove(questionId);
        if (future == null) return false;
        Pending p = pendingById.remove(questionId);
        if (p != null) sessionToQuestionId.remove(p.session().value(), questionId);
        // Publish SSE event for real-time UI update
        if (eventBus != null) {
            try {
                String json = "{\"type\":\"question_resolved\",\"id\":\"" + questionId + "\"}";
                eventBus.publishLive("questions", json);
            } catch (Exception ignored) { }
        }
        future.complete(new Answer(selected, text));
        return true;
    }

    /** Resolves a pending question for a session. Returns true when it was still open. */
    public boolean resolve(SessionId session, String questionId, List<String> selected, String text) {
        return resolve(questionId, selected, text);
    }

    /** Cleans up an externally registered future. */
    public void cleanupExternalFuture(String questionId) {
        if (questionId == null) return;
        futures.remove(questionId);
        Pending p = pendingById.remove(questionId);
        if (p != null) sessionToQuestionId.remove(p.session().value(), questionId);
    }
}
