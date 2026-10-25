package de.augmentia.oc.engine.runtime.tool.structured;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The synthetic strict function that carries a structured final answer.
 *
 * <p>The tool is added to the normal tool set of one prompt. Models that
 * support function calling can use it next to regular tools, which avoids the
 * provider incompatibility of {@code response_format} combined with tools.</p>
 */
public final class SubmitResult {

    public static final String TOOL_NAME = "submit_result";

    public static final String INSTRUCTION = """
            To finish, call the `submit_result` tool with the final result. Its arguments must \
            match the provided schema. Use the other tools first if the task requires it.""";

    private SubmitResult() {}

    public static ToolDefinition definition(Schema schema) {
        if (schema == null) throw new IllegalArgumentException("schema must not be null");
        ToolOptions options = new ToolOptions(Option.none(), Option.none(), false, false, true);
        return new ToolDefinition(new ToolName(TOOL_NAME),
                "Submit the final structured result. Call this exactly once when the task is complete.",
                schema, Option.none(),
                (input, context) -> new ToolOutcome.Ok(new ToolResult(Option.some(input),
                        List.of(new ToolContent.TextItem("Result submitted.")), Map.of())),
                options);
    }

    /**
     * Adds the strict result tool to a snapshot.
     *
     * @throws IllegalArgumentException if a tool with the reserved name is
     *         already present; the caller should ask the user to rename it
     */
    public static ToolSnapshot withTool(ToolSnapshot base, Schema schema) {
        if (base == null) throw new IllegalArgumentException("base snapshot must not be null");
        Map<ToolName, ToolDefinition> definitions = new LinkedHashMap<>(base.definitions());
        ToolName name = new ToolName(TOOL_NAME);
        if (definitions.containsKey(name)) {
            throw new IllegalArgumentException("tool name '" + TOOL_NAME
                    + "' is reserved for structured output");
        }
        definitions.put(name, definition(schema));
        return new ToolSnapshot(definitions);
    }
}
