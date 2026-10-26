package de.augmentia.oc.engine.runtime.command;

import de.augmentia.oc.engine.core.model.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads text macros from {@code command/<name>.md} (project, .opencode, global).
 * Expansion is intentionally small: $ARGUMENTS, $@ and positional $1..$9.
 */
public final class CommandLoader {

    private static final int MAX_BYTES = 32 * 1024;

    private final Path workspace;
    private final Path global;

    public CommandLoader(Path workspace, Path global) {
        if (workspace == null) throw new IllegalArgumentException("workspace must not be null");
        this.workspace = workspace.toAbsolutePath().normalize();
        this.global = global == null ? null : global.toAbsolutePath().normalize();
    }

    public Option<String> expand(String name, String arguments) {
        if (name == null || name.isBlank()) return Option.none();
        String args = arguments == null ? "" : arguments;
        for (Path candidate : candidates(name)) {
            Option<String> text = read(candidate);
            if (text.isPresent()) return Option.some(substitute(text.orElseThrow(), args));
        }
        return Option.none();
    }

    private List<Path> candidates(String name) {
        List<Path> paths = new ArrayList<>();
        String file = name.endsWith(".md") ? name : name + ".md";
        paths.add(workspace.resolve("command").resolve(file));
        paths.add(workspace.resolve(".opencode/command").resolve(file));
        if (global != null) paths.add(global.resolve("command").resolve(file));
        return paths;
    }

    private static Option<String> read(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return Option.none();
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return text.isBlank() ? Option.none() : Option.some(text);
        } catch (IOException e) {
            return Option.none();
        }
    }

    public static String substitute(String template, String arguments) {
        String args = arguments == null ? "" : arguments;
        List<String> parsed = splitArgs(args);
        String joined = String.join(" ", parsed);
        String result = template.replace("$ARGUMENTS", joined).replace("$@", joined);
        for (int i = 0; i < parsed.size() && i < 9; i++) {
            result = result.replace("$" + (i + 1), parsed.get(i));
        }
        return result;
    }

    /** Minimal shell-like split; quotes are removed, no variable expansion. */
    static List<String> splitArgs(String input) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean doubleQuote = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'' && !doubleQuote) {
                single = !single;
            } else if (c == '"' && !single) {
                doubleQuote = !doubleQuote;
            } else if (Character.isWhitespace(c) && !single && !doubleQuote) {
                if (!current.isEmpty()) {
                    values.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) values.add(current.toString());
        return List.copyOf(values);
    }
}
