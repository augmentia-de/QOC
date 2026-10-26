package de.augmentia.oc.engine.core;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.eval.OutputBounding;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutputBoundingTest {

    private static final Path OUTPUT = Path.of("/data/tool-output/tool_1");

    @Test
    void smallResultIsUnchanged() {
        ToolResult result = new ToolResult(Option.some(JsonValue.of("42")),
                List.of(new ToolContent.TextItem("a\nb")), Map.of("ui", "hint"));
        OutputBounding.Result bounded = OutputBounding.bound(result, OUTPUT);
        assertThat(bounded).isInstanceOf(OutputBounding.Result.Unchanged.class);
        assertThat(((OutputBounding.Result.Unchanged) bounded).result()).isEqualTo(result);
    }

    @Test
    void tooManyLinesAreTruncated() {
        String text = "line\n".repeat(OutputBounding.MAX_LINES + 10);
        ToolResult result = new ToolResult(Option.none(), List.of(new ToolContent.TextItem(text)), Map.of());

        OutputBounding.Result bounded = OutputBounding.bound(result, OUTPUT);

        assertThat(bounded).isInstanceOf(OutputBounding.Result.Truncated.class);
        OutputBounding.Result.Truncated truncated = (OutputBounding.Result.Truncated) bounded;
        assertThat(truncated.fullText()).isEqualTo(text);
        assertThat(truncated.reference()).isEqualTo(OUTPUT.toString());
        assertThat(truncated.result().metadata()).containsEntry(OutputBounding.TRUNCATED, "yes");
        assertThat(truncated.result().metadata()).containsEntry(OutputBounding.OUTPUT_PATH, OUTPUT.toString());
        assertThat(visible(truncated.result())).startsWith("[showing lines 1-2000 of 2010; full output saved to "
                + OUTPUT + "]");
        assertThat(truncated.result().output().isEmpty()).isTrue();
    }

    @Test
    void tooManyBytesAreTruncated() {
        String text = "a".repeat((int) OutputBounding.MAX_BYTES + 1);
        ToolResult result = new ToolResult(Option.none(), List.of(new ToolContent.TextItem(text)), Map.of());

        OutputBounding.Result bounded = OutputBounding.bound(result, OUTPUT);

        assertThat(bounded).isInstanceOf(OutputBounding.Result.Truncated.class);
        assertThat(((OutputBounding.Result.Truncated) bounded).result().metadata())
                .containsEntry(OutputBounding.TRUNCATED, "yes");
    }

    @Test
    void alreadyTruncatedResultIsLeftAlone() {
        ToolResult result = new ToolResult(Option.none(),
                List.of(new ToolContent.TextItem("line\n".repeat(OutputBounding.MAX_LINES + 10))),
                Map.of(OutputBounding.TRUNCATED, true));

        OutputBounding.Result bounded = OutputBounding.bound(result, OUTPUT);

        assertThat(bounded).isInstanceOf(OutputBounding.Result.Unchanged.class);
        assertThat(((OutputBounding.Result.Unchanged) bounded).result()).isEqualTo(result);
    }

    @Test
    void stringMarkerAlsoCountsAsTruncated() {
        ToolResult result = new ToolResult(Option.none(),
                List.of(new ToolContent.TextItem("line\n".repeat(OutputBounding.MAX_LINES + 10))),
                Map.of(OutputBounding.TRUNCATED, "yes"));
        assertThat(OutputBounding.bound(result, OUTPUT)).isInstanceOf(OutputBounding.Result.Unchanged.class);
    }

    @Test
    void machineOutputValueIsNeverChanged() {
        String text = "line\n".repeat(OutputBounding.MAX_LINES + 10);
        ToolResult result = new ToolResult(Option.some(JsonValue.of("machine")),
                List.of(new ToolContent.TextItem(text)), Map.of());

        OutputBounding.Result.Truncated truncated =
                (OutputBounding.Result.Truncated) OutputBounding.bound(result, OUTPUT);

        assertThat(truncated.result().output().orElseThrow()).isEqualTo(JsonValue.of("machine"));
    }

    @Test
    void fileItemsSurviveBounding() {
        String text = "line\n".repeat(OutputBounding.MAX_LINES + 10);
        ToolResult result = new ToolResult(Option.none(), List.of(
                new ToolContent.TextItem(text),
                new ToolContent.FileItem("file:///a", "text/plain", Option.some("a"))), Map.of());

        OutputBounding.Result.Truncated truncated =
                (OutputBounding.Result.Truncated) OutputBounding.bound(result, OUTPUT);

        assertThat(truncated.result().content()).anyMatch(item -> item instanceof ToolContent.FileItem);
    }

    @Test
    void resultWithoutTextIsUnchanged() {
        ToolResult onlyFile = new ToolResult(Option.none(),
                List.of(new ToolContent.FileItem("file:///a", "text/plain", Option.none())), Map.of());
        assertThat(OutputBounding.bound(onlyFile, OUTPUT)).isInstanceOf(OutputBounding.Result.Unchanged.class);
    }

    @Test
    void textPartsAreConcatenatedWithNewlines() {
        String text = "a\n".repeat(OutputBounding.MAX_LINES + 10);
        ToolResult result = new ToolResult(Option.none(),
                List.of(new ToolContent.TextItem(text), new ToolContent.TextItem("tail")), Map.of());
        OutputBounding.Result.Truncated truncated =
                (OutputBounding.Result.Truncated) OutputBounding.bound(result, OUTPUT);
        assertThat(truncated.fullText()).endsWith("tail");
    }

    @Test
    void invalidArgumentsAreRejected() {
        ToolResult result = ToolResult.of("x");
        assertThatThrownBy(() -> OutputBounding.bound(null, OUTPUT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutputBounding.bound(result, (Path) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String visible(ToolResult result) {
        return result.content().stream()
                .filter(item -> item instanceof ToolContent.TextItem)
                .map(item -> ((ToolContent.TextItem) item).text())
                .findFirst()
                .orElse("");
    }
}
