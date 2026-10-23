package de.augmentia.oc.engine.runtime.tool.subagent;

import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentToolTest {

    @Test
    void delegatesToRunner() {
        SubagentTool tool = new SubagentTool((parent, agent, prompt, depth) -> "done: " + prompt, request -> { });
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());

        ToolOutcome outcome = SubagentTool.definition(tool).execute().execute(
                JsonValue.of(Map.of("agent", JsonValue.of("helper"), "prompt", JsonValue.of("hi"))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(((ToolOutcome.Ok) outcome).result().content().getFirst().toString()).contains("done: hi");
    }

    @Test
    void launchesInBackground() {
        SubagentTool tool = new SubagentTool((parent, agent, prompt, depth) -> "done", request -> { });
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());

        ToolOutcome outcome = SubagentTool.definition(tool).execute().execute(
                JsonValue.of(Map.of("agent", JsonValue.of("helper"), "prompt", JsonValue.of("hi"),
                        "background", JsonValue.of(true))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(((ToolOutcome.Ok) outcome).result().metadata()).containsEntry("status", "running");
    }

    @Test
    void missingRunnerFails() {
        SubagentTool tool = new SubagentTool(null, request -> { });
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());

        ToolOutcome outcome = SubagentTool.definition(tool).execute().execute(
                JsonValue.of(Map.of("agent", JsonValue.of("helper"), "prompt", JsonValue.of("hi"))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
    }
}
