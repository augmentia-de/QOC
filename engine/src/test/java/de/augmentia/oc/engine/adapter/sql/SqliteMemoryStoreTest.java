package de.augmentia.oc.engine.adapter.sql;

import de.augmentia.oc.engine.adapter.sqlite.SqliteMemoryStore;
import de.augmentia.oc.engine.core.model.Fact;
import de.augmentia.oc.engine.core.model.MemoryQuery;
import de.augmentia.oc.engine.core.port.store.MemoryStore;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteMemoryStoreTest {

    private static DataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        // keep
        java.nio.file.Path dbPath = java.nio.file.Files.createTempFile("qoc", ".db");
        java.nio.file.Files.deleteIfExists(dbPath);
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + dbPath);
        dataSource = source;
        de.augmentia.oc.engine.adapter.sqlite.Sqlite.enableWal(dataSource);
        org.flywaydb.core.Flyway.configure().dataSource(dataSource).locations("classpath:db/migration-sqlite").load().migrate();
    }

    @BeforeEach
    void cleanup() throws Exception {
        try (var c = dataSource.getConnection(); var s = c.createStatement()) {
            s.execute("delete from memory_fact");
        }
    }

    @Test
    void upsertAndQuery() {
        MemoryStore store = new SqliteMemoryStore(dataSource);
        Fact f = new Fact("f1", "Quarkus", "version", "3.40", Instant.now(), null, null, List.of(), 1.0, 0);
        store.upsert(f);
        List<Fact> results = store.query(new MemoryQuery("Quarkus", null, null, null, 10));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).factId()).isEqualTo("f1");
        assertThat(results.get(0).value()).isEqualTo("3.40");
    }

    @Test
    void upsertIdempotent() {
        MemoryStore store = new SqliteMemoryStore(dataSource);
        Fact f = new Fact("f2", "Java", "version", "21", Instant.now(), null, null, List.of(), 1.0, 0);
        store.upsert(f);
        store.upsert(f);
        List<Fact> results = store.query(new MemoryQuery("Java", "version", null, null, 10));
        assertThat(results).hasSize(1);
    }

    @Test
    void asOfReturnsHistorical() {
        MemoryStore store = new SqliteMemoryStore(dataSource);
        Instant past = Instant.now().minusSeconds(100);
        Fact f = new Fact("f3", "Quarkus", "version", "3.39", past, past, null, List.of(), 1.0, 0);
        store.upsert(f);
        var result = store.asOf("Quarkus", "version", past.plusSeconds(10));
        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualTo("3.39");
    }

    @Test
    void checkAnswerable() {
        MemoryStore store = new SqliteMemoryStore(dataSource);
        store.upsert(new Fact("f4", "Quarkus", "version", "3.40", Instant.now(), null, null, List.of(), 1.0, 0));
        var r = store.checkAnswerable("What is the Quarkus version?");
        assertThat(r.answerable()).isTrue();
    }

    @Test
    void diffShowsAdded() {
        MemoryStore store = new SqliteMemoryStore(dataSource);
        Instant before = Instant.now().minusSeconds(10);
        store.upsert(new Fact("f5", "Java", "version", "21", Instant.now(), null, null, List.of(), 1.0, 0));
        var diff = store.diff(before);
        assertThat(diff.added()).isNotEmpty();
    }

    @Test
    void retireFact() {
        MemoryStore store = new SqliteMemoryStore(dataSource);
        Fact f = new Fact("f6", "Quarkus", "version", "3.38", Instant.now(), null, null, List.of(), 1.0, 0);
        store.upsert(f);
        store.retire("f6", "outdated", "f7");
        var result = store.asOf("Quarkus", "version", Instant.now().plusSeconds(10));
        assertThat(result).isEmpty();
    }
}