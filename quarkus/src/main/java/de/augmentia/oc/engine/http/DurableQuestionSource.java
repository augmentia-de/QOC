package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.adapter.sql.QuestionSql;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.human.NotificationChannel;
import de.augmentia.oc.engine.core.port.human.QuestionSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Durable question source. Persists questions to the database, sends a
 * notification (email) with a single-use reply link, and polls the database
 * for an answer instead of holding an in-memory future indefinitely.
 *
 * <p>On timeout the question is marked as expired and an empty answer is
 * returned so the agent can continue or retry.</p>
 */
@ApplicationScoped
public class DurableQuestionSource implements QuestionSource {

    private static final Logger LOG = Logger.getLogger(DurableQuestionSource.class.getName());

    @Inject
    Instance<DataSource> dataSource;

    @Inject
    @ConfigProperty(name = "engine.storage", defaultValue = "")
    String storageKind;

    private DataSource ds() {
        if ("memory".equalsIgnoreCase(storageKind)) return null;
        return dataSource.isResolvable() ? dataSource.get() : null;
    }

    @Inject
    NotificationChannel notificationChannel;

    @Inject
    WebQuestionSource webQuestionSource;

    @Inject
    @ConfigProperty(name = "engine.question.timeout-seconds", defaultValue = "300")
    long timeoutSeconds;

    @Inject
    @ConfigProperty(name = "engine.approval.base-url", defaultValue = "http://localhost:8080")
    String baseUrl;

    private final SecureRandom random = new SecureRandom();

    @Override
    public Answer ask(SessionId session, List<Question> questions) {
        if (session == null || questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("session and questions must be set");
        }
        if (ds() == null) {
            return webQuestionSource.ask(session, questions);
        }
        String id = "q_" + System.currentTimeMillis() + "_" + random.nextInt(1000);
        Instant now = Instant.now();
        Instant deadline = now.plus(timeoutSeconds, ChronoUnit.SECONDS);
        String token = generateToken();
        String tokenHash = sha256(token);
        String url = baseUrl + "/api/question/" + token;

        // Persist to DB
        try {
            persistPending(id, session.value(), questions, now, deadline, tokenHash);
        } catch (Exception e) {
            LOG.warning("question store unavailable: " + e.getMessage());
            // Fall back to web question source
            return webQuestionSource.ask(session, questions);
        }

        // Send notification
        try {
            String qText = questions.stream().map(QuestionSource.Question::question).reduce((a, b) -> a + "\n" + b).orElse("");
            notificationChannel.sendQuestion(id, session, qText, url);
        } catch (RuntimeException e) {
            LOG.warning("notification failed: " + e.getMessage());
        }

        // Use an external future so that both the UI (via REST) and the
        // email poller can resolve it, and the prompt thread unblocks.
        java.util.concurrent.CompletableFuture<Answer> future = new java.util.concurrent.CompletableFuture<>();
        String webId = webQuestionSource.registerExternalFuture(future, session, questions);

        // Start email polling in background
        Thread emailPoller = new Thread(() -> {
            long pollMs = 2000;
            long deadlineMs = deadline.toEpochMilli();
            while (System.currentTimeMillis() < deadlineMs && !future.isDone()) {
                try { Thread.sleep(pollMs); } catch (InterruptedException e) { return; }
                String[] answer = pollAnswer(id);
                if (answer != null) {
                    markAnswered(id);
                    List<String> selected = answer[0] == null || answer[0].isBlank()
                            ? List.of() : List.of(answer[0].split(","));
                    future.complete(new Answer(selected, answer[1] == null ? "" : answer[1]));
                    return;
                }
            }
            if (!future.isDone()) {
                markExpired(id);
                future.complete(new Answer(List.of(), ""));
            }
        }, "email-poller-" + id);
        emailPoller.setDaemon(true);
        emailPoller.start();

        // Block on our own future (UI can answer via REST → webQuestionSource.resolve(webId, ...) )
        Answer result;
        try {
            result = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            result = new Answer(List.of(), "");
        }

        // Clean up
        emailPoller.interrupt();
        webQuestionSource.cleanupExternalFuture(webId);
        // If answered via UI, update DB
        if (result != null && (!result.selected().isEmpty() || !result.text().isBlank())) {
            try {
                QuestionSql.updateAnswer(ds(), id, String.join(",", result.selected()), result.text());
            } catch (Exception e) {
                LOG.warning("update answer failed: " + e.getMessage());
            }
        }

        return result;
    }

    /** All currently pending questions (delegates to web source). */
    public java.util.List<WebQuestionSource.Pending> listPending() {
        return webQuestionSource.listPending();
    }

    /** Resolves a pending question (delegates to web source). */
    public boolean resolve(String questionId, java.util.List<String> selected, String text) {
        return webQuestionSource.resolve(questionId, selected, text);
    }

    /** The current pending question for a session, if any. */
    public de.augmentia.oc.engine.core.model.Option<WebQuestionSource.Pending> pending(SessionId session) {
        return webQuestionSource.pending(session);
    }

    /** Resolves a pending question by token (from email reply). */
    public boolean resolveByToken(String token, List<String> selected, String text) {
        String qid = findQuestionByToken(token);
        if (qid == null) return false;
        return resolve(qid, selected, text);
    }

    /** Resolves a pending question for a session. */
    public boolean resolve(de.augmentia.oc.engine.core.model.SessionId session, String questionId,
                          java.util.List<String> selected, String text) {
        return webQuestionSource.resolve(session, questionId, selected, text);
    }

    private void persistPending(String id, String sessionId, List<Question> questions,
                                Instant now, Instant deadline, String tokenHash) throws Exception {
        // Serialize questions to JSON
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < questions.size(); i++) {
            if (i > 0) json.append(",");
            json.append("{\"header\":\"").append(escape(questions.get(i).header()))
                    .append("\",\"question\":\"").append(escape(questions.get(i).question()))
                    .append("\",\"multiple\":").append(questions.get(i).multiple()).append("}");
        }
        json.append("]");

        QuestionSql.persistPending(ds(), id, sessionId, json.toString(), now, deadline, tokenHash);
    }

    private String[] pollAnswer(String id) {
        try {
            return QuestionSql.pollAnswer(ds(), id);
        } catch (Exception e) {
            LOG.warning("poll failed: " + e.getMessage());
            return null;
        }
    }

    private void markAnswered(String id) {
        try {
            QuestionSql.markAnswered(ds(), id);
        } catch (Exception e) {
            LOG.warning("mark answered failed: " + e.getMessage());
        }
    }

    private void markExpired(String id) {
        try {
            QuestionSql.markExpired(ds(), id);
        } catch (Exception e) {
            LOG.warning("mark expired failed: " + e.getMessage());
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Finds a pending question by its reply token. Returns the question ID or null. */
    public String findQuestionByToken(String token) {
        if (token == null || token.isBlank()) return null;
        if (ds() == null) return null;
        try {
            return QuestionSql.findIdByToken(ds(), sha256(token));
        } catch (Exception e) {
            LOG.warning("find by token failed: " + e.getMessage());
            return null;
        }
    }

    /** Gets the session ID for a question. */
    public String getSessionIdForQuestion(String questionId) {
        if (ds() == null) return null;
        try {
            return QuestionSql.sessionIdForQuestion(ds(), questionId);
        } catch (Exception e) {
            LOG.warning("get session failed: " + e.getMessage());
            return null;
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
