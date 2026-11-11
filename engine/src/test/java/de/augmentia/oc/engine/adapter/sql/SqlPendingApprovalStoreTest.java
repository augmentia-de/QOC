package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.core.port.store.PendingApprovalStore;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SqlPendingApprovalStoreTest {

    private static DataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        java.nio.file.Path dbPath = java.nio.file.Files.createTempFile("qoc", ".db");
        java.nio.file.Files.deleteIfExists(dbPath);
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + dbPath);
        dataSource = source;
        de.augmentia.oc.engine.adapter.sqlite.Sqlite.enableWal(dataSource);
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-sqlite").load().migrate();
    }

    @Test
    void roundTrip() throws Exception {
        SqlPendingApprovalStore store = new SqlPendingApprovalStore(dataSource);
        String id = "pa-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();
        store.persist(id, "s1", "shell", "ctx", now, now.plusSeconds(60), "PENDING", "hash", "email");
        assertThat(store.pending("s1")).hasSize(1);
        assertThat(store.sessionIdForToken("hash")).isEqualTo("s1");
        assertThat(store.requestIdForToken("hash")).isEqualTo(id);
        assertThat(store.decision(id)).isNull();
        assertThat(store.updateDecision(id, "ONCE", "ok")).isTrue();
        assertThat(store.decision(id)).isEqualTo("ONCE");
        assertThat(store.feedback(id)).isEqualTo("ok");
        store.markState(id, "DECIDED");
        assertThat(store.recoverOrphans()).isGreaterThanOrEqualTo(0);
        assertThat(store.deleteDecidedBefore(now.plusSeconds(120))).isGreaterThanOrEqualTo(1);
        assertThat(store.pending("s1")).isEmpty();
    }

    @Test
    void persistBasic() throws Exception {
        SqlPendingApprovalStore store = new SqlPendingApprovalStore(dataSource);
        String id = "pab-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();
        store.persistBasic(id, "s2", "shell", "ctx", now, now.plusSeconds(60));
        assertThat(store.pending("s2")).hasSize(1);
    }
}
