package de.augmentia.oc.engine.core.contract.provider;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolName;

public sealed interface ModelContent {
    record Text(String text) implements ModelContent {
        public Text {
            if (text == null) text = "";
        }
    }

    record Media(Modality modality, byte[] data, Option<String> name) implements ModelContent {
        public Media {
            if (modality == null) throw new IllegalArgumentException("modality must not be null");
            data = data == null ? new byte[0] : data.clone();
            if (name == null) name = Option.none();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Media media
                    && modality == media.modality
                    && java.util.Arrays.equals(data, media.data)
                    && name.equals(media.name);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(modality, java.util.Arrays.hashCode(data), name);
        }
    }

    record ToolCall(CallId callId, ToolName name, JsonValue input) implements ModelContent {
        public ToolCall {
            if (callId == null || name == null) throw new IllegalArgumentException("callId and name must not be null");
            if (input == null) input = JsonValue.ofNull();
        }
    }
}