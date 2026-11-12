package de.augmentia.oc.engine.http;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Registers workflow-runner model classes for reflection in native images. */
@RegisterForReflection(classNames = {
        "de.augmentia.qoc.workflowrunner.model.WorkflowDefinition",
        "de.augmentia.qoc.workflowrunner.model.WorkflowDefinition$Node",
        "de.augmentia.qoc.workflowrunner.model.WorkflowDefinition$Point",
        "de.augmentia.qoc.workflowrunner.model.WorkflowDefinition$NodeData",
        "de.augmentia.qoc.workflowrunner.model.WorkflowDefinition$Branch",
        "de.augmentia.qoc.workflowrunner.model.WorkflowDefinition$Edge",
        "de.augmentia.qoc.workflowrunner.model.WorkflowDefinition$Agent",
        "de.augmentia.qoc.workflowrunner.validation.GraphCompiler$ValidationResult",
        "de.augmentia.qoc.workflowrunner.schema.JavaRecordToJsonSchema",
        "de.augmentia.qoc.workflowrunner.schema.OpenApiToJsonSchema",
        "de.augmentia.qoc.workflowrunner.schema.JavaSourceToJsonSchema",
        "de.augmentia.qoc.workflowrunner.schema.YamlToJsonSchema",
        "com.github.javaparser.JavaParser",
        "com.github.javaparser.ast.CompilationUnit"
})
public class WorkflowReflectionConfig {
}

