package de.augmentia.qoc.workflowrunner.runner;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Zentraler Workflow-Kontext für die getNextStep-basierte Ausführung.
 *
 * <p>Hält strukturierte Node-Outputs, globale State-Variablen und die
 * aktuelle Position im React-Flow-Graphen. Enthält <b>keine</b> Chat-Historie —
 * die wird von QocEngine.runPrompt() intern über ConversationStore verwaltet.</p>
 *
 * <p>Der Kontext ist bewusst nur ein Laufzeitobjekt. Seine Bestandteile werden
 * als Workflow-Events im Session-Journal persistiert und beim Resume daraus
 * wiederhergestellt.</p>
 */
public class WorkflowContext {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowContext.class);


    /** Strukturierte Outputs pro Node-ID (für Data-Piping und Conditions). */
    private final Map<String, Object> nodeOutputs = new HashMap<>();

    /** Globale State-Variablen (z. B. lastStatus, lastResult, forkResults). */
    private final Map<String, Object> workflowState = new HashMap<>();

    /** Aktuelle Position im React-Flow-Graphen. */
    private String currentNodeId;

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

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public void setCurrentNodeId(String id) {
        this.currentNodeId = id;
    }

    /** Restores a context from journal events. */
    public void restore(Map<String, Object> nodeOutputs, Map<String, Object> workflowState,
                        String currentNodeId) {
        if (nodeOutputs != null) nodeOutputs.forEach(this::setNodeOutput);
        if (workflowState != null) workflowState.forEach(this::setState);
        this.currentNodeId = currentNodeId;
    }
}
