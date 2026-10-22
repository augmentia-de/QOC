package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.contract.provider.Choice;
import de.augmentia.oc.engine.core.contract.provider.GenerationOptions;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;

import java.util.List;
import java.util.Map;

/** A fully prepared model request, frozen for one step. */
public record Prepared(ModelRef model, List<String> system, List<ModelMessage> messages,
                       List<ToolDefinition> tools, Option<Choice> toolChoice,
                       GenerationOptions generation, Map<String, String> headers,
                       Option<String> cacheKey, Map<String, Object> providerOptions) {

    public Prepared {
        if (model == null) throw new IllegalArgumentException("model must not be null");
        system = system == null ? List.of() : List.copyOf(system);
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (toolChoice == null) toolChoice = Option.none();
        if (generation == null) generation = GenerationOptions.defaults();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (cacheKey == null) cacheKey = Option.none();
        providerOptions = providerOptions == null ? Map.of() : Map.copyOf(providerOptions);
    }

    public ModelRequest toRequest() {
        return new ModelRequest(model, system, messages, tools, toolChoice, generation,
                providerOptions, headers, cacheKey);
    }
}
