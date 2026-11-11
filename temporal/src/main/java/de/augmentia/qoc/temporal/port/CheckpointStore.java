package de.augmentia.qoc.temporal.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Port: Persistent Checkpoints für Graph-State.
 * Implementierung: SQLite (wie AgentFlow) oder In-Memory.
 */
public interface CheckpointStore {
    void save(String graphId, String checkpointId, Map<String, Object> state);
    Optional<Map<String, Object>> load(String graphId, String checkpointId);
    List<String> listCheckpoints(String graphId);
}
