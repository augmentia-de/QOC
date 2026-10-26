package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.ArtifactRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.ToolOutputStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** In-memory managed storage for bounded tool output. */
public final class InMemoryToolOutput implements ToolOutputStore {

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    @Override
    public synchronized ArtifactRef save(SessionId session, String suffix, byte[] bytes) {
        if (session == null || suffix == null || suffix.isBlank()) {
            throw new IllegalArgumentException("session and suffix must not be blank");
        }
        byte[] content = bytes == null ? new byte[0] : bytes.clone();
        String id = "art_" + suffix;
        files.put(session.value() + "/" + id, content);
        return new ArtifactRef(session, id, "text/plain; charset=utf-8",
                sha256(content), content.length);
    }

    @Override
    public synchronized Option<ArtifactRef> find(SessionId session, String id) {
        if (session == null || id == null) return Option.none();
        byte[] content = files.get(session.value() + "/" + id);
        if (content == null) return Option.none();
        return Option.some(new ArtifactRef(session, id, "text/plain; charset=utf-8",
                sha256(content), content.length));
    }

    @Override
    public synchronized byte[] load(SessionId session, String id) {
        byte[] content = session == null || id == null ? null : files.get(session.value() + "/" + id);
        if (content == null) throw new IllegalArgumentException("unknown artifact: " + id);
        return content.clone();
    }

    @Override
    public synchronized void sweep(Instant olderThan) {
        files.clear();
    }

    public synchronized int size() {
        return files.size();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    public synchronized String text(SessionId session, String id) {
        return new String(load(session, id), StandardCharsets.UTF_8);
    }
}
