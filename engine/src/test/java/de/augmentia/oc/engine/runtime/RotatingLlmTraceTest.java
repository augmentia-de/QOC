package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.adapter.log.RotatingLlmTrace;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolName;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RotatingLlmTraceTest {

    @Test
    void writesRequestAndAssembledResponse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("llm.log");
        try (RotatingLlmTrace trace = RotatingLlmTrace.open(file, 64 * 1024, 2)) {
            trace.request("https://provider.test/v1/chat", "{\"model\":\"test\"}");
            trace.response(200, List.of(
                    new ModelEvent.TextDelta("b1", "Hello "),
                    new ModelEvent.TextDelta("b1", "world"),
                    new ModelEvent.ToolCallRequested(CallId.create(), ToolName.of("read"),
                            JsonValue.of("x")),
                    new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.of(3, 4),
                            JsonValue.ofNull())));
        }

        String content = readAll(dir);
        assertThat(content).contains("REQUEST");
        assertThat(content).contains("{\"model\":\"test\"}");
        assertThat(content).contains("RESPONSE status=200");
        assertThat(content).contains("Hello world");
        assertThat(content).contains("[tool] read");
        assertThat(content).contains("[finish] NORMAL input=3 output=4");
    }

    @Test
    void logsTheRawErrorBody(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("llm.log");
        try (RotatingLlmTrace trace = RotatingLlmTrace.open(file, 64 * 1024, 2)) {
            trace.error(429, "{\"error\":\"rate limited\"}");
        }

        assertThat(readAll(dir)).contains("ERROR status=429").contains("rate limited");
    }

    @Test
    void rotatesAtTheConfiguredLimit(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("llm.log");
        try (RotatingLlmTrace trace = RotatingLlmTrace.open(file, 512, 3)) {
            for (int i = 0; i < 40; i++) {
                trace.request("https://provider.test", "body-" + i + "-" + "x".repeat(64));
            }
        }

        List<Path> parts;
        try (var stream = Files.list(dir)) {
            parts = stream.filter(path -> path.getFileName().toString().startsWith("llm.log"))
                    .sorted()
                    .toList();
        }

        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts).hasSizeLessThanOrEqualTo(3);
        assertThat(parts).noneMatch(path -> {
            try {
                return Files.size(path) > 1024;
            } catch (IOException e) {
                return false;
            }
        });
    }

    private static String readAll(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(path -> path.getFileName().toString().startsWith("llm.log"))
                    .sorted()
                    .map(RotatingLlmTraceTest::read)
                    .collect(Collectors.joining("\n"));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
