package de.augmentia.oc.engine.adapter.fs;

import de.augmentia.oc.engine.core.model.ArtifactRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.ToolOutputStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * File-system backed tool output store. Large tool results and session artifacts
 * are kept on disk instead of in database BLOB columns, mirroring OpenCode's
 * {@code tool-output} directory approach.
 */
public final class FileSystemToolOutputStore implements ToolOutputStore {

    private static final String MEDIA_TYPE = "text/plain; charset=utf-8";

    private final Path root;

    public FileSystemToolOutputStore(Path root) {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public ArtifactRef save(SessionId session, String suffix, byte[] bytes) {
        if (session == null || suffix == null || suffix.isBlank()) {
            throw new IllegalArgumentException("session and suffix must not be blank");
        }
        byte[] content = bytes == null ? new byte[0] : bytes;
        String id = "art_" + suffix;
        String hash = sha256(content);
        Path file = file(session, id);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (IOException e) {
            throw new IllegalStateException("cannot store tool output: " + file, e);
        }
        return new ArtifactRef(session, id, MEDIA_TYPE, hash, content.length);
    }

    @Override
    public Option<ArtifactRef> find(SessionId session, String id) {
        if (session == null || id == null || id.isBlank()) return Option.none();
        Path file = file(session, id);
        if (!Files.isRegularFile(file)) return Option.none();
        try {
            byte[] content = Files.readAllBytes(file);
            return Option.some(new ArtifactRef(session, id, MEDIA_TYPE, sha256(content), content.length));
        } catch (IOException e) {
            return Option.none();
        }
    }

    @Override
    public byte[] load(SessionId session, String id) {
        if (session == null || id == null || id.isBlank()) {
            throw new IllegalStateException("unknown tool output: " + id);
        }
        Path file = file(session, id);
        try {
            if (!Files.isRegularFile(file)) throw new IllegalStateException("unknown tool output: " + id);
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read tool output: " + file, e);
        }
    }

    @Override
    public void sweep(Instant olderThan) {
        if (olderThan == null || !Files.isDirectory(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                try {
                    Instant modified = Files.getLastModifiedTime(path).toInstant();
                    if (modified.isBefore(olderThan)) Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private Path file(SessionId session, String id) {
        String safeSession = session.value().replaceAll("[^A-Za-z0-9._-]", "_");
        String safeId = id.replaceAll("[^A-Za-z0-9._-]", "_");
        return root.resolve(safeSession).resolve(safeId + ".bin");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}