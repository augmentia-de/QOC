package de.augmentia.qoc.workflowrunner.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.augmentia.qoc.workflowrunner.runner.WorkflowContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Checkpoint store for crash-recovery without Temporal.
 * Saves/restores WorkflowContext after each node execution.
 */
public class CheckpointStore {

    private final Path checkpointDir;
    private final ObjectMapper mapper;

    public CheckpointStore(Path storageDir) {
        this.checkpointDir = storageDir.resolve("checkpoints");
        this.mapper = new ObjectMapper();
        try {
            Files.createDirectories(checkpointDir);
        } catch (IOException e) {
            throw new RuntimeException("Cannot create checkpoint dir: " + checkpointDir, e);
        }
    }

    /**
     * Saves a checkpoint for the given run.
     */
    public void save(String runId, String currentNodeId, WorkflowContext ctx) {
        try {
            Map<String, Object> data = new java.util.HashMap<>();
            data.put("runId", runId);
            data.put("currentNodeId", currentNodeId);
            data.put("nodeOutputs", ctx.getNodeOutputs());
            data.put("workflowState", ctx.getWorkflowState());
            Path file = checkpointDir.resolve(runId + ".json");
            mapper.writeValue(file.toFile(), data);
        } catch (IOException e) {
            throw new RuntimeException("Cannot save checkpoint: " + runId, e);
        }
    }

    /**
     * Loads a checkpoint for the given run.
     * Returns null if no checkpoint exists.
     */
    @SuppressWarnings("unchecked")
    public WorkflowContext load(String runId) {
        Path file = checkpointDir.resolve(runId + ".json");
        if (!Files.exists(file)) return null;
        try {
            Map<String, Object> data = mapper.readValue(file.toFile(), Map.class);
            WorkflowContext ctx = new WorkflowContext();
            String nodeId = (String) data.get("currentNodeId");
            ctx.setCurrentNodeId(nodeId);
            Map<String, Object> outputs = (Map<String, Object>) data.get("nodeOutputs");
            if (outputs != null) {
                for (Map.Entry<String, Object> e : outputs.entrySet()) {
                    ctx.setNodeOutput(e.getKey(), e.getValue());
                }
            }
            Map<String, Object> state = (Map<String, Object>) data.get("workflowState");
            if (state != null) {
                for (Map.Entry<String, Object> e : state.entrySet()) {
                    ctx.setState(e.getKey(), e.getValue());
                }
            }
            return ctx;
        } catch (IOException e) {
            throw new RuntimeException("Cannot load checkpoint: " + runId, e);
        }
    }

    /**
     * Deletes a checkpoint for the given run.
     */
    public void delete(String runId) {
        Path file = checkpointDir.resolve(runId + ".json");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Ignore
        }
    }

    /**
     * Checks if a checkpoint exists for the given run.
     */
    public boolean exists(String runId) {
        return Files.exists(checkpointDir.resolve(runId + ".json"));
    }
}