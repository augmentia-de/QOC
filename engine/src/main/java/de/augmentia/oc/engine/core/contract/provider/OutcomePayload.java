package de.augmentia.oc.engine.core.contract.provider;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Option;

public sealed interface OutcomePayload {
    record Text(String text) implements OutcomePayload {
        public Text {
            if (text == null) text = "";
        }
    }

    record Json(JsonValue value) implements OutcomePayload {
        public Json {
            if (value == null) value = JsonValue.ofNull();
        }
    }

    record Content(java.util.List<de.augmentia.oc.engine.core.model.ToolContent> items,
                   Option<java.util.Map<String, Object>> metadata) implements OutcomePayload {
        public Content {
            items = items == null ? java.util.List.of() : java.util.List.copyOf(items);
            if (metadata == null) metadata = Option.none();
        }
    }
}