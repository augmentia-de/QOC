package de.augmentia.oc.engine.http;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;
import de.augmentia.qoc.workflowrunner.store.AgentStoreFactory;
import de.augmentia.qoc.workflowrunner.store.AgentStorePort;
import de.augmentia.qoc.workflowrunner.store.WorkflowDefinitionStore;
import de.augmentia.qoc.workflowrunner.store.WorkflowStoreFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.util.List;

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

    @Produces
    @ApplicationScoped
    public AgentStorePort agentStore(
            @ConfigProperty(name = "engine.workflow.storage", defaultValue = "auto") String storageKind,
            @ConfigProperty(name = "engine.workflow.storage-dir", defaultValue = "./wf-data") String dir,
            @ConfigProperty(name = "engine.storage", defaultValue = "postgres") String engineStorage,
            @ConfigProperty(name = "engine.db.kind", defaultValue = "postgresql") String databaseKind) {
        String resolvedKind = AgentStoreFactory.resolve(storageKind, engineStorage, databaseKind);
        DataSource resolved = ("sqlite".equals(resolvedKind) || "postgres".equals(resolvedKind))
                ? (dataSource.isResolvable() ? dataSource.get() : null)
                : null;
        AgentStorePort store = AgentStoreFactory.create(
                resolvedKind, engineStorage, databaseKind, dir, resolved);
        if (store.get("build") == null) {
            java.util.List<String> empty = java.util.List.of();
            java.util.List<String> star = java.util.List.of("*");
            Agent a = new Agent("build", "Build Agent", "1.0.0", "core",
                    "", 0.3, 4096, 1.0,
                    empty, empty, empty, empty, false, true,
                    "You are a helpful build agent. Reply concisely.", null, "", star,
                    "all", empty, 10, 1.0, false);
            store.save("build", a);
        }
        LOG.infof("Agent store (%s): %s", store.getClass().getSimpleName(), store.list().size());
        return store;
    }
}