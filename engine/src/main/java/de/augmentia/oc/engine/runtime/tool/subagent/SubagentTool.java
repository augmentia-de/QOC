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

    public SubagentTool(SubagentRunner runner, PermissionGate permissionGate) {
        this.runner = runner;
        this.permissionGate = permissionGate;
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
                        return executeBackground(r, tool.permissionGate, value, context);
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

            String result = runner.run(context.sessionId(), agent, prompt, 0);
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(result)), Map.of("agent", agent)));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("subagent", e.getMessage());
        } catch (RuntimeException e) {
            return ReadTool.failure("subagent", e.getMessage());
        }
    }

    static ToolOutcome executeBackground(SubagentRunner runner, PermissionGate permissionGate, JsonValue value,
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

            Thread thread = new Thread(() -> {
                try {
                    runner.run(context.sessionId(), agent, prompt, 0);
                } catch (Exception ignored) {
                }
            }, "subagent-" + agent);
            thread.setDaemon(true);
            thread.start();

            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(
                            "Subagent launched in background. It will notify on completion.")),
                    Map.of("agent", agent, "status", "running")));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("subagent", e.getMessage());
        } catch (RuntimeException e) {
            return ReadTool.failure("subagent", e.getMessage());
        }
    }
}