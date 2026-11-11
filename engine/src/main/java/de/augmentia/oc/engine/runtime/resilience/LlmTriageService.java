package de.augmentia.oc.engine.runtime.resilience;

import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM-backed triage service. Calls the configured provider with the error
 * context and parses a structured JSON correction suggestion. Falls back to
 * "not retryable" on any failure so triage never crashes the turn.
 */
public final class LlmTriageService implements TriageService {

    private final ProviderAdapter provider;
    private final ModelRef model;

    public LlmTriageService(ProviderAdapter provider, ModelRef model) {
        this.provider = provider;
        this.model = model;
    }

    @Override
    public TriageResult analyze(TriageContext ctx) {
        if (provider == null || model == null) {
            return new TriageResult(false, "", "Triage unavailable: no provider");
        }
        try {
            String system = "You are a triage assistant for a coding agent. "
                    + "Analyze the error and return ONLY a JSON object with keys: "
                    + "retryable (boolean), correctedInput (string), explanation (string).";
            String user = "Tool: " + (ctx.toolName() == null ? "" : ctx.toolName()) + "\n"
                    + "Error: " + (ctx.errorMessage() == null ? "" : ctx.errorMessage()) + "\n"
                    + "Input: " + (ctx.toolInput() == null ? "" : ctx.toolInput()) + "\n"
                    + "State: " + (ctx.state() == null ? "" : ctx.state()) + "\n"
                    + "Attempt: " + ctx.attempt() + " of " + ctx.maxAttempts();
            ModelRequest request = new ModelRequest(
                    model, List.of(system), List.of(new ModelMessage.User(List.of(new ModelContent.Text(user)))),
                    List.of(), Option.none(),
                    de.augmentia.oc.engine.core.contract.provider.GenerationOptions.defaults(),
                    java.util.Map.of(), java.util.Map.of(), Option.none());
            StringBuilder text = new StringBuilder();
            for (ModelEvent event : provider.stream(request).toList()) {
                if (event instanceof ModelEvent.TextDelta delta) text.append(delta.text());
            }
            String raw = text.toString().trim();
            if (raw.isEmpty()) return new TriageResult(false, "", "Empty triage response");
            Result<JsonValue> parsed = Json.parse(raw);
            if (parsed instanceof Result.Err) return new TriageResult(false, "", "Malformed triage JSON");
            JsonValue value = parsed.valueOrNull();
            boolean retryable = value instanceof JsonValue.Obj o && o.values().get("retryable") instanceof JsonValue.Bool b && b.value();
            String corrected = value instanceof JsonValue.Obj o && o.values().get("correctedInput") instanceof JsonValue.Str s ? s.value() : "";
            String explanation = value instanceof JsonValue.Obj o && o.values().get("explanation") instanceof JsonValue.Str s ? s.value() : raw;
            return new TriageResult(retryable, corrected, explanation);
        } catch (Exception e) {
            return new TriageResult(false, "", "Triage failed: " + e.getMessage());
        }
    }
}
