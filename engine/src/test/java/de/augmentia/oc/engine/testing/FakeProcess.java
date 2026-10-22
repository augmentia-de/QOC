package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Scripted process runner: one configured exit per call. */
public final class FakeProcess implements ProcessRunner {

    private final Deque<Exit> exits;
    private final List<ProcessSpec> calls = new ArrayList<>();
    private final List<Handle> handles = new ArrayList<>();

    public FakeProcess(Exit... exits) {
        this(List.of(exits));
    }

    public FakeProcess(List<Exit> exits) {
        this.exits = new ArrayDeque<>(exits == null ? List.of() : exits);
    }

    @Override
    public synchronized Handle start(ProcessSpec spec) {
        if (spec == null) throw new IllegalArgumentException("spec must not be null");
        calls.add(spec);
        Handle handle = new ScriptedHandle(next());
        handles.add(handle);
        return handle;
    }

    @Override
    public synchronized Exit run(ProcessSpec spec, Duration timeout) {
        if (spec == null) throw new IllegalArgumentException("spec must not be null");
        if (timeout == null) throw new IllegalArgumentException("timeout must not be null");
        calls.add(spec);
        return next();
    }

    public synchronized List<ProcessSpec> calls() {
        return List.copyOf(calls);
    }

    public synchronized List<Handle> handles() {
        return List.copyOf(handles);
    }

    private Exit next() {
        Exit exit = exits.pollFirst();
        if (exit == null) {
            throw new IllegalStateException("FakeProcess has no scripted exit left");
        }
        return exit;
    }

    private static final class ScriptedHandle implements Handle {

        private final Exit exit;
        private final StringBuilder stdin = new StringBuilder();
        private boolean killed;
        private boolean closed;

        private ScriptedHandle(Exit exit) {
            this.exit = exit;
        }

        @Override
        public void write(String input) {
            if (input != null) stdin.append(input);
        }

        @Override
        public String output() {
            return exit.stdout();
        }

        @Override
        public Option<Integer> exitCode() {
            return killed ? Option.none() : Option.some(exit.code());
        }

        @Override
        public void kill() {
            killed = true;
        }

        @Override
        public void close() {
            closed = true;
        }

        public String stdin() {
            return stdin.toString();
        }

        public boolean killed() {
            return killed;
        }

        public boolean closed() {
            return closed;
        }
    }
}
