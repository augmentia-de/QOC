package de.augmentia.oc.engine.core.port.world;

import de.augmentia.oc.engine.core.model.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Process execution for the shell tool. */
public interface ProcessRunner {

    Handle start(ProcessSpec spec);

    Exit run(ProcessSpec spec, Duration timeout);

    record ProcessSpec(List<String> command, Option<Path> cwd, Map<String, String> env,
                       Option<String> stdin, boolean pty) {
        public ProcessSpec {
            if (command == null || command.isEmpty()) {
                throw new IllegalArgumentException("command must not be empty");
            }
            command = List.copyOf(command);
            if (cwd == null) cwd = Option.none();
            env = env == null ? Map.of() : Map.copyOf(env);
            if (stdin == null) stdin = Option.none();
        }
    }

    record Exit(int code, String stdout, String stderr, boolean timedOut) {
        public Exit {
            if (stdout == null) stdout = "";
            if (stderr == null) stderr = "";
        }
    }

    interface Handle extends AutoCloseable {

        void write(String stdin);

        String output();

        /** Stderr captured so far. Implementations that do not separate streams return empty. */
        default String errorOutput() {
            return "";
        }

        Option<Integer> exitCode();

        void kill();

        @Override
        void close();
    }
}
