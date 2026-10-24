package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.AgentId;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.port.human.QuestionSource;
import de.augmentia.oc.engine.runtime.tool.meta.QuestionTool;
import de.augmentia.oc.engine.runtime.tool.meta.SessionTools;
import de.augmentia.oc.engine.runtime.tool.meta.TodoTool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetaToolsTest {

    @Test
    void todoWriteReplacesTheList() {
        TodoTool todo = new TodoTool();
        SessionId session = SessionId.create();

        ToolOutcome first = execute(TodoTool.definition(todo), session, Map.of("todos", JsonValue.of(List.of(
                JsonValue.of(Map.of("content", JsonValue.of("one"), "status", JsonValue.of("pending"))),
                JsonValue.of(Map.of("content", JsonValue.of("two"), "status", JsonValue.of("completed")))))));

        assertThat(first).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(text(first)).contains("[pending] one").contains("[completed] two");
        assertThat(todo.todos(session)).hasSize(2);

        ToolOutcome invalid = execute(TodoTool.definition(todo), session, Map.of("todos", JsonValue.of(List.of(
                JsonValue.of(Map.of("content", JsonValue.of("x"), "status", JsonValue.of("done")))))));
        assertThat(invalid).isInstanceOf(ToolOutcome.Failed.class);
    }

    @Test
    void sessionRenameMoveAndModelsUseTheControl() {
        List<String> calls = new ArrayList<>();
        SessionTools.SessionControl control = new SessionTools.SessionControl() {
            @Override
            public String rename(SessionId session, String title) {
                calls.add("rename:" + title);
                return title;
            }

            @Override
            public String move(SessionId session, String directory) {
                calls.add("move:" + directory);
                return directory;
            }

            @Override
            public List<SessionTools.ModelSummary> models() {
                return List.of(new SessionTools.ModelSummary("deepseek-v4.1-flash", "DeepSeek", "opencode"));
            }
        };
        SessionId session = SessionId.create();

        assertThat(execute(SessionTools.rename(control), session,
                Map.of("title", JsonValue.of("New title")))).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(execute(SessionTools.move(control), session,
                Map.of("directory", JsonValue.of("/tmp/work")))).isInstanceOf(ToolOutcome.Ok.class);
        ToolOutcome models = execute(SessionTools.models(control), session,
                Map.of("query", JsonValue.of("deepseek")));
        assertThat(text(models)).contains("opencode/deepseek-v4.1-flash");

        assertThat(calls).containsExactly("rename:New title", "move:/tmp/work");
    }

    @Test
    void questionToolReturnsTheAnswer() {
        QuestionSource source = (session, questions) -> {
            assertThat(questions).hasSize(1);
            assertThat(questions.getFirst().options()).extracting(QuestionSource.Option::label)
                    .containsExactly("Yes", "No");
            return new QuestionSource.Answer(List.of("Yes"), "because");
        };
        ToolOutcome outcome = execute(QuestionTool.definition(new QuestionTool(source)), SessionId.create(),
                Map.of("questions", JsonValue.of(List.of(JsonValue.of(Map.of(
                        "header", JsonValue.of("Choose"),
                        "question", JsonValue.of("Continue?"),
                        "options", JsonValue.of(List.of(
                                JsonValue.of(Map.of("label", JsonValue.of("Yes"))),
                                JsonValue.of(Map.of("label", JsonValue.of("No")))))))))));

        assertThat(text(outcome)).contains("Selected: Yes").contains("Answer: because");
    }

    private static ToolOutcome execute(ToolDefinition definition, SessionId session, Map<String, JsonValue> input) {
        ToolContext context = new ToolContext(session, AgentId.create(), MessageId.create(), CallId.create());
        return definition.execute().execute(JsonValue.of(input), context);
    }

    private static String text(ToolOutcome outcome) {
        return ((ToolContent.TextItem) ((ToolOutcome.Ok) outcome).result().content().getFirst()).text();
    }
}
