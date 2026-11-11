package de.augmentia.oc.engine.adapter.sql;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SqlHelpersTest {

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
    void dbHealth() throws Exception {
        assertThat(DbHealthSql.isHealthy(dataSource, 3)).isTrue();
        assertThat(DbHealthSql.isHealthy(null, 3)).isTrue();
    }

    @Test
    void dbConfig() throws Exception {
        DbConfigSql.configureSqlite(dataSource);
    }

    @Test
    void auditSql() throws Exception {
        assertThat(AuditSql.deleteToolOutput(dataSource, Instant.now())).isGreaterThanOrEqualTo(0);
        assertThat(AuditSql.deleteSnapshots(dataSource, Instant.now())).isGreaterThanOrEqualTo(0);
    }

    @Test
    void pendingApprovalSql() throws Exception {
        assertThat(PendingApprovalSql.pending(dataSource, "s1")).isEmpty();
        assertThat(PendingApprovalSql.recoverOrphans(dataSource)).isGreaterThanOrEqualTo(0);
        assertThat(PendingApprovalSql.deleteDecidedBefore(dataSource, Instant.now())).isGreaterThanOrEqualTo(0);
    }

    @Test
    void questionSql() throws Exception {
        assertThat(QuestionSql.pollAnswer(dataSource, "q")).isNull();
        assertThat(QuestionSql.findIdByToken(dataSource, "hash")).isNull();
        assertThat(QuestionSql.sessionIdForQuestion(dataSource, "q")).isNull();
    }
}
