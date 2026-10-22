package de.augmentia.oc.engine.core.contract.tool;

import de.augmentia.oc.engine.core.model.Rule;

import java.util.List;

@FunctionalInterface
public interface ToolGate {
    boolean visible(ToolDefinition definition, List<Rule> rules);
}