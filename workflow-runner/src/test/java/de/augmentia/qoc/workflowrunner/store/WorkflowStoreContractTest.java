package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowStoreContractTest {

    @TempDir
    Path tempDir;

    @Test
    void memoryStoreContract() {
        contract(new InMemoryWorkflowStore());
    }

    @Test
    void fileStoreContract() {
        contract(new WorkflowStore(tempDir.resolve("workflows").toString()));
    }

    @Test
    void sqliteStoreContract() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("workflows.db"));
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : schema().split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
        contract(new SqliteWorkflowStore(dataSource));
    }

    private void contract(WorkflowDefinitionStore store) {
        WorkflowDefinition created = store.create("Demo / Workflow");
        String id = created.id();
        assertEquals(id, created.id());
        assertNotNull(store.get(id));
        assertEquals(List.of(id), store.list());

        WorkflowDefinition first = definition(id, "First", "draft");
        store.save(id, first);
        WorkflowDefinition second = definition(id, "Second", "active");
        store.save(id, second);

        assertEquals("Second", store.get(id).name());
        assertEquals(2, store.versions(id).size());
        WorkflowDefinition previous = store.version(id, store.versions(id).get(0));
        assertEquals("First", previous.name());

        String currentHash = WorkflowJson.contentHash(second);
        String previousHash = WorkflowJson.contentHash(first);
        assertEquals(second, store.findByHash(id, currentHash));
        assertEquals(first, store.findByHash(id, previousHash));

        WorkflowDefinition expanded = definition(id, "Expanded", "active");
        store.snapshot(id, "run-1", expanded);
        assertEquals(expanded.name(), store.version(id, "run-1").name());
        assertEquals(expanded, store.findByHash(id, WorkflowJson.contentHash(expanded)));

        // Saving identical content must not create another version.
        int versionsBefore = store.versions(id).size();
        store.save(id, second);
        assertEquals(versionsBefore, store.versions(id).size());

        assertTrue(store.delete(id));
        assertNull(store.get(id));
        assertTrue(store.versions(id).isEmpty());
    }

    private static WorkflowDefinition definition(String id, String name, String status) {
        return new WorkflowDefinition(id, name, "1.0.0", status, List.of(), List.of(), List.of());
    }

    private static String schema() {
        return """
                create table workflow_definition (
                    id text primary key,
                    name text not null,
                    version text not null,
                    status text not null,
                    definition text not null,
                    content_hash text not null,
                    created_at text not null default (datetime('now')),
                    updated_at text not null default (datetime('now'))
                );
                create table workflow_version (
                    workflow_id text not null references workflow_definition(id) on delete cascade,
                    version text not null,
                    definition text not null,
                    content_hash text not null,
                    created_at text not null default (datetime('now')),
                    primary key (workflow_id, version)
                );
                """;
    }
}
