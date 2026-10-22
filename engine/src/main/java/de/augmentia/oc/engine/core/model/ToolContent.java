package de.augmentia.oc.engine.core.model;

public sealed interface ToolContent {
    record TextItem(String text) implements ToolContent {
        public TextItem {
            if (text == null) throw new IllegalArgumentException("text must not be null");
        }
    }

    record FileItem(String uri, String mime, Option<String> name) implements ToolContent {
        public FileItem {
            if (uri == null || uri.isBlank()) throw new IllegalArgumentException("uri must not be blank");
            if (mime == null || mime.isBlank()) throw new IllegalArgumentException("mime must not be blank");
            if (name == null) name = Option.none();
        }
    }
}