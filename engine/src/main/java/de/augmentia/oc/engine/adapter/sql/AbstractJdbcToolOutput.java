package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.error.StoreFailure;
import de.augmentia.oc.engine.core.model.ArtifactRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.ToolOutputStore;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.HexFormat;

/** Shared JDBC tool-output store (H9). */
public abstract class AbstractJdbcToolOutput implements ToolOutputStore {

    private static final String MEDIA_TYPE = "text/plain; charset=utf-8";

    protected final DataSource dataSource;
    protected final SqlDialect dialect;

    protected AbstractJdbcToolOutput(DataSource dataSource, java.nio.file.Path directory, SqlDialect dialect) {
        if (dataSource == null || directory == null || dialect == null) {
            throw new IllegalArgumentException("dataSource, directory and dialect must not be null");
        }
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @Override
    public ArtifactRef save(SessionId session, String suffix, byte[] bytes) {
        if (session == null || suffix == null || suffix.isBlank()) {
            throw new IllegalArgumentException("session and suffix must not be blank");
        }
        byte[] content = bytes == null ? new byte[0] : bytes;
        String id = "art_" + suffix;
        String hash = sha256(content);
        ArtifactRef ref = new ArtifactRef(session, id, MEDIA_TYPE, hash, content.length);
        dialect.transaction(dataSource, connection -> {
            Sql.execute(connection,
                    "insert into tool_output " +
                            "(path, content, created_at, session_id, artifact_id, media_type, sha256, size) " +
                            "values (?, ?, " + dialect.nowExpression() + ", ?, ?, ?, ?, ?) " +
                            "on conflict (path) do update set content = excluded.content, " +
                            "session_id = excluded.session_id, artifact_id = excluded.artifact_id, " +
                            "media_type = excluded.media_type, sha256 = excluded.sha256, size = excluded.size",
                    ref.uri(), content, session.value(), id, MEDIA_TYPE, hash, content.length);
            return null;
        });
        return ref;
    }

    @Override
    public Option<ArtifactRef> find(SessionId session, String id) {
        if (session == null || id == null) return Option.none();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select media_type, sha256, size from tool_output " +
                             "where session_id = ? and artifact_id = ?")) {
            statement.setString(1, session.value());
            statement.setString(2, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) return Option.none();
                return Option.some(new ArtifactRef(session, id,
                        resultSet.getString("media_type"), resultSet.getString("sha256"),
                        resultSet.getLong("size")));
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot find tool output", e);
        }
    }

    @Override
    public byte[] load(SessionId session, String id) {
        if (session == null || id == null) throw new StoreFailure("unknown tool output: " + id);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select content from tool_output where session_id = ? and artifact_id = ?")) {
            statement.setString(1, session.value());
            statement.setString(2, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new StoreFailure("unknown tool output: " + id);
                return resultSet.getBytes("content");
            }
        } catch (java.sql.SQLException e) {
            throw new StoreFailure("cannot read tool output", e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
