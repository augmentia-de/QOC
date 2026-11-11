package de.augmentia.oc.engine.runtime.tool.mcp;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.ToolContent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpContentMapperTest {

    private final McpContentMapper mapper = new McpContentMapper();

    @Test
    void emptyResultReturnsEmpty() {
        assertThat(mapper.map(null)).isEmpty();
        assertThat(mapper.map(JsonValue.of(Map.of()))).isEmpty();
    }

    @Test
    void mapsText() {
        JsonValue result = JsonValue.of(Map.of("content", JsonValue.of(List.of(
                JsonValue.of(Map.of("type", JsonValue.of("text"), "text", JsonValue.of("hello")))))));
        List<ToolContent> mapped = mapper.map(result);
        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0)).isInstanceOf(ToolContent.TextItem.class);
    }

    @Test
    void mapsImage() {
        JsonValue result = JsonValue.of(Map.of("content", JsonValue.of(List.of(
                JsonValue.of(Map.of("type", JsonValue.of("image"), "uri", JsonValue.of("u"), "mimeType", JsonValue.of("image/png")))))));
        List<ToolContent> mapped = mapper.map(result);
        assertThat(mapped).hasSize(1);
        assertThat(mapped.get(0)).isInstanceOf(ToolContent.FileItem.class);
    }

    @Test
    void isError() {
        assertThat(mapper.isError(JsonValue.of(Map.of("isError", JsonValue.of(true))))).isTrue();
        assertThat(mapper.isError(JsonValue.of(Map.of("isError", JsonValue.of(false))))).isFalse();
        assertThat(mapper.isError(JsonValue.of(Map.of()))).isFalse();
    }
}
