package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;

import java.util.List;

/**
 * Persistence port for agent definitions (SSOT).
 *
 * <p>Agents are stored as JSON (Jackson-serialized {@link Agent}) and may be
 * backed by files, SQLite, PostgreSQL or in-memory. The canonical on-disk
 * format for OpenCode compatibility is Markdown with YAML frontmatter; the
 * JSON representation is the DB/file cache.</p>
 */
public interface AgentStorePort {

    /** Returns the agent or {@code null}. */
    Agent get(String id);

    /** Deletes an agent. Returns true if it existed. */
    boolean delete(String id);

    /** Lists all agent IDs. */
    List<String> list();

    /** Lists all agents. */
    List<Agent> listAll();

    /** Lists agents visible to the given user (null = all for admin). */
    List<Agent> listAll(String userId, boolean isAdmin);

    /** Saves an agent under the given id. */
    Agent save(String id, Agent agent);

    /** Creates a new default agent. */
    Agent create(String name);
}