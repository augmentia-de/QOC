package de.augmentia.oc.engine.runtime.tool.meta;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.human.QuestionSource;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code question}: asks the user structured questions and blocks until the
 * answer arrives. The transport (web UI, CLI) is a {@link QuestionSource}.
 */
public final class QuestionTool {

    private final QuestionSource source;

    public QuestionTool(QuestionSource source) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        this.source = source;
    }

    public static ToolDefinition definition(QuestionTool tool) {
        if (tool == null) throw new IllegalArgumentException("tool must not be null");
        Schema option = Schema.obj(Map.of(
                "label", Schema.str(),
                "description", Schema.opt(Schema.str())));
        Schema question = Schema.obj(Map.of(
                "header", Schema.opt(Schema.str()),
                "question", Schema.str(),
                "options", Schema.opt(Schema.arr(option)),
                "multiple", Schema.opt(Schema.bool())));
        Schema input = Schema.obj(Map.of("questions", Schema.arr(question)));
        return new ToolDefinition(new ToolName("question"),
                "Ask the user questions when a decision or missing information blocks progress",
                input, Option.none(),
                (value, context) -> tool.execute(context.sessionId(), value),
                new ToolOptions(Option.none(), Option.of("question"), true, false));
    }

    private ToolOutcome execute(de.augmentia.oc.engine.core.model.SessionId session, JsonValue value) {
        try {
            List<QuestionSource.Question> questions = questions(value);
            if (questions.isEmpty()) return ReadTool.failure("question", "questions must not be empty");
            QuestionSource.Answer answer = source.ask(session, questions);
            if (answer.selected().isEmpty() && answer.text().isBlank()) {
                return ReadTool.failure("question", "question was not answered");
            }
            StringBuilder text = new StringBuilder();
            if (!answer.selected().isEmpty()) {
                text.append("Selected: ").append(String.join(", ", answer.selected())).append('\n');
            }
            if (!answer.text().isBlank()) {
                text.append("Answer: ").append(answer.text());
            }
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(text.toString().strip())),
                    Map.of("selected", answer.selected().size())));
        } catch (IllegalArgumentException e) {
            return ReadTool.failure("question", e.getMessage());
        }
    }

    private static List<QuestionSource.Question> questions(JsonValue value) {
        if (!(value instanceof JsonValue.Obj object)
                || !(object.values().get("questions") instanceof JsonValue.Arr array)) {
            throw new IllegalArgumentException("questions must be an array");
        }
        List<QuestionSource.Question> questions = new ArrayList<>();
        for (JsonValue element : array.values()) {
            if (!(element instanceof JsonValue.Obj item)) continue;
            String question = string(item, "question");
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question text must not be blank");
            }
            questions.add(new QuestionSource.Question(
                    orEmpty(string(item, "header")), question, options(item), bool(item, "multiple")));
        }
        return List.copyOf(questions);
    }

    private static List<QuestionSource.Option> options(JsonValue.Obj question) {
        if (!(question.values().get("options") instanceof JsonValue.Arr array)) return List.of();
        List<QuestionSource.Option> options = new ArrayList<>();
        for (JsonValue element : array.values()) {
            if (!(element instanceof JsonValue.Obj item)) continue;
            String label = string(item, "label");
            if (label == null || label.isBlank()) continue;
            options.add(new QuestionSource.Option(label, orEmpty(string(item, "description"))));
        }
        return List.copyOf(options);
    }

    private static String string(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Str str ? str.value() : null;
    }

    private static boolean bool(JsonValue.Obj object, String key) {
        return object.values().get(key) instanceof JsonValue.Bool bool && bool.value();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
