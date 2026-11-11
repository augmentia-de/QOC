package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import java.util.List;

/**
 * Persistence port for workflow <em>specifications</em>.
 *
 * <p>The store only owns definitions and their version snapshots. Execution
 * state (current node, node outputs, attempts, …) is deliberately not part of
 * this port: it lives in the engine's session journal so restart/retry works
 * for every backend without duplicating state.</p>
 */
public interface WorkflowDefinitionStore {

    /** Returns the current definition or {@code null}. */
    WorkflowDefinition get(String id);

    /** Deletes a workflow and all of its versions. */
    boolean delete(String id);

    /** Lists all workflow IDs. */
    List<String> list();

    /**
     * Saves a new current definition. If the content changed, the previous
     * current definition is kept as a version snapshot.
     */
    WorkflowDefinition save(String id, WorkflowDefinition definition);

    /** Creates a new draft workflow with the default start/end graph. */
    WorkflowDefinition create(String name);

    /** Lists version IDs, newest first. */
    List<String> versions(String id);

    /** Loads one version snapshot or {@code null}. */
    WorkflowDefinition version(String id, String version);

    /**
     * Writes an immutable snapshot without touching the current definition.
     * Used for the expanded graph of a workflow run so a retry always replays
     * exactly the graph that was started.
     */
    void snapshot(String id, String version, WorkflowDefinition definition);

    /**
     * Finds a definition by content hash. Checks the current definition first
     * and then the version snapshots.
     */
    WorkflowDefinition findByHash(String id, String contentHash);
}
