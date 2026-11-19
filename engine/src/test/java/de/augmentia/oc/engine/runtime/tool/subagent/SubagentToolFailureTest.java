package de.augmentia.oc.engine.runtime.tool.subagent;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.TokenCount;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentToolFailureTest {

    @Test
    void runnerFailureBecomesToolFailure() {
        SubagentRunner runner = (parent, agentName, prompt, depth, principal) -> {
            throw new IllegalStateException("child crashed");
        };

        ToolOutcome outcome = SubagentTool.execute(runner, null, null, request(), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().message()).contains("child crashed");
    }

    @Test
    void successfulRunnerBecomesToolResult() {
        SubagentRunner runner = (parent, agentName, prompt, depth, principal) ->
                new SubagentRunner.SubagentResult("done", TokenCount.zero(), null);

        ToolOutcome outcome = SubagentTool.execute(runner, null, null, request(), context());

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(((ToolOutcome.Ok) outcome).result().content())
                .extracting(content -> content.toString())
                .anySatisfy(text -> assertThat(text).contains("done"));
    }

    private static JsonValue request() {
        return JsonValue.of(Map.of(
                "agent", JsonValue.of("research"),
                "prompt", JsonValue.of("find something")));
    }

    private static ToolContext context() {
        return new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
    }
}
