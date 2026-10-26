package de.augmentia.oc.engine.runtime.tool.subagent;

import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.util.List;
import java.util.Map;

/**
 * {@code subagent}: delegates a task to a specialized agent in a child session.
 * The subagent runs to completion and its final text response is returned as
 * tool output.
 */
public final class SubagentTool {

    public static final int DEFAULT_MAX_DEPTH = 2;

    private volatile SubagentRunner runner;
    private final PermissionGate permissionGate;
    private final BackgroundTasks backgroundTasks = new BackgroundTasks();

    public SubagentTool(SubagentRunner runner, PermissionGate permissionGate) {
        this.runner = runner;
        this.permissionGate = permissionGate;
    }

    /** Session-scoped registry for background tasks; used by the task tool and HTTP surface. */
    public BackgroundTasks backgroundTasks() {
        return backgroundTasks;
    }

    /** Cancels and forgets all background tasks of a session. */
    public void closeSession(de.augmentia.oc.engine.core.model.SessionId session) {
        backgroundTasks.closeSession(session);
    }

    public void close() {
        backgroundTasks.closeAll();
    }

    public void setRunner(SubagentRunner runner) {
        this.runner = runner;
    }

    public static ToolDefinition definition(SubagentTool tool) {
        if (tool == null) throw new IllegalArgumentException("tool must not be null");
        Schema input = Schema.obj(Map.of(
                "agent", Schema.str(),
                "prompt", Schema.str(),
                "description", Schema.opt(Schema.str()),
                "background", Schema.opt(Schema.bool())));
        return new ToolDefinition(new ToolName("subagent"), "Delegate a task to a specialized subagent",
                input, Option.none(),
                (value, context) -> {
                    SubagentRunner r = tool.runner;
                    if (r == null) return ReadTool.failure("subagent", "subagent runner not wired");
                    boolean background = JsonArgs.boolOr(value, "background", false);
                    if (background) {
                        return tool.executeBackground(r, value, context);
                    }
                    return execute(r, tool.permissionGate, value, context);
                },
                null);
    }

    static ToolOutcome execute(SubagentRunner runner, PermissionGate permissionGate, JsonValue value,
                               de.augmentia.oc.engine.core.contract.tool.ToolContext context) {
        try {
            String agent = JsonArgs.requiredString(value, "agent");
            String prompt = JsonArgs.requiredString(value, "prompt");

            // Permission check
            if (permissionGate != null) {
                PermissionRequest req = new PermissionRequest(
                        PermissionRequestId.create(),
                        context.sessionId(),
                        "subagent",
                        List.of(agent),
                        List.of(agent),
                        Map.of("tool", "subagent", "agent", agent),
                        null, null);
                permissionGate.assertAllowed(req);
            }

            SubagentRunner.SubagentResult result = runner.run(context.sessionId(), agent, prompt, 0);
            String text = result.text().isBlank() ? "Subagent completed with no text output." : result.text();
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(text)), Map.of("agent", agent, "tokens", result.usage().total())));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("subagent", e.getMessage());
        } catch (RuntimeException e) {
            return ReadTool.failure("subagent", e.getMessage());
        }
    }

    ToolOutcome executeBackground(SubagentRunner runner, JsonValue value,
                                  de.augmentia.oc.engine.core.contract.tool.ToolContext context) {
        try {
            String agent = JsonArgs.requiredString(value, "agent");
            String prompt = JsonArgs.requiredString(value, "prompt");

            if (permissionGate != null) {
                PermissionRequest req = new PermissionRequest(
                        PermissionRequestId.create(),
                        context.sessionId(),
                        "subagent",
                        List.of(agent),
                        List.of(agent),
                        Map.of("tool", "subagent", "agent", agent, "background", true),
                        null, null);
                permissionGate.assertAllowed(req);
            }

            String taskId = backgroundTasks.create(context.sessionId(), agent);
            Thread thread = Thread.ofVirtual().name("subagent-" + taskId).unstarted(() -> {
                try {
                    SubagentRunner.SubagentResult result = runner.run(context.sessionId(), agent, prompt, 0);
                    if (result == null) {
                        backgroundTasks.fail(taskId, new IllegalStateException("subagent returned no result"));
                    } else {
                        backgroundTasks.succeed(taskId, result);
                    }
                } catch (Throwable error) {
                    // A background failure must remain visible in the task registry.
                    backgroundTasks.fail(taskId, error);
                }
            });
            backgroundTasks.attach(taskId, thread);
            thread.start();

            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(
                            "Subagent launched in background. Task ID: " + taskId
                                    + ". Use task(action=status|wait|cancel, id=" + taskId + ") to inspect it.")),
                    Map.of("agent", agent, "status", "running", "taskId", taskId)));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("subagent", e.getMessage());
        } catch (RuntimeException e) {
            return ReadTool.failure("subagent", e.getMessage());
        }
    }
}