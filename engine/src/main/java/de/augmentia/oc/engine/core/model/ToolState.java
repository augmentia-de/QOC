package de.augmentia.oc.engine.core.model;
import de.augmentia.oc.engine.core.json.*;

import java.util.List;
import java.util.Map;

public sealed interface ToolState {
    record Streaming(String input) implements ToolState {}

    record Running(JsonValue input, Map<String, Object> metadata) implements ToolState {
        public Running {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record Completed(JsonValue input, List<ToolContent> content, Map<String, Object> metadata) implements ToolState {
        public Completed {
            content = content == null ? List.of() : List.copyOf(content);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    record Failed(JsonValue input, SessionError error, Option<List<ToolContent>> content,
                  Map<String, Object> metadata) implements ToolState {
        public Failed {
            if (error == null) throw new IllegalArgumentException("error must not be null");
            if (content == null) content = Option.none();
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}