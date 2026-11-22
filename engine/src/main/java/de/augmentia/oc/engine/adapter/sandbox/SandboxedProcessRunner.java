package de.augmentia.oc.engine.adapter.sandbox;

import de.augmentia.oc.engine.adapter.process.JdkProcessRunner;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional sandboxed process runner.
 *
 * <p>In {@code local} mode it delegates to {@link JdkProcessRunner} but
 * enforces that the working directory stays inside the configured workspace
 * root (path-traversal guard).</p>
 *
 * <p>In {@code container} mode it wraps the command in a rootless podman
 * invocation when podman is available; otherwise it falls back to local mode
 * with a warning.</p>
 */
public final class SandboxedProcessRunner implements ProcessRunner {

    private final SandboxConfig config;
    private final ProcessRunner delegate;
    private final boolean containerAvailable;

    public SandboxedProcessRunner(SandboxConfig config) {
        this(config, new JdkProcessRunner());
    }

    public SandboxedProcessRunner(SandboxConfig config, ProcessRunner delegate) {
        this.config = config == null ? new SandboxConfig("local", null, null, 0, 0, 0, null, null) : config;
        this.delegate = delegate == null ? new JdkProcessRunner() : delegate;
        this.containerAvailable = this.config.isContainerMode() && podmanAvailable();
    }

    @Override
    public Handle start(ProcessSpec spec) {
        return delegate.start(wrap(spec));
    }

    @Override
    public Exit run(ProcessSpec spec, Duration timeout) {
        return delegate.run(wrap(spec), timeout);
    }

    private ProcessSpec wrap(ProcessSpec spec) {
        // Path guard: ensure cwd stays inside workspace root.
        Option<Path> cwd = spec.cwd();
        if (cwd instanceof Option.Some<Path> s) {
            Path resolved = s.value().toAbsolutePath().normalize();
            Path root = config.workspaceRoot().toAbsolutePath().normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalArgumentException("cwd escapes workspace root: " + resolved);
            }
        }

        if (!config.isContainerMode() || !containerAvailable) {
            return spec;
        }

        // Wrap in podman run.
        List<String> cmd = new ArrayList<>();
        cmd.add("podman");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("-i");
        if (config.cpuLimit() > 0) {
            cmd.add("--cpus");
            cmd.add(String.valueOf(config.cpuLimit()));
        }
        if (config.memoryMb() > 0) {
            cmd.add("--memory");
            cmd.add(config.memoryMb() + "m");
        }
        // Network policy.
        switch (config.network()) {
            case "none" -> cmd.add("--network=none");
            case "outbound-only", "allowlist" -> { /* default bridge; egress enforced by proxy externally */ }
            default -> { }
        }
        // Workspace mount.
        cmd.add("-v");
        cmd.add(config.workspaceRoot().toAbsolutePath() + ":/workspace:rw");
        cmd.add("-w");
        cmd.add("/workspace");
        cmd.add(config.image());
        cmd.addAll(spec.command());

        Option<Path> newCwd = spec.cwd().isPresent()
                ? Option.some(Path.of("/workspace"))
                : Option.none();
        return new ProcessSpec(cmd, newCwd, spec.env(), spec.stdin(), spec.pty());
    }

    private static boolean podmanAvailable() {
        try {
            Process p = new ProcessBuilder("podman", "--version").start();
            return p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}