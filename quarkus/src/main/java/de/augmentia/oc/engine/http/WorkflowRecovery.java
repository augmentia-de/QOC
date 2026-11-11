package de.augmentia.oc.engine.http;

import de.augmentia.qoc.workflowrunner.runner.WorkflowExecutor;
import de.augmentia.qoc.workflowrunner.runner.WorkflowRunJournal;
import de.augmentia.qoc.workflowrunner.runner.WorkflowRunSnapshot;
import de.augmentia.qoc.workflowrunner.store.AgentStore;
import de.augmentia.qoc.workflowrunner.store.WorkflowDefinitionStore;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Resumes workflow runs that were interrupted by a process restart.
 *
 * <p>This is deliberately separate from {@code QocEngine.recover()}: the
 * engine skips workflow sessions, because resuming them requires the workflow
 * definition and the replayed {@code WorkflowContext}, not a generic
 * "server restarted" prompt.</p>
 */
@ApplicationScoped
public class WorkflowRecovery {

    private static final Logger LOG = Logger.getLogger(WorkflowRecovery.class);

    @Inject
    Instance<Engine> engine;

    @Inject
    DatabaseMigration migration;

    @Inject
    WorkflowDefinitionStore store;

    @Inject
    AgentStore agentStore;

    @ConfigProperty(name = "engine.workflow.recovery.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "engine.storage", defaultValue = "postgres")
    String engineStorage;

    @ConfigProperty(name = "engine.workflow.max-resume-attempts", defaultValue = "10")
    int maxResumeAttempts;

    void onStart(@Observes StartupEvent event) {
        if (!enabled || "memory".equalsIgnoreCase(engineStorage)) {
            return;
        }
        // Workflow tables are part of the same schema; make sure they exist
        // before scanning the journal for interrupted runs.
        try {
            migration.migrate();
        } catch (RuntimeException e) {
            LOG.warnf("Workflow recovery skipped: migration failed: %s", e.getMessage());
            return;
        }
        Thread.startVirtualThread(this::recover);
    }

    private void recover() {
        try {
            Engine engineInstance = engine.get();
            WorkflowExecutor executor = new WorkflowExecutor(
                    engineInstance.qocEngine(), store, engineInstance.mcp().manager(), agentStore);
            WorkflowRunJournal journal = new WorkflowRunJournal(engineInstance.qocEngine());
            for (WorkflowRunSnapshot run : journal.scan(200)) {
                if (!run.resumable() || run.active()) continue;
                if (!"RUNNING".equalsIgnoreCase(run.status())
                        && !"PAUSED".equalsIgnoreCase(run.status())) {
                    continue;
                }
                if (run.resumeCount() >= Math.max(1, maxResumeAttempts)) {
                    LOG.warnf("Workflow run %s reached the resume limit (%d); skipping",
                            run.runId(), maxResumeAttempts);
                    continue;
                }
                try {
                    LOG.infof("Auto-resuming workflow run %s at node %s",
                            run.runId(), run.currentNodeId());
                    executor.resume(run.runId());
                } catch (RuntimeException e) {
                    LOG.warnf("Workflow run %s could not be resumed: %s", run.runId(), e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            LOG.warnf("Workflow recovery scan failed: %s", e.getMessage());
        }
    }
}
