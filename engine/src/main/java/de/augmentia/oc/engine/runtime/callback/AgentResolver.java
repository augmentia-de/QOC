package de.augmentia.oc.engine.runtime.callback;

import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.runtime.QocEngine;

/**
 * Resolves agent names and configurations.
 * Framework-neutral: Quarkus implements via AgentCatalogue, default returns null.
 */
public interface AgentResolver {
    String nameOf(AgentId agent);

    QocEngine.AgentConfig config(String name);

    static AgentResolver noop() {
        return new AgentResolver() {
            public String nameOf(AgentId agent) { return null; }
            public QocEngine.AgentConfig config(String name) { return null; }
        };
    }
}