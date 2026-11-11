package de.augmentia.oc.engine.adapter.ssh;

import de.augmentia.oc.engine.core.port.world.RemoteShell;
import de.augmentia.oc.engine.core.model.Option;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Apache MINA SSHD adapter for the RemoteShell port.
 * Supports exec, read, write, list, stat. Download/upload not yet implemented.
 */
public class MinaSshRemoteShell implements RemoteShell, AutoCloseable {

    private final SshClient client;

    public MinaSshRemoteShell() {
        client = SshClient.setUpDefaultClient();
        client.start();
    }

    @Override
    public Option<HostConfig> resolveHost(String alias) {
        // Host resolution is handled by the caller (config). This adapter
        // assumes the host config is already resolved.
        return Option.none();
    }

    @Override
    public ExecResult exec(HostConfig host, String command, Option<String> cwd, Duration timeout) {
        long start = System.currentTimeMillis();
        try (ClientSession session = connect(host)) {
            String fullCommand = command;
            if (cwd.isPresent() && cwd.orElse(null) != null && !cwd.orElse(null).isBlank()) {
                fullCommand = "cd " + shellQuote(cwd.orElse(null)) + " && " + command;
            }
            org.apache.sshd.client.channel.ChannelExec channel = session.createExecChannel(fullCommand);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOut(stdout);
            channel.setErr(stderr);
            channel.open().verify(timeout == null ? Duration.ofSeconds(10) : timeout);
            channel.waitFor(java.util.Collections.singleton(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED),
                    timeout == null ? Duration.ofSeconds(10) : timeout);
            int exitCode = channel.getExitStatus() == null ? -1 : channel.getExitStatus();
            channel.close();
            long duration = System.currentTimeMillis() - start;
            String out = truncate(stdout.toString(StandardCharsets.UTF_8), host.maxOutputBytes());
            String err = truncate(stderr.toString(StandardCharsets.UTF_8), host.maxOutputBytes());
            return new ExecResult(exitCode, out, err, false, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new ExecResult(-1, "", e.getMessage(), true, duration);
        }
    }

    @Override
    public ReadResult read(HostConfig host, String path, int maxBytes) {
        try (ClientSession session = connect(host);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            String normalized = normalizePath(path, host);
            int limit = Math.min(maxBytes > 0 ? maxBytes : host.maxFileBytes(), host.maxFileBytes());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (var is = sftp.read(normalized)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = is.read(buf)) != -1 && baos.size() < limit) {
                    int toWrite = Math.min(read, limit - baos.size());
                    baos.write(buf, 0, toWrite);
                }
            }
            boolean truncated = baos.size() >= limit;
            String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            return new ReadResult(normalized, content, "utf-8", baos.size(), truncated);
        } catch (Exception e) {
            throw new IllegalStateException("read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public WriteResult write(HostConfig host, String path, String content, String encoding) {
        try (ClientSession session = connect(host);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            String normalized = normalizePath(path, host);
            byte[] data = "base64".equals(encoding)
                    ? java.util.Base64.getDecoder().decode(content)
                    : content.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = sftp.write(normalized)) {
                os.write(data);
            }
            return new WriteResult(normalized, data.length);
        } catch (Exception e) {
            throw new IllegalStateException("write failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ListResult list(HostConfig host, String path, int maxEntries) {
        try (ClientSession session = connect(host);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            String normalized = normalizePath(path, host);
            int limit = Math.min(maxEntries > 0 ? maxEntries : host.maxEntries(), host.maxEntries());
            List<ListEntry> entries = new ArrayList<>();
            boolean truncated = false;
            for (SftpClient.DirEntry e : sftp.readDir(normalized)) {
                if (entries.size() >= limit) { truncated = true; break; }
                String type = e.getAttributes().isDirectory() ? "directory" : "file";
                entries.add(new ListEntry(e.getFilename(), type, e.getAttributes().getSize(), String.valueOf(e.getAttributes().getModifyTime())));
            }
            return new ListResult(normalized, entries, truncated);
        } catch (Exception e) {
            throw new IllegalStateException("list failed: " + e.getMessage(), e);
        }
    }

    @Override
    public StatResult stat(HostConfig host, String path) {
        try (ClientSession session = connect(host);
             SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            String normalized = normalizePath(path, host);
            SftpClient.Attributes attrs = sftp.stat(normalized);
            String type = attrs.isDirectory() ? "directory" : "file";
            return new StatResult(normalized, type, attrs.getSize(),
                    String.valueOf(attrs.getModifyTime()), String.format("%04o", attrs.getPermissions()));
        } catch (Exception e) {
            throw new IllegalStateException("stat failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (client != null && client.isStarted()) {
            client.stop();
        }
    }

    // ── Helpers ──

    private ClientSession connect(HostConfig host) throws IOException {
        org.apache.sshd.client.future.ConnectFuture cf = client.connect(host.username(), host.hostname(), host.port());
        cf.verify(Duration.ofSeconds(10));
        ClientSession session = cf.getSession();
        // Load key
        if (host.keyPath() != null && !host.keyPath().isBlank()) {
            Path keyFile = Path.of(host.keyPath());
            if (Files.exists(keyFile)) {
                org.apache.sshd.common.keyprovider.FileKeyPairProvider provider =
                        new org.apache.sshd.common.keyprovider.FileKeyPairProvider(keyFile);
                session.addPublicKeyIdentity(provider.loadKeys(null).iterator().next());
            }
        }
        session.auth().verify(Duration.ofSeconds(10));
        return session;
    }

    private String normalizePath(String path, HostConfig host) {
        if (path == null || path.isBlank()) return host.cwd() == null ? "/" : host.cwd();
        String p = path.trim();
        if (p.contains("..") || p.contains("\0")) throw new IllegalArgumentException("invalid path");
        if (!p.startsWith("/")) {
            String base = host.cwd() == null ? "" : host.cwd();
            p = base.endsWith("/") ? base + p : base + "/" + p;
        }
        return p;
    }

    private String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private String truncate(String s, int maxBytes) {
        if (s == null) return "";
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return s;
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8) + "...[truncated]";
    }

}
