package de.augmentia.oc.engine.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class PortableScanner implements ShellScanner {

    private static final Set<String> SUBCOMMAND_COMMANDS = Set.of(
            "git", "npm", "yarn", "pnpm", "docker", "kubectl", "cargo", "go", "mvn", "gradle");

    @Override
    public ScanResult scan(String command, String shell, Path cwd) {
        List<ScannedCommand> commands = new ArrayList<>();
        List<Path> directories = new ArrayList<>();

        if (command == null || command.isBlank()) {
            return new ScanResult(commands, directories);
        }

        for (String part : splitChains(command)) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            List<String> tokens = tokenize(trimmed);
            if (tokens.isEmpty()) continue;

            String resource = trimmed;
            String prefix = prefixOf(tokens);
            String save = prefix + " *";

            commands.add(new ScannedCommand(resource, save));

            for (String token : tokens) {
                if (looksLikePath(token)) {
                    try {
                        directories.add(Path.of(stripQuotes(token)));
                    } catch (Exception ignored) {
                        // ignore unparseable tokens
                    }
                }
            }
        }

        return new ScanResult(commands, directories);
    }

    private static List<String> splitChains(String command) {
        // Split on &&, ||, ;, |, newline. Simple heuristic, not quote-aware.
        return Arrays.asList(command.split("\\s*(?:&&|\\|\\||;|\\|)\\s*"));
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inSingle) {
                cur.append(c);
                if (c == '\'') inSingle = false;
            } else if (inDouble) {
                cur.append(c);
                if (c == '"') inDouble = false;
            } else if (c == '\'') {
                inSingle = true;
                cur.append(c);
            } else if (c == '"') {
                inDouble = true;
                cur.append(c);
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String prefixOf(List<String> tokens) {
        if (tokens.size() == 1) {
            return stripQuotes(tokens.get(0));
        }
        String first = stripQuotes(tokens.get(0));
        if (SUBCOMMAND_COMMANDS.contains(first) && tokens.size() >= 2) {
            return first + " " + stripQuotes(tokens.get(1));
        }
        return first;
    }

    private static boolean looksLikePath(String token) {
        String t = stripQuotes(token);
        return t.startsWith("/") || t.startsWith("~") || t.startsWith(".")
                || t.contains("/") || t.contains("\\");
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}