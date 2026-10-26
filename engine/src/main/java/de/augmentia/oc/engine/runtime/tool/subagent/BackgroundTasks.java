package de.augmentia.oc.engine.runtime.tool.subagent;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.TokenCount;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session-scoped registry for background subagent runs. A task has one stable
 * id, one terminal state and a result or an error. The registry is in-memory;
 * tasks do not survive an engine restart.
 */
public final class BackgroundTasks {

    public enum State {
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    public record Snapshot(String id, SessionId parent, String agent, State state,
                           String text, String error, Option<SessionId> childSession,
                           TokenCount usage, Instant created, Instant finished) {
        public Snapshot {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            if (parent == null) throw new IllegalArgumentException("parent must not be null");
            if (agent == null) agent = "";
            if (state == null) state = State.RUNNING;
            if (text == null) text = "";
            if (error == null) error = "";
            if (childSession == null) childSession = Option.none();
            if (usage == null) usage = TokenCount.zero();
            if (created == null) created = Instant.EPOCH;
        }

        public boolean running() {
            return state == State.RUNNING;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    public String create(SessionId parent, String agent) {
        if (parent == null) throw new IllegalArgumentException("parent must not be null");
        String id = "task_" + UUID.randomUUID();
        entries.put(id, new Entry(id, parent, agent == null ? "" : agent));
        return id;
    }

    public void attach(String id, Thread thread) {
        Entry entry = entries.get(id);
        if (entry != null) {
            entry.thread = thread;
            if (!entry.running()) thread.interrupt();
        }
    }

    public void succeed(String id, SubagentRunner.SubagentResult result) {
        Entry entry = entries.get(id);
        if (entry == null || !entry.state.compareAndSet(State.RUNNING, State.SUCCEEDED)) return;
        if (result != null) {
            entry.text = result.text() == null ? "" : result.text();
            entry.childSession = Option.of(result.childSession());
            entry.usage = result.usage() == null ? TokenCount.zero() : result.usage();
        }
        entry.finished = Instant.now();
        entry.done.countDown();
    }

    public void fail(String id, Throwable error) {
        Entry entry = entries.get(id);
        if (entry == null || !entry.state.compareAndSet(State.RUNNING, State.FAILED)) return;
        entry.error = error == null ? "background subagent failed"
                : (error.getMessage() == null || error.getMessage().isBlank()
                        ? error.getClass().getSimpleName() : error.getMessage());
        entry.finished = Instant.now();
        entry.done.countDown();
    }

    /** Cancels a running task. The thread is interrupted; a late result is ignored. */
    public boolean cancel(SessionId parent, String id) {
        Entry entry = entry(parent, id);
        if (entry == null || !entry.state.compareAndSet(State.RUNNING, State.CANCELLED)) return false;
        entry.finished = Instant.now();
        Thread thread = entry.thread;
        if (thread != null) thread.interrupt();
        entry.done.countDown();
        return true;
    }

    public Option<Snapshot> get(SessionId parent, String id) {
        Entry entry = entry(parent, id);
        return entry == null ? Option.none() : Option.some(snapshot(entry));
    }

    public List<Snapshot> list(SessionId parent) {
        if (parent == null) throw new IllegalArgumentException("parent must not be null");
        return entries.values().stream()
                .filter(entry -> entry.parent.equals(parent))
                .map(this::snapshot)
                .sorted(Comparator.comparing(Snapshot::created))
                .toList();
    }

    public Option<Snapshot> await(SessionId parent, String id, Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be null or negative");
        }
        Entry entry = entry(parent, id);
        if (entry == null) return Option.none();
        try {
            entry.done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return get(parent, id);
    }

    /** Cancels and forgets every task of a session. */
    public void closeSession(SessionId parent) {
        if (parent == null) return;
        for (Entry entry : entries.values()) {
            if (!entry.parent.equals(parent)) continue;
            if (entry.state.compareAndSet(State.RUNNING, State.CANCELLED)) {
                entry.finished = Instant.now();
                Thread thread = entry.thread;
                if (thread != null) thread.interrupt();
                entry.done.countDown();
            }
            entries.remove(entry.id, entry);
        }
    }

    public void closeAll() {
        for (Entry entry : entries.values()) {
            if (entry.state.compareAndSet(State.RUNNING, State.CANCELLED)) {
                entry.finished = Instant.now();
                Thread thread = entry.thread;
                if (thread != null) thread.interrupt();
                entry.done.countDown();
            }
        }
        entries.clear();
    }

    private Entry entry(SessionId parent, String id) {
        if (parent == null || id == null || id.isBlank()) return null;
        Entry entry = entries.get(id);
        return entry != null && entry.parent.equals(parent) ? entry : null;
    }

    private Snapshot snapshot(Entry entry) {
        return new Snapshot(entry.id, entry.parent, entry.agent, entry.state.get(),
                entry.text, entry.error, entry.childSession, entry.usage,
                entry.created, entry.finished);
    }

    private static final class Entry {

        private final String id;
        private final SessionId parent;
        private final String agent;
        private final Instant created = Instant.now();
        private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Thread thread;
        private volatile String text = "";
        private volatile String error = "";
        private volatile Option<SessionId> childSession = Option.none();
        private volatile TokenCount usage = TokenCount.zero();
        private volatile Instant finished;

        private Entry(String id, SessionId parent, String agent) {
            this.id = id;
            this.parent = parent;
            this.agent = agent;
        }

        private boolean running() {
            return state.get() == State.RUNNING;
        }
    }
}
