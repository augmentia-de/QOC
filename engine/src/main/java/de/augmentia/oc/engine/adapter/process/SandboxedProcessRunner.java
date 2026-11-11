package de.augmentia.oc.engine.adapter.process;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.core.shell.SandboxMode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Process runner that confines shell commands in a Linux mount/user namespace
 * via {@code bwrap} (bubblewrap). Falls back to the wrapped inner runner when
 * bwrap is unavailable or sandboxing is disabled.
 *
 * <p>Design notes:
 * <ul>
 *   <li>The inner runner is {@link JdkProcessRunner}; it spawns the
 *       {@code bwrap} process. bwrap itself creates the namespace and execs
 *       the (sanitised) command.</li>
 *   <li>Environment is cleared inside the sandbox ({@code --clearenv}) and
 *       only an allow-listed subset is re-injected via {@code --setenv}.</li>
 *   <li>{@code --die-with-parent} ensures the sandbox dies with QOC.</li>
 *   <li>If {@code bwrap} cannot be resolved, or the mode is
 *       {@link SandboxMode#NONE}/{@link SandboxMode#DANGER_FULL_ACCESS},
 *       every call is delegated unchanged (backward compatible).</li>
 * </ul>
 */
public final class SandboxedProcessRunner implements ProcessRunner {

    private static final Set<String> DEFAULT_ENV_ALLOWLIST = Set.of(
            "PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TERM",
            "AGENT", "OPENCODE", "AI_AGENT", "OPENCODE_SESSION_ID");

    private final ProcessRunner inner;
    private final SandboxMode mode;
    private final Path workspaceRoot;
    private final List<Path> extraWritable;
    private final int uid;   // -1 = keep current user
    private final int gid;   // -1 = keep current group
    private final String bwrapPath; // resolved absolute path or null
    private final List<String> envAllowlist;

    public SandboxedProcessRunner(ProcessRunner inner, SandboxMode mode, Path workspaceRoot,
                                 List<Path> extraWritable, int uid, int gid,
                                 String bwrapBinary, List<String> envAllowlist) {
        if (inner == null) throw new IllegalArgumentException("inner runner must not be null");
        this.inner = inner;
        this.mode = mode == null ? SandboxMode.NONE : mode;
        this.workspaceRoot = workspaceRoot;
        this.extraWritable = extraWritable == null ? List.of() : List.copyOf(extraWritable);
        this.uid = uid;
        this.gid = gid;
        this.bwrapPath = resolveBwrap(bwrapBinary);
        this.envAllowlist = (envAllowlist == null || envAllowlist.isEmpty())
                ? new ArrayList<>(DEFAULT_ENV_ALLOWLIST) : new ArrayList<>(envAllowlist);
    }

    /** Convenience: sandbox disabled (passthrough). */
    public SandboxedProcessRunner(ProcessRunner inner) {
        this(inner, SandboxMode.NONE, null, List.of(), -1, -1, null, List.of());
    }

    private boolean active() {
        return bwrapPath != null && mode != SandboxMode.NONE
                && mode != SandboxMode.DANGER_FULL_ACCESS;
    }

    @Override
    public Handle start(ProcessSpec spec) {
        if (!active()) return inner.start(spec);
        ProcessSpec wrapped = wrap(spec);
        return inner.start(wrapped);
    }

    @Override
    public Exit run(ProcessSpec spec, Duration timeout) {
        if (!active()) return inner.run(spec, timeout);
        ProcessSpec wrapped = wrap(spec);
        return inner.run(wrapped, timeout);
    }

    private ProcessSpec wrap(ProcessSpec spec) {
        List<String> cmd = new ArrayList<>();
        cmd.add(bwrapPath);
        cmd.add("--die-with-parent");
        // Unshare pid/ipc/uts/cgroup/mnt but NOT net (loopback setup requires
        // CAP_NET_ADMIN) and not user unless a uid mapping is requested.
        cmd.add("--unshare-pid");
        cmd.add("--unshare-ipc");
        cmd.add("--unshare-uts");
        cmd.add("--unshare-cgroup");
        if (uid >= 0) {
            cmd.add("--unshare-user");
        }
        Path ws = spec.cwd().orElse(workspaceRoot);
        // Do not expose the whole host root (which would include /data with
        // the audit DB, logs and secrets). Build a minimal root instead:
        // tmpfs root, read-only system directories, an isolated /proc and /dev,
        // and only the workspace (plus explicitly allowed extras) writable.
        cmd.add("--tmpfs");
        cmd.add("/");
        for (String systemDir : new String[]{"/usr", "/bin", "/sbin", "/lib", "/lib64",
                "/etc", "/var", "/opt", "/home", "/run"}) {
            Path candidate = Path.of(systemDir);
            if (java.nio.file.Files.exists(candidate)) {
                cmd.add("--ro-bind");
                cmd.add(systemDir);
                cmd.add(systemDir);
            }
        }
        cmd.add("--proc");
        cmd.add("/proc");
        cmd.add("--dev");
        cmd.add("/dev");
        cmd.add("--tmpfs");
        cmd.add("/tmp");
        if (ws != null) {
            if (mode == SandboxMode.READ_ONLY) {
                cmd.add("--ro-bind");
            } else {
                cmd.add("--bind");
            }
            cmd.add(ws.toString());
            cmd.add(ws.toString());
        }
        if (mode != SandboxMode.READ_ONLY) {
            for (Path extra : extraWritable) {
                cmd.add("--bind");
                cmd.add(extra.toString());
                cmd.add(extra.toString());
            }
        }

        if (uid >= 0) {
            cmd.add("--uid");
            cmd.add(String.valueOf(uid));
            cmd.add("--gid");
            cmd.add(String.valueOf(gid >= 0 ? gid : uid));
        }

        if (ws != null) {
            cmd.add("--chdir");
            cmd.add(ws.toString());
        } else if (spec.cwd().isPresent()) {
            cmd.add("--chdir");
            cmd.add(spec.cwd().orElseThrow().toString());
        }

        cmd.add("--clearenv");
        Map<String, String> env = new LinkedHashMap<>();
        for (String key : envAllowlist) {
            String v = System.getenv(key);
            if (v != null) env.put(key, v);
        }
        // Always carry the agent/tool env vars supplied by ShellTool.
        for (Map.Entry<String, String> e : spec.env().entrySet()) {
            env.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : env.entrySet()) {
            cmd.add("--setenv");
            cmd.add(e.getKey());
            cmd.add(e.getValue());
        }

        cmd.add("--");
        // PTY handling: replicate JdkProcessRunner.ptyCommand when needed.
        if (spec.pty()) {
            List<String> pty = JdkProcessRunner.ptyCommand(spec.command());
            cmd.addAll(pty);
        } else {
            cmd.addAll(spec.command());
        }

        // cwd: keep original so inner runner sets working dir (bwrap --chdir
        // already handles it; passing Option.none() avoids double handling).
        return new ProcessSpec(cmd, Option.none(), Map.of(), spec.stdin(), false);
    }

    private static String resolveBwrap(String configured) {
        if (configured == null || configured.isBlank()) configured = "bwrap";
        Path p = Paths.get(configured);
        if (p.isAbsolute() && Files.isExecutable(p)) return p.toString();
        // Search PATH
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                Path candidate = Paths.get(dir, configured);
                if (Files.isExecutable(candidate)) return candidate.toString();
            }
        }
        return null;
    }
}