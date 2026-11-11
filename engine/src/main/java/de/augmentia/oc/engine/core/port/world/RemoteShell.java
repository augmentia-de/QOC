package de.augmentia.oc.engine.core.port.world;

import de.augmentia.oc.engine.core.model.Option;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Remote shell port: SSH-based command execution and file operations on
 * configured hosts. The runtime uses only this port; the adapter implements
 * it with an SSH library.
 */
public interface RemoteShell {

    /**
     * Resolves a host alias to its configuration. Returns empty if unknown.
     */
    Option<HostConfig> resolveHost(String alias);

    /**
     * Executes a command on the remote host.
     */
    ExecResult exec(HostConfig host, String command, Option<String> cwd, Duration timeout);

    /**
     * Reads a remote file.
     */
    ReadResult read(HostConfig host, String path, int maxBytes);

    /**
     * Writes a remote file.
     */
    WriteResult write(HostConfig host, String path, String content, String encoding);

    /**
     * Lists a remote directory.
     */
    ListResult list(HostConfig host, String path, int maxEntries);

    /**
     * Reads metadata of a remote file/directory.
     */
    StatResult stat(HostConfig host, String path);

    // ── Records ──

    record HostConfig(
            String alias,
            String hostname,
            int port,
            String username,
            String keyPath,
            String knownHostsPath,
            boolean strictHostKeyChecking,
            List<String> allowedActions,
            List<String> allowedCommands,
            List<String> deniedCommands,
            List<String> allowedPaths,
            String cwd,
            Duration defaultTimeout,
            int maxOutputBytes,
            int maxFileBytes,
            int maxEntries
    ) {
        public HostConfig {
            if (alias == null || alias.isBlank()) throw new IllegalArgumentException("alias required");
            if (hostname == null || hostname.isBlank()) throw new IllegalArgumentException("hostname required");
            if (username == null || username.isBlank()) throw new IllegalArgumentException("username required");
            if (port <= 0) port = 22;
            if (allowedActions == null) allowedActions = List.of();
            if (allowedCommands == null) allowedCommands = List.of();
            if (deniedCommands == null) deniedCommands = List.of();
            if (allowedPaths == null) allowedPaths = List.of();
            if (defaultTimeout == null) defaultTimeout = Duration.ofSeconds(10);
            if (maxOutputBytes <= 0) maxOutputBytes = 65536;
            if (maxFileBytes <= 0) maxFileBytes = 1048576;
            if (maxEntries <= 0) maxEntries = 1000;
        }
    }

    record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut, long durationMs) {}

    record ReadResult(String path, String content, String encoding, int bytes, boolean truncated) {}

    record WriteResult(String path, int bytesWritten) {}

    record ListEntry(String name, String type, long size, String mtime) {}

    record ListResult(String path, List<ListEntry> entries, boolean truncated) {}

    record StatResult(String path, String type, long size, String mtime, String mode) {}
}
