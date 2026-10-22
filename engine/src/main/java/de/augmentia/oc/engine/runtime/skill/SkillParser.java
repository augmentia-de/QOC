package de.augmentia.oc.engine.runtime.skill;

import de.augmentia.oc.engine.core.json.DecodeError;
import de.augmentia.oc.engine.core.json.Result;
import de.augmentia.oc.engine.core.model.SkillId;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a Markdown skill with a small YAML frontmatter subset. Invalid
 * frontmatter yields a decode error; the caller skips the file (SK-11).
 */
public final class SkillParser {

    public static final int MAX_DESCRIPTION = 500;
    public static final int MAX_BODY_BYTES = 4 * 1024;

    public Result<Skill> parse(Path file, String content) {
        if (file == null) return error("$", "file is null");
        if (content == null) return error("$", "content is null");

        String normalized = content.startsWith("\uFEFF") ? content.substring(1) : content;
        if (!normalized.startsWith("---")) {
            return error("$", "missing frontmatter");
        }
        int firstLineEnd = normalized.indexOf('\n');
        if (firstLineEnd < 0) return error("$", "missing closing frontmatter");
        int closing = normalized.indexOf("\n---", firstLineEnd);
        if (closing < 0) return error("$", "missing closing frontmatter");

        String frontmatter = normalized.substring(firstLineEnd + 1, closing);
        String body = normalized.substring(closing + 4);
        if (body.startsWith("\n")) body = body.substring(1);

        Map<String, String> top = new LinkedHashMap<>();
        Map<String, String> metadata = new LinkedHashMap<>();
        Result<Void> parsed = parseFrontmatter(frontmatter, top, metadata);
        if (parsed instanceof Result.Err<Void> error) return new Result.Err<>(error.error());

        String idValue = id(file);
        if (idValue == null || idValue.isBlank()) return error("$.id", "cannot derive skill id");

        String description = top.getOrDefault("description", "");
        if (description.getBytes(StandardCharsets.UTF_8).length > MAX_DESCRIPTION) {
            return error("$.description", "description exceeds " + MAX_DESCRIPTION + " characters");
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return error("$.body", "body exceeds " + MAX_BODY_BYTES + " bytes");
        }

        boolean autoinvoke = !"false".equalsIgnoreCase(metadata.get("opencode/autoinvoke"))
                && !"true".equalsIgnoreCase(top.get("disable-model-invocation"));
        String name = top.getOrDefault("name", idValue);

        try {
            return new Result.Ok<>(new Skill(SkillId.parse(idValue), name, description, body,
                    file.getParent(), List.of(), autoinvoke));
        } catch (IllegalArgumentException e) {
            return error("$.id", e.getMessage());
        }
    }

    private static Result<Void> parseFrontmatter(String frontmatter, Map<String, String> top,
                                                 Map<String, String> metadata) {
        String section = null;
        String[] lines = frontmatter.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            int indent = leadingSpaces(line);
            String trimmed = line.trim();
            int colon = trimmed.indexOf(':');
            if (colon < 0) return errorVoid("$.frontmatter[" + i + "]", "expected key: value");
            String key = trimmed.substring(0, colon).trim();
            String value = unquote(trimmed.substring(colon + 1).trim());
            if (indent == 0) {
                section = key;
                if (!value.isEmpty()) top.put(key, value);
            } else {
                if (section == null) return errorVoid("$.frontmatter[" + i + "]", "nested value without key");
                if ("metadata".equals(section)) metadata.put(key, value);
            }
        }
        return new Result.Ok<>(null);
    }

    static String id(Path file) {
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        if (fileName.equals("SKILL.md")) {
            Path parent = file.getParent();
            return parent == null || parent.getFileName() == null ? null : parent.getFileName().toString();
        }
        if (fileName.endsWith(".md")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        return null;
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') count++;
        return count;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Result<Skill> error(String path, String message) {
        return new Result.Err<>(new DecodeError(path, message));
    }

    private static Result<Void> errorVoid(String path, String message) {
        return new Result.Err<>(new DecodeError(path, message));
    }
}
