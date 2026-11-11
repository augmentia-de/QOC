package de.augmentia.oc.engine.http;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;
import de.augmentia.qoc.workflowrunner.store.AgentStore;
import de.augmentia.qoc.workflowrunner.store.WorkflowDefinitionStore;
import de.augmentia.qoc.workflowrunner.store.WorkflowStoreFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import javax.sql.DataSource;

import java.nio.file.Path;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class WorkflowBeans {

    private static final Logger LOG = Logger.getLogger(WorkflowBeans.class);

    @Inject
    Instance<DataSource> dataSource;

    @Produces
    @ApplicationScoped
    public WorkflowDefinitionStore workflowStore(
            @ConfigProperty(name = "engine.workflow.storage", defaultValue = "auto") String storageKind,
            @ConfigProperty(name = "engine.workflow.storage-dir", defaultValue = "./wf-data") String dir,
            @ConfigProperty(name = "engine.storage", defaultValue = "postgres") String engineStorage,
            @ConfigProperty(name = "engine.db.kind", defaultValue = "postgresql") String databaseKind) {
        String resolvedKind = WorkflowStoreFactory.resolve(storageKind, engineStorage, databaseKind);
        DataSource resolved = ("sqlite".equals(resolvedKind) || "postgres".equals(resolvedKind))
                ? (dataSource.isResolvable() ? dataSource.get() : null)
                : null;
        WorkflowDefinitionStore store = WorkflowStoreFactory.create(
                resolvedKind, engineStorage, databaseKind, dir, resolved);
        LOG.infof("Workflow specification storage: %s", store.getClass().getSimpleName());
        return store;
    }

    /**
     * Global agent store (Option B). Agents live next to the workflow
     * definitions and are referenced by id from workflow nodes.
     */
    @Produces
    @ApplicationScoped
    public AgentStore agentStore(
            @ConfigProperty(name = "engine.workflow.storage-dir", defaultValue = "./wf-data") String dir) {
        AgentStore store = new AgentStore(Path.of(dir));
        // Built-in default so the starter graph (agentRef="build") is valid.
        // Users may override or delete it like any other stored agent.
        if (store.get("build") == null) {
            store.save("build", new Agent("build", "Build Agent", "1.0.0", "core",
                    // Empty model = follow the engine's configured model.
                    "", 0.3, 4096, 1.0,
                    java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    java.util.List.of(), false, true,
                    "You are a helpful build agent. Reply concisely.", null));
        }
        LOG.infof("Agent store: %s", store.list().size());
        return store;
    }
}
