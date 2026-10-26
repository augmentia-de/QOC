package de.augmentia.oc.engine.adapter.process;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** JDK process runner for the shell tool and local MCP servers. */
public final class JdkProcessRunner implements ProcessRunner {

    @Override
    public Handle start(ProcessSpec spec) {
        if (spec == null) throw new IllegalArgumentException("spec must not be null");
        Process process = spawn(spec);
        return new JdkHandle(process, spec.stdin());
    }

    @Override
    public Exit run(ProcessSpec spec, Duration timeout) {
        if (spec == null || timeout == null) throw new IllegalArgumentException("spec and timeout must not be null");
        Process process = spawn(spec);
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outReader = Thread.ofVirtual().start(() -> pump(process.getInputStream(), stdout));
        Thread errReader = Thread.ofVirtual().start(() -> pump(process.getErrorStream(), stderr));
        try {
            spec.stdin().ifPresent(input -> {
                try (OutputStream out = process.getOutputStream()) {
                    out.write(input.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            boolean finished = timeout.isZero() || timeout.isNegative()
                    ? waitForever(process)
                    : process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) process.destroyForcibly();
            outReader.join(2_000);
            errReader.join(2_000);
            if (!finished) {
                return new Exit(124, stdout.toString(), stderr.toString(), true);
            }
            return new Exit(process.exitValue(), stdout.toString(), stderr.toString(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new UncheckedIOException(new IOException("interrupted", e));
        }
    }

    private static void pump(InputStream stream, StringBuilder target) {
        try (InputStream in = stream) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                synchronized (target) {
                    target.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
            // Process ended.
        }
    }

    private static boolean waitForever(Process process) throws InterruptedException {
        process.waitFor();
        return true;
    }

    private static Process spawn(ProcessSpec spec) {
        List<String> command = spec.pty() ? ptyCommand(spec.command()) : spec.command();
        ProcessBuilder builder = new ProcessBuilder(command);
        spec.cwd().ifPresent(path -> builder.directory(path.toFile()));
        builder.redirectErrorStream(false);
        Map<String, String> environment = builder.environment();
        environment.putAll(spec.env());
        try {
            return builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start process", e);
        }
    }

    /**
     * A one-off pseudo terminal via {@code script(1)} when available. The flag
     * requests PTY semantics (single command); a persistent PTY across calls is
     * not modelled here. Without {@code script} the command runs normally.
     */
    private static List<String> ptyCommand(List<String> command) {
        String script = scriptBinary();
        if (script == null) return command;
        StringBuilder line = new StringBuilder();
        for (String argument : command) {
            if (line.length() > 0) line.append(' ');
            line.append('\'').append(argument.replace("'", "'\\''")).append('\'');
        }
        return List.of(script, "-qefc", line.toString(), "/dev/null");
    }

    private static String scriptBinary() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(directory, "script");
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        return null;
    }

    private static final class JdkHandle implements Handle {

        private final Process process;
        private final StringBuilder output = new StringBuilder();
        private boolean closed;

        private JdkHandle(Process process, Option<String> stdin) {
            this.process = process;
            Thread.ofVirtual().start(this::pump);
            stdin.ifPresent(this::write);
        }

        private void pump() {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    synchronized (output) {
                        output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                        output.notifyAll();
                    }
                }
            } catch (IOException ignored) {
                // Process ended.
            }
        }

        @Override
        public void write(String stdin) {
            if (stdin == null) return;
            try {
                OutputStream out = process.getOutputStream();
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String output() {
            synchronized (output) {
                return output.toString();
            }
        }

        @Override
        public Option<Integer> exitCode() {
            return process.isAlive() ? Option.none() : Option.some(process.exitValue());
        }

        @Override
        public void kill() {
            process.destroyForcibly();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            process.destroy();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
