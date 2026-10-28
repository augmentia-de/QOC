package de.augmentia.oc.engine.http.a2a;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.http.Engine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps A2A conversation contexts to QOC sessions.
 *
 * <p>A2A groups tasks by {@code contextId}. Reusing the same QOC session for all
 * tasks of a context keeps the model history consistent. The registry is bounded
 * so long-running servers do not accumulate sessions forever; the least recently
 * used mapping is evicted first. Evicted sessions remain in the QOC session store
 * and are subject to the normal retention policy.</p>
 */
@ApplicationScoped
public class A2ASessionRegistry {
    private static final int MAX_CONTEXTS = 1_000;

    @Inject
    Engine engine;

    @Inject
    A2AServerConfig config;

    private final Map<String, SessionId> sessions =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SessionId> eldest) {
                    return size() > MAX_CONTEXTS;
                }
            };

    /**
     * Returns the session for the given A2A conversation, creating one if necessary.
     *
     * <p>Tasks of the same context share a session. A task without a context gets
     * its own session so unrelated one-shot calls do not share history.</p>
     *
     * @param user authenticated principal name, may be {@code null}
     * @param contextId A2A context identifier, may be {@code null}
     * @param taskId A2A task identifier, may be {@code null}
     */
    public SessionId sessionFor(String user, String contextId, String taskId) {
        String key = key(user, contextId, taskId);
        synchronized (sessions) {
            SessionId existing = sessions.get(key);
            if (existing != null && engine.session(existing).isPresent()) {
                return existing;
            }
            SessionId created = engine.createSession(config.agent(), user);
            sessions.put(key, created);
            return created;
        }
    }

    /** Looks up an existing session without creating one. */
    public Optional<SessionId> find(String user, String contextId, String taskId) {
        String key = key(user, contextId, taskId);
        synchronized (sessions) {
            SessionId id = sessions.get(key);
            if (id == null || engine.session(id).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(id);
        }
    }

    public void forget(String user, String contextId, String taskId) {
        String key = key(user, contextId, taskId);
        synchronized (sessions) {
            sessions.remove(key);
        }
    }

    /** Visible for tests. */
    public int size() {
        synchronized (sessions) {
            return sessions.size();
        }
    }

    private static String key(String user, String contextId, String taskId) {
        String owner = user == null || user.isBlank() ? "anonymous" : user;
        if (contextId != null && !contextId.isBlank()) {
            return owner + "|c:" + contextId;
        }
        if (taskId != null && !taskId.isBlank()) {
            return owner + "|t:" + taskId;
        }
        return owner + "|__default__";
    }
}
