package de.augmentia.qoc.workflowrunner.runner;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Central workflow context for getNextStep-based execution.
 *
 * <p>Holds structured node outputs, global state variables and the
 * current position in the React-Flow graph. Contains <b>no</b> chat history —
 * which is managed internally by QocEngine.runPrompt() via ConversationStore.</p>
 *
 * <p>The context is deliberately only a runtime object. Its components are
 * persisted as workflow events in the session journal and restored from there on resume
 * restored.</p>
 */
public class WorkflowContext {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowContext.class);


    /** Structured outputs per node ID (for data-piping and conditions). */
    private final Map<String, Object> nodeOutputs = new HashMap<>();

    /** Globale State-Variablen (z. B. lastStatus, lastResult, forkResults). */
    private final Map<String, Object> workflowState = new HashMap<>();

    /** Externally stored artifacts; prompts receive only references. */
    private final Map<String, ArtifactRef> artifacts = new HashMap<>();

    /** References to include in the next context. */
    private final List<String> contextInclude = new ArrayList<>();

    /** References to exclude from the next context. */
    private final List<String> contextExclude = new ArrayList<>();

    /** Strukturiertes Ergebnis des letzten Agenten-Laufs. */
    private Map<String, Object> lastStructuredResult = new HashMap<>();

    /** Aktuelle Position im React-Flow-Graphen. */
    private String currentNodeId;

    /** Ausfuehrungszaehler pro Node fuer den Circuit-Breaker. */
    private final Map<String, Integer> nodeExecutionCounts = new HashMap<>();

    /** Original workflow input; contains phase information. */
    private String initialPrompt;

    public String getInitialPrompt() {
        return initialPrompt;
    }

    public void setInitialPrompt(String initialPrompt) {
        this.initialPrompt = initialPrompt;
    }

    public void setNodeOutput(String nodeId, Object output) {
        nodeOutputs.put(nodeId, WorkflowValues.canonical(output));
    }

    public Object getNodeOutput(String nodeId) {
        return nodeOutputs.get(nodeId);
    }

    public Map<String, Object> getNodeOutputs() {
        return nodeOutputs;
    }

    public void setState(String key, Object val) {
        workflowState.put(key, WorkflowValues.canonical(val));
    }

    public Object getState(String key) {
        return workflowState.get(key);
    }

    public Map<String, Object> getWorkflowState() {
        return workflowState;
    }

    public void addArtifact(ArtifactRef ref) {
        if (ref == null) return;
        artifacts.put(ref.id(), ref);
        syncArtifacts();
    }

    public Map<String, ArtifactRef> getArtifacts() {
        return Map.copyOf(artifacts);
    }

    public ArtifactRef getArtifact(String id) {
        return id == null ? null : artifacts.get(id);
    }

    public void includeContext(String ref) {
        if (ref == null || ref.isBlank() || contextInclude.contains(ref)) return;
        contextInclude.add(ref);
        syncContextSelection();
    }

    public void excludeContext(String ref) {
        if (ref == null || ref.isBlank() || contextExclude.contains(ref)) return;
        contextExclude.add(ref);
        syncContextSelection();
    }

    public List<String> getContextInclude() {
        return List.copyOf(contextInclude);
    }

    public List<String> getContextExclude() {
        return List.copyOf(contextExclude);
    }

    public void setLastStructuredResult(Map<String, Object> result) {
        lastStructuredResult = result == null ? new HashMap<>() : new HashMap<>(result);
        workflowState.put("_lastStructuredResult", WorkflowValues.canonical(lastStructuredResult));
    }

    public Map<String, Object> getLastStructuredResult() {
        return Map.copyOf(lastStructuredResult);
    }

    private void syncArtifacts() {
        workflowState.put("_artifacts", WorkflowValues.canonical(new HashMap<>(artifacts)));
    }

    private void syncContextSelection() {
        workflowState.put("_contextInclude", WorkflowValues.canonical(new ArrayList<>(contextInclude)));
        workflowState.put("_contextExclude", WorkflowValues.canonical(new ArrayList<>(contextExclude)));
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public void setCurrentNodeId(String id) {
        this.currentNodeId = id;
    }

    /** Counts a node execution and returns the new counter value. */
    public int recordNodeExecution(String nodeId) {
        if (nodeId == null || nodeId.isBlank() || "DONE".equalsIgnoreCase(nodeId)) return 0;
        int count = nodeExecutionCounts.merge(nodeId, 1, Integer::sum);
        workflowState.put("_nodeExecutionCounts", WorkflowValues.canonical(new HashMap<>(nodeExecutionCounts)));
        return count;
    }

    public int getNodeExecutionCount(String nodeId) {
        return nodeId == null ? 0 : nodeExecutionCounts.getOrDefault(nodeId, 0);
    }

    /** Restores a context from journal events. */
    public void restore(Map<String, Object> nodeOutputs, Map<String, Object> workflowState,
                        String currentNodeId) {
        if (nodeOutputs != null) nodeOutputs.forEach(this::setNodeOutput);
        if (workflowState != null) {
            workflowState.forEach(this::setState);
            restoreExtendedState(workflowState);
        }
        this.currentNodeId = currentNodeId;
    }

    private void restoreExtendedState(Map<String, Object> state) {
        Object artifacts = state.get("_artifacts");
        if (artifacts instanceof Map<?, ?> map) {
            this.artifacts.clear();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() instanceof ArtifactRef ref) {
                    this.artifacts.put(String.valueOf(e.getKey()), ref);
                }
            }
        }
        Object include = state.get("_contextInclude");
        if (include instanceof List<?> list) {
            this.contextInclude.clear();
            for (Object o : list) {
                if (o != null) this.contextInclude.add(String.valueOf(o));
            }
        }
        Object exclude = state.get("_contextExclude");
        if (exclude instanceof List<?> list) {
            this.contextExclude.clear();
            for (Object o : list) {
                if (o != null) this.contextExclude.add(String.valueOf(o));
            }
        }
        Object last = state.get("_lastStructuredResult");
        if (last instanceof Map<?, ?> map) {
            this.lastStructuredResult = new HashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                this.lastStructuredResult.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        Object counts = state.get("_nodeExecutionCounts");
        if (counts instanceof Map<?, ?> map) {
            this.nodeExecutionCounts.clear();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                try {
                    this.nodeExecutionCounts.put(String.valueOf(e.getKey()),
                            ((Number) e.getValue()).intValue());
                } catch (ClassCastException ignored) {
                }
            }
        }
    }

    /** Neuer Restore-Pfad inkl. Initial-Prompt. */
    public void restore(Map<String, Object> nodeOutputs, Map<String, Object> workflowState,
                        String currentNodeId, String initialPrompt) {
        restore(nodeOutputs, workflowState, currentNodeId);
        this.initialPrompt = initialPrompt;
    }
}
