package de.augmentia.oc.engine.runtime.tool.runtime;

import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.ToolContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JSON codec for {@link ToolOutcome}, used by the tool result cache. */
public final class ToolOutcomeJson {

    private ToolOutcomeJson() {}

    public static JsonValue encode(ToolOutcome outcome) {
        if (outcome instanceof ToolOutcome.Ok ok) {
            List<JsonValue> texts = new ArrayList<>();
            for (ToolContent item : ok.result().content()) {
                if (item instanceof ToolContent.TextItem t) texts.add(JsonValue.of(t.text()));
            }
            return JsonValue.of(Map.of(
                    "type", JsonValue.of("ok"),
                    "texts", JsonValue.of(texts)));
        }
        if (outcome instanceof ToolOutcome.Failed failed) {
            String msg = failed.error() instanceof SessionError.ToolExecutionError te
                    ? te.message() : String.valueOf(failed.error());
            return JsonValue.of(Map.of(
                    "type", JsonValue.of("failed"),
                    "message", JsonValue.of(msg == null ? "cached failure" : msg)));
        }
        if (outcome instanceof ToolOutcome.Paused paused) {
            return JsonValue.of(Map.of(
                    "type", JsonValue.of("paused"),
                    "approvalId", JsonValue.of(paused.approvalId()),
                    "approvalUrl", JsonValue.of(paused.approvalUrl())));
        }
        if (outcome instanceof ToolOutcome.Empty empty) {
            return JsonValue.of(Map.of(
                    "type", JsonValue.of("empty"),
                    "message", JsonValue.of(empty.message() == null ? "" : empty.message())));
        }
        if (outcome instanceof ToolOutcome.InvalidArgument invalid) {
            return JsonValue.of(Map.of(
                    "type", JsonValue.of("invalid_argument"),
                    "message", JsonValue.of(invalid.message() == null ? "" : invalid.message())));
        }
        if (outcome instanceof ToolOutcome.Unavailable unavailable) {
            return JsonValue.of(Map.of(
                    "type", JsonValue.of("unavailable"),
                    "message", JsonValue.of(unavailable.message() == null ? "" : unavailable.message())));
        }
        return JsonValue.of(Map.of("type", JsonValue.of("unknown")));
    }

    public static ToolOutcome decode(JsonValue value) {
        if (!(value instanceof JsonValue.Obj obj)) return null;
        String type = obj.values().get("type") instanceof JsonValue.Str s ? s.value() : "";
        switch (type) {
            case "ok" -> {
                List<ToolContent> content = new ArrayList<>();
                if (obj.values().get("texts") instanceof JsonValue.Arr arr) {
                    for (JsonValue item : arr.values()) {
                        if (item instanceof JsonValue.Str s) content.add(new ToolContent.TextItem(s.value()));
                    }
                }
                return new ToolOutcome.Ok(new ToolResult(Option.none(), content, Map.of()));
            }
            case "failed" -> {
                String msg = obj.values().get("message") instanceof JsonValue.Str s ? s.value() : "cached failure";
                return new ToolOutcome.Failed(new SessionError.ToolExecutionError("cached", msg));
            }
            case "paused" -> {
                String id = obj.values().get("approvalId") instanceof JsonValue.Str s ? s.value() : "";
                String url = obj.values().get("approvalUrl") instanceof JsonValue.Str s ? s.value() : "";
                return new ToolOutcome.Paused(id, url);
            }
            case "empty" -> {
                String msg = obj.values().get("message") instanceof JsonValue.Str s ? s.value() : "";
                return new ToolOutcome.Empty(msg);
            }
            case "invalid_argument" -> {
                String msg = obj.values().get("message") instanceof JsonValue.Str s ? s.value() : "";
                return new ToolOutcome.InvalidArgument(msg);
            }
            case "unavailable" -> {
                String msg = obj.values().get("message") instanceof JsonValue.Str s ? s.value() : "";
                return new ToolOutcome.Unavailable(msg);
            }
            default -> {
                return null;
            }
        }
    }
}