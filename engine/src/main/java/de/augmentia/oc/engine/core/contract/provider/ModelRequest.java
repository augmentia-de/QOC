package de.augmentia.oc.engine.core.contract.provider;

import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;

import java.util.List;
import java.util.Map;

public record ModelRequest(ModelRef model, List<String> system, List<ModelMessage> messages,
                           List<ToolDefinition> tools, Option<Choice> toolChoice,
                           GenerationOptions generation, Map<String, Object> providerOptions,
                           Map<String, String> headers, Option<String> cacheKey) {
    public ModelRequest {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        system = system == null ? List.of() : List.copyOf(system);
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (toolChoice == null) toolChoice = Option.none();
        if (generation == null) generation = GenerationOptions.defaults();
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(providerOptions);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (cacheKey == null) cacheKey = Option.none();
    }
}