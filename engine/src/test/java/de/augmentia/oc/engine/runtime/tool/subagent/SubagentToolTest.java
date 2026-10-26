package de.augmentia.oc.engine.runtime.tool.subagent;

import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
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

class SubagentToolTest {

    @Test
    void delegatesToRunner() {
        SubagentTool tool = new SubagentTool((parent, agent, prompt, depth) ->
                new SubagentRunner.SubagentResult("done: " + prompt, de.augmentia.oc.engine.core.model.TokenCount.zero()), request -> { });
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());

        ToolOutcome outcome = SubagentTool.definition(tool).execute().execute(
                JsonValue.of(Map.of("agent", JsonValue.of("helper"), "prompt", JsonValue.of("hi"))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(((ToolOutcome.Ok) outcome).result().content().getFirst().toString()).contains("done: hi");
    }

    @Test
    void launchesInBackground() {
        SubagentTool tool = new SubagentTool((parent, agent, prompt, depth) ->
                new SubagentRunner.SubagentResult("done", de.augmentia.oc.engine.core.model.TokenCount.zero()), request -> { });
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());

        ToolOutcome outcome = SubagentTool.definition(tool).execute().execute(
                JsonValue.of(Map.of("agent", JsonValue.of("helper"), "prompt", JsonValue.of("hi"),
                        "background", JsonValue.of(true))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(((ToolOutcome.Ok) outcome).result().metadata())
                .containsEntry("status", "running")
                .containsKey("taskId");
    }

    @Test
    void backgroundSuccessIsObservableFromTheParent() {
        SubagentTool tool = new SubagentTool((parent, agent, prompt, depth) ->
                new SubagentRunner.SubagentResult("done", TokenCount.zero()), request -> { });
        ToolContext context = context();
        String taskId = launch(tool, context);

        await(tool, context.sessionId(), taskId);
        BackgroundTasks.Snapshot task = tool.backgroundTasks()
                .get(context.sessionId(), taskId).orElseThrow();

        assertThat(task.state()).isEqualTo(BackgroundTasks.State.SUCCEEDED);
        assertThat(task.text()).isEqualTo("done");
        assertThat(task.error()).isEmpty();
        tool.close();
    }

    @Test
    void backgroundFailureIsRecordedInsteadOfSwallowed() {
        SubagentTool tool = new SubagentTool((parent, agent, prompt, depth) -> {
            throw new IllegalStateException("runner exploded");
        }, request -> { });
        ToolContext context = context();
        String taskId = launch(tool, context);

        await(tool, context.sessionId(), taskId);
        BackgroundTasks.Snapshot task = tool.backgroundTasks()
                .get(context.sessionId(), taskId).orElseThrow();

        assertThat(task.state()).isEqualTo(BackgroundTasks.State.FAILED);
        assertThat(task.error()).contains("runner exploded");
        tool.close();
    }

    @Test
    void backgroundTasksAreScopedToTheirParentAndCleanedUp() {
        SubagentTool tool = new SubagentTool((parent, agent, prompt, depth) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new SubagentRunner.SubagentResult("late", TokenCount.zero());
        }, request -> { });
        SessionId parent = SessionId.create();
        SessionId other = SessionId.create();
        String taskId = launch(tool, new ToolContext(parent, AgentId.create(),
                MessageId.create(), CallId.create()));

        assertThat(tool.backgroundTasks().get(other, taskId).isEmpty()).isTrue();
        tool.closeSession(parent);
        assertThat(tool.backgroundTasks().get(parent, taskId).isEmpty()).isTrue();
        tool.close();
    }

    @Test
    void taskToolListsAndWaitsForACompletedTask() {
        SubagentTool tool = new SubagentTool((parent, agent, prompt, depth) ->
                new SubagentRunner.SubagentResult("done", TokenCount.zero()), request -> { });
        ToolContext context = context();
        String taskId = launch(tool, context);
        await(tool, context.sessionId(), taskId);

        ToolOutcome wait = TaskTool.definition(tool).execute().execute(
                JsonValue.of(Map.of("action", JsonValue.of("wait"),
                        "id", JsonValue.of(taskId), "timeout", JsonValue.of(1_000L))), context);
        ToolOutcome list = TaskTool.definition(tool).execute().execute(
                JsonValue.of(Map.of("action", JsonValue.of("list"))), context);

        assertThat(wait).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(((ToolOutcome.Ok) wait).result().content().getFirst().toString())
                .contains("succeeded").contains("done");
        assertThat(((ToolOutcome.Ok) list).result().content().getFirst().toString())
                .contains(taskId);
        tool.close();
    }

    @Test
    void taskToolCancelsARunningTask() {
        SubagentTool tool = new SubagentTool((parent, agent, prompt, depth) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new SubagentRunner.SubagentResult("late", TokenCount.zero());
        }, request -> { });
        ToolContext context = context();
        String taskId = launch(tool, context);

        ToolOutcome cancel = TaskTool.definition(tool).execute().execute(
                JsonValue.of(Map.of("action", JsonValue.of("cancel"),
                        "id", JsonValue.of(taskId))), context);

        assertThat(cancel).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(tool.backgroundTasks().get(context.sessionId(), taskId)
                .orElseThrow().state()).isEqualTo(BackgroundTasks.State.CANCELLED);
        tool.close();
    }

    @Test
    void missingRunnerFails() {
        SubagentTool tool = new SubagentTool(null, request -> { });
        ToolContext context = new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());

        ToolOutcome outcome = SubagentTool.definition(tool).execute().execute(
                JsonValue.of(Map.of("agent", JsonValue.of("helper"), "prompt", JsonValue.of("hi"))), context);

        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
    }

    private static ToolContext context() {
        return new ToolContext(SessionId.create(), AgentId.create(), MessageId.create(), CallId.create());
    }

    private static String launch(SubagentTool tool, ToolContext context) {
        ToolOutcome outcome = SubagentTool.definition(tool).execute().execute(
                JsonValue.of(Map.of("agent", JsonValue.of("helper"), "prompt", JsonValue.of("hi"),
                        "background", JsonValue.of(true))), context);
        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        return String.valueOf(((ToolOutcome.Ok) outcome).result().metadata().get("taskId"));
    }

    private static void await(SubagentTool tool, SessionId parent, String taskId) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (tool.backgroundTasks().get(parent, taskId).orElseThrow().running()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
