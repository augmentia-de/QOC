package de.augmentia.qoc.workflowrunner.store;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition;

import java.util.List;
import java.util.Map;

/** Default workflow graphs shared by all storage adapters. */
public final class WorkflowDefaults {

    private WorkflowDefaults() {}

    public static WorkflowDefinition create(String name) {
        String id = idFor(name);
        String title = (name == null || name.isBlank()) ? id : name;
        WorkflowDefinition.Node start = new WorkflowDefinition.Node("start", "start",
                new WorkflowDefinition.Point(100, 200),
                new WorkflowDefinition.NodeData("Start", "", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null));
        WorkflowDefinition.Node end = new WorkflowDefinition.Node("end", "end",
                new WorkflowDefinition.Point(400, 200),
                new WorkflowDefinition.NodeData("Done", "", false, "human", 3, "",
                        List.of(), "all", null, List.of(), "", "", null, "", "", Map.of(), null));
        return new WorkflowDefinition(id, title, "1.0.0", "draft",
                List.of(start, end), List.of(), List.of());
    }

    /** Ensures the stored definition carries the ID used as its key. */
    public static WorkflowDefinition withId(String id, WorkflowDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("definition must not be null");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (id.equals(definition.id())) return definition;
        return new WorkflowDefinition(id, definition.name(), definition.version(), definition.status(),
                definition.nodes(), definition.edges(), definition.agents());
    }

    private static String idFor(String name) {
        if (name == null || name.isBlank()) return "workflow-" + System.currentTimeMillis();
        String id = name.toLowerCase().replaceAll("[^a-z0-9-]", "-");
        return id.isBlank() ? "workflow-" + System.currentTimeMillis() : id;
    }
}
