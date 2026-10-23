package de.augmentia.oc.engine.core.eval;

import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.model.ToolContent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic output bounding (tools/02-filesystem.md §9). The tool's own limits
 * run first; this kicks in only when the producer did not mark the result.
 * Bounding never changes the machine output value, only the visible content.
 */
public final class OutputBounding {

    public static final int MAX_LINES = 2_000;
    public static final long MAX_BYTES = 50L * 1024L;
    public static final String TRUNCATED = "truncated";
    public static final String OUTPUT_PATH = "outputPath";

    private OutputBounding() {}

    public sealed interface Result {
        record Unchanged(ToolResult result) implements Result {}

        record Truncated(ToolResult result, String fullText, Path outputPath) implements Result {}
    }

    public static Result boundStoring(ToolResult result, java.util.function.Function<String, Path> saver) {
        if (saver == null) throw new IllegalArgumentException("saver must not be null");
        Result probe = bound(result, Path.of(""));
        if (!(probe instanceof Result.Truncated truncated)) return probe;
        Path path = saver.apply(truncated.fullText());
        return bound(result, path);
    }

    public static Result bound(ToolResult result, Path outputPath) {
        if (result == null) throw new IllegalArgumentException("result must not be null");
        if (outputPath == null) throw new IllegalArgumentException("outputPath must not be null");

        if (isMarkedTruncated(result.metadata())) {
            return new Result.Unchanged(result);
        }

        String text = visibleText(result.content());
        if (text.isEmpty()) {
            return new Result.Unchanged(result);
        }

        long lines = text.lines().count();
        long bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (lines <= MAX_LINES && bytes <= MAX_BYTES) {
            return new Result.Unchanged(result);
        }

        String prefix = prefix(text);
        long shown = prefix.isEmpty() ? 0 : prefix.lines().count();
        String hint = PromptLoader.render("bounding-hint", Map.of(
                "shown", String.valueOf(shown),
                "total", String.valueOf(lines),
                "path", outputPath.toString()));

        List<ToolContent> content = new ArrayList<>();
        content.add(new ToolContent.TextItem(prefix.isEmpty() ? hint : hint + "\n" + prefix));
        for (ToolContent item : result.content()) {
            if (item instanceof ToolContent.FileItem) {
                content.add(item);
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put(TRUNCATED, "yes");
        metadata.put(OUTPUT_PATH, outputPath.toString());

        ToolResult bounded = new ToolResult(result.output(), content, metadata);
        return new Result.Truncated(bounded, text, outputPath);
    }

    private static boolean isMarkedTruncated(Map<String, Object> metadata) {
        Object value = metadata.get(TRUNCATED);
        return Boolean.TRUE.equals(value) || "yes".equalsIgnoreCase(String.valueOf(value));
    }

    private static String visibleText(List<ToolContent> content) {
        StringBuilder text = new StringBuilder();
        for (ToolContent item : content) {
            if (item instanceof ToolContent.TextItem textItem) {
                if (!text.isEmpty()) text.append('\n');
                text.append(textItem.text());
            }
        }
        return text.toString();
    }

    /** First lines that fit both the line and the byte limit. */
    private static String prefix(String text) {
        StringBuilder prefix = new StringBuilder();
        long bytes = 0;
        int lines = 0;
        for (String line : text.split("\n", -1)) {
            long added = line.getBytes(StandardCharsets.UTF_8).length + (lines == 0 ? 0 : 1);
            if (lines >= MAX_LINES || bytes + added > MAX_BYTES) {
                break;
            }
            if (lines > 0) prefix.append('\n');
            prefix.append(line);
            bytes += added;
            lines++;
        }
        return prefix.toString();
    }
}
