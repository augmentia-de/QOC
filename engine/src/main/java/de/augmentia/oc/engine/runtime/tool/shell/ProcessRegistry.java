package de.augmentia.oc.engine.runtime.tool.shell;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Session-scoped registry for background shell processes. The registry owns
 * the {@link ProcessRunner.Handle}, assigns a stable id, observes the exit
 * status and reaps the handle when the command ends.
 *
 * <p>The registry is in-memory by design: a process outlives a prompt, but not
 * an engine restart. The id is therefore stable only for the lifetime of the
 * engine process, which is exactly what the background shell contract needs.
 */
public final class ProcessRegistry {

    private static final Logger LOG = Logger.getLogger(ProcessRegistry.class.getName());
    private static final long POLL_MILLIS = 25;

    public enum State {
        RUNNING,
        COMPLETED,
        FAILED,
        KILLED
    }

    public record Snapshot(String id, SessionId session, String command, State state,
                           String stdout, String stderr, Option<Integer> exit,
                           Instant started, Instant finished) {
        public Snapshot {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            if (session == null) throw new IllegalArgumentException("session must not be null");
            if (command == null) command = "";
            if (state == null) state = State.RUNNING;
            if (stdout == null) stdout = "";
            if (stderr == null) stderr = "";
            if (exit == null) exit = Option.none();
            if (started == null) started = Instant.EPOCH;
        }

        public boolean running() {
            return state == State.RUNNING;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Consumer<Snapshot> listener;

    public ProcessRegistry() {
        this(null);
    }

    /**
     * @param listener optional completion observer. It is called exactly once
     *                 per process after the terminal state is visible.
     */
    public ProcessRegistry(Consumer<Snapshot> listener) {
        this.listener = listener;
    }

    /** Registers an already started handle and returns the stable process id. */
    public String register(SessionId session, String command, ProcessRunner.Handle handle) {
        if (session == null || handle == null) {
            throw new IllegalArgumentException("session and handle must not be null");
        }
        String id = "sh_" + UUID.randomUUID();
        Entry entry = new Entry(id, session, command == null ? "" : command, handle);
        entries.put(id, entry);
        Thread.ofVirtual().name("qoc-shell-" + id).start(() -> watch(entry));
        return id;
    }

    /** Returns a snapshot only when the process belongs to the given session. */
    public Option<Snapshot> get(SessionId session, String id) {
        Entry entry = entry(session, id);
        return entry == null ? Option.none() : Option.some(snapshot(entry));
    }

    /** All known processes of a session, oldest first. */
    public List<Snapshot> list(SessionId session) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        return entries.values().stream()
                .filter(entry -> entry.session.equals(session))
                .map(this::snapshot)
                .sorted(Comparator.comparing(Snapshot::started))
                .toList();
    }

    /** Waits until the process ends or the timeout expires. */
    public Option<Snapshot> await(SessionId session, String id, Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be null or negative");
        }
        Entry entry = entry(session, id);
        if (entry == null) return Option.none();
        try {
            entry.done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return get(session, id);
    }

    /** Kills a running process and records it as killed. */
    public boolean kill(SessionId session, String id) {
        Entry entry = entry(session, id);
        if (entry == null || !entry.running()) return false;
        entry.handle.kill();
        finish(entry, State.KILLED, Option.none());
        return true;
    }

    /** Kills and forgets every process of a session. */
    public void closeSession(SessionId session) {
        if (session == null) return;
        for (Entry entry : entries.values()) {
            if (!entry.session.equals(session)) continue;
            if (entry.running()) {
                entry.handle.kill();
                finish(entry, State.KILLED, Option.none());
            }
            entries.remove(entry.id, entry);
        }
    }

    public void closeAll() {
        for (Entry entry : entries.values()) {
            if (entry.running()) {
                entry.handle.kill();
                finish(entry, State.KILLED, Option.none());
            }
        }
        entries.clear();
    }

    private Entry entry(SessionId session, String id) {
        if (session == null || id == null || id.isBlank()) return null;
        Entry entry = entries.get(id);
        return entry != null && entry.session.equals(session) ? entry : null;
    }

    private void watch(Entry entry) {
        while (entry.running()) {
            Option<Integer> exit = entry.handle.exitCode();
            if (exit.isPresent()) {
                int code = exit.orElse(-1);
                finish(entry, code == 0 ? State.COMPLETED : State.FAILED, exit);
                return;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void finish(Entry entry, State state, Option<Integer> exit) {
        if (!entry.state.compareAndSet(State.RUNNING, state)) return;
        entry.exit = exit == null ? Option.none() : exit;
        entry.stdout = entry.handle.output();
        entry.stderr = entry.handle.errorOutput();
        entry.finished = Instant.now();
        try {
            entry.handle.close();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "cannot close background shell " + entry.id, e);
        }
        entry.done.countDown();
        if (listener != null) {
            try {
                listener.accept(snapshot(entry));
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "background shell listener failed for " + entry.id, e);
            }
        }
    }

    private Snapshot snapshot(Entry entry) {
        return new Snapshot(entry.id, entry.session, entry.command, entry.state.get(),
                entry.stdout, entry.stderr, entry.exit, entry.started, entry.finished);
    }

    private static final class Entry {

        private final String id;
        private final SessionId session;
        private final String command;
        private final ProcessRunner.Handle handle;
        private final Instant started = Instant.now();
        private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile String stdout = "";
        private volatile String stderr = "";
        private volatile Option<Integer> exit = Option.none();
        private volatile Instant finished;

        private Entry(String id, SessionId session, String command, ProcessRunner.Handle handle) {
            this.id = id;
            this.session = session;
            this.command = command;
            this.handle = handle;
        }

        private boolean running() {
            return state.get() == State.RUNNING;
        }
    }
}
