package de.augmentia.oc.engine.runtime.tool.meta;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Session-management tools from the reference namespace: rename, move and a
 * read-only model lookup. The actual work stays in the wiring layer.
 */
public final class SessionTools {

    /** Wiring callbacks; keep the tools independent from the HTTP/engine layer. */
    public interface SessionControl {
        String rename(SessionId session, String title);

        String move(SessionId session, String directory);

        List<ModelSummary> models();
    }

    public record ModelSummary(String id, String name, String provider) {
        public ModelSummary {
            if (id == null) id = "";
            if (name == null) name = id;
            if (provider == null) provider = "";
        }
    }

    private SessionTools() {}

    public static ToolDefinition rename(SessionControl control) {
        if (control == null) throw new IllegalArgumentException("control must not be null");
        Schema input = Schema.obj(Map.of(
                "sessionID", Schema.opt(Schema.str()),
                "title", Schema.str()));
        return new ToolDefinition(new ToolName("session_rename"),
                "Rename a session, or omit sessionID to rename the current session",
                input, Option.none(),
                (value, context) -> {
                    try {
                        String title = JsonArgs.requiredString(value, "title");
                        if (title.isBlank()) return failure("session_rename", "title must not be blank");
                        SessionId target = target(value, context.sessionId());
                        String renamed = control.rename(target, title);
                        return ok("Renamed session " + target.value() + " to " + renamed + ".");
                    } catch (IllegalArgumentException e) {
                        return failure("session_rename", e.getMessage());
                    }
                },
                new ToolOptions(Option.none(), Option.of("session"), true, false));
    }

    public static ToolDefinition move(SessionControl control) {
        if (control == null) throw new IllegalArgumentException("control must not be null");
        Schema input = Schema.obj(Map.of(
                "sessionID", Schema.opt(Schema.str()),
                "directory", Schema.str()));
        return new ToolDefinition(new ToolName("session_move"),
                "Move a session to another directory, or omit sessionID to move the current session",
                input, Option.none(),
                (value, context) -> {
                    try {
                        String directory = JsonArgs.requiredString(value, "directory");
                        if (directory.isBlank()) return failure("session_move", "directory must not be blank");
                        SessionId target = target(value, context.sessionId());
                        String moved = control.move(target, directory);
                        return ok("Moved session " + target.value() + " to " + moved + ".");
                    } catch (IllegalArgumentException e) {
                        return failure("session_move", e.getMessage());
                    }
                },
                new ToolOptions(Option.none(), Option.of("edit"), true, false));
    }

    public static ToolDefinition models(SessionControl control) {
        if (control == null) throw new IllegalArgumentException("control must not be null");
        Schema input = Schema.obj(Map.of(
                "query", Schema.opt(Schema.str()),
                "provider", Schema.opt(Schema.str()),
                "offset", Schema.opt(Schema.intg()),
                "limit", Schema.opt(Schema.intg())));
        return new ToolDefinition(new ToolName("models"),
                "Search the models available to this deployment",
                input, Option.none(),
                (value, context) -> {
                    String query = JsonArgs.string(value, "query");
                    String provider = JsonArgs.string(value, "provider");
                    int offset = Math.max(0, JsonArgs.intOr(value, "offset", 0));
                    int limit = Math.max(1, Math.min(100, JsonArgs.intOr(value, "limit", 20)));
                    List<ModelSummary> matches = control.models().stream()
                            .filter(model -> query == null || query.isBlank()
                                    || (model.id() + " " + model.name()).toLowerCase(Locale.ROOT)
                                    .contains(query.toLowerCase(Locale.ROOT)))
                            .filter(model -> provider == null || provider.isBlank()
                                    || model.provider().equalsIgnoreCase(provider))
                            .toList();
                    List<ModelSummary> page = matches.stream().skip(offset).limit(limit).toList();
                    StringBuilder text = new StringBuilder();
                    for (ModelSummary model : page) {
                        text.append("- ").append(model.provider()).append('/')
                                .append(model.id()).append(" (").append(model.name()).append(")\n");
                    }
                    if (page.isEmpty()) text.append("No matching models.");
                    return new ToolOutcome.Ok(new ToolResult(Option.none(),
                            List.of(new ToolContent.TextItem(text.toString().strip())),
                            Map.of("total", matches.size())));
                },
                new ToolOptions(Option.none(), Option.of("models"), true, false));
    }

    private static SessionId target(JsonValue value, SessionId current) {
        String id = JsonArgs.string(value, "sessionID");
        return id == null || id.isBlank() ? current : SessionId.parse(id);
    }

    private static ToolOutcome ok(String text) {
        return new ToolOutcome.Ok(new ToolResult(Option.none(),
                List.of(new ToolContent.TextItem(text)), Map.of()));
    }

    private static ToolOutcome failure(String tool, String message) {
        return ReadTool.failure(tool, message);
    }
}
