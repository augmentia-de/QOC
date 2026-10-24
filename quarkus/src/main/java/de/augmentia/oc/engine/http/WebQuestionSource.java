package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.human.QuestionSource;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Human-in-the-loop questions for the web UI. {@link #ask} blocks until the UI
 * posts an answer; a timeout returns an empty answer.
 */
@ApplicationScoped
public class WebQuestionSource implements QuestionSource {

    private static final long TIMEOUT_SECONDS = 300;

    public record Pending(String id, SessionId session, List<Question> questions) {}

    private final Map<String, CompletableFuture<Answer>> futures = new ConcurrentHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public Answer ask(SessionId session, List<Question> questions) {
        if (session == null || questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("session and questions must be set");
        }
        String id = "q_" + sequence.incrementAndGet();
        CompletableFuture<Answer> future = new CompletableFuture<>();
        futures.put(session.value() + ":" + id, future);
        pending.put(session.value(), new Pending(id, session, List.copyOf(questions)));
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new Answer(List.of(), "");
        } finally {
            futures.remove(session.value() + ":" + id);
            pending.remove(session.value(), pending.get(session.value()));
        }
    }

    /** The current pending question for a session, if any. */
    public de.augmentia.oc.engine.core.model.Option<Pending> pending(SessionId session) {
        if (session == null) return de.augmentia.oc.engine.core.model.Option.none();
        return de.augmentia.oc.engine.core.model.Option.of(pending.get(session.value()));
    }

    /** Resolves a pending question. Returns true when it was still open. */
    public boolean resolve(SessionId session, String questionId, List<String> selected, String text) {
        if (session == null || questionId == null) return false;
        CompletableFuture<Answer> future = futures.remove(session.value() + ":" + questionId);
        if (future == null) return false;
        pending.remove(session.value());
        return future.complete(new Answer(selected, text));
    }
}
