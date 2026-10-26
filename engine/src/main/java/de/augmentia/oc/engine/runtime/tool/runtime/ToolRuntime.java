package de.augmentia.oc.engine.runtime.tool.runtime;

import de.augmentia.oc.engine.core.contract.permission.PermissionGate;
import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.contract.tool.Hooks;
import de.augmentia.oc.engine.core.contract.tool.ToolCall;
import de.augmentia.oc.engine.core.contract.tool.ToolContext;
import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolFailure;
import de.augmentia.oc.engine.core.contract.permission.PermissionDenied;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.contract.tool.ToolSnapshot;
import de.augmentia.oc.engine.core.eval.OutputBounding;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.port.store.ToolOutputStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

/**
 * The tool execution chain: pre-hook, snapshot resolution, input decode,
 * permission, execution, output decode, post-hook, normalization, bounding.
 */
public final class ToolRuntime {
    private static final Logger LOG = Logger.getLogger(ToolRuntime.class.getName());

    private final ToolSnapshot snapshot;
    private final ToolAuthorizer authorizer;
    private final PermissionGate permission;
    private final Hooks hooks;
    private final ToolOutputStore outputStore;

    public ToolRuntime(ToolSnapshot snapshot, ToolAuthorizer authorizer, PermissionGate permission,
                       Hooks hooks, ToolOutputStore outputStore) {
        if (snapshot == null || authorizer == null || permission == null || hooks == null
                || outputStore == null) {
            throw new IllegalArgumentException("runtime dependencies must not be null");
        }
        this.snapshot = snapshot;
        this.authorizer = authorizer;
        this.permission = permission;
        this.hooks = hooks;
        this.outputStore = outputStore;
    }

    public ToolOutcome execute(ToolCall rawCall, ToolContext context) {
        if (rawCall == null || context == null) {
            throw new IllegalArgumentException("call and context must not be null");
        }
        try {
            ToolCall call = hooks.beforeToolCall(context, rawCall);
            if (call == null) throw new ToolFailure("pre-hook returned no call");

            LOG.fine("tool execute: " + call.name());
            ToolDefinition definition = snapshot.require(call.name());
            JsonValue decodedInput = decode(call.input(), definition.inputSchema(), "input");

            PermissionRequest request = authorizer.request(context, definition, call);
            permission.assertAllowed(request);

            ToolOutcome outcome = definition.execute().execute(decodedInput, context);
            LOG.fine("tool outcome: " + call.name() + " -> " + outcome.getClass().getSimpleName());
            if (outcome instanceof ToolOutcome.Failed failed) {
                return failed;
            }
            ToolResult result = ((ToolOutcome.Ok) outcome).result();

            if (definition.outputSchema().isPresent()) {
                JsonValue output = result.output().orElse(JsonValue.ofNull());
                decode(output, definition.outputSchema().orElseThrow(), "output");
            }

            ToolResult hooked;
            try {
                hooked = hooks.afterToolCall(context, result);
            } catch (RuntimeException hookFailure) {
                // Post-hooks are observability/redaction aids: a broken hook
                // must not turn a successful tool call into a failure.
                LOG.warning("afterToolCall hook failed for " + call.name() + ": "
                        + hookFailure.getMessage());
                hooked = result;
            }
            ToolResult normalized = normalize(hooked);
            return new ToolOutcome.Ok(bound(normalized, call, context));
        } catch (PermissionDenied denied) {
            return new ToolOutcome.Failed(new SessionError.ToolExecutionError("permission_denied",
                    denied.getMessage()));
        } catch (ToolFailure failure) {
            return new ToolOutcome.Failed(new SessionError.ToolExecutionError("tool_failure",
                    failure.getMessage()));
        }
    }

    private JsonValue decode(JsonValue value, de.augmentia.oc.engine.core.json.Schema schema, String what) {
        Result<JsonValue> decoded = Json.decode(value, schema);
        if (decoded instanceof Result.Err<JsonValue> error) {
            throw new ToolFailure("invalid " + what + " at " + error.error().path()
                    + ": " + error.error().message());
        }
        return decoded.valueOrNull();
    }

    private static ToolResult normalize(ToolResult result) {
        if (!result.content().isEmpty()) return result;
        if (result.output().isEmpty()) return result;
        JsonValue output = result.output().orElseThrow();
        String text = output instanceof JsonValue.Str str ? str.value() : Json.encode(output);
        return new ToolResult(result.output(), List.of(new ToolContent.TextItem(text)), result.metadata());
    }

    private ToolResult bound(ToolResult result, ToolCall call, ToolContext context) {
        OutputBounding.Result bounded = OutputBounding.boundStoring(result,
                fullText -> outputStore.save(context.sessionId(), call.id().value(),
                        fullText.getBytes(StandardCharsets.UTF_8)));
        if (bounded instanceof OutputBounding.Result.Truncated truncated) {
            return truncated.result();
        }
        return result;
    }
}
