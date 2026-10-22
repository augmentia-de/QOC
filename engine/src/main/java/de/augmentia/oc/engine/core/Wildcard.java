package de.augmentia.oc.engine.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class Wildcard {

    private Wildcard() {}

    public static boolean matches(String input, String pattern) {
        return match(input, pattern);
    }

    public static boolean match(String input, String pattern) {
        if (input == null || pattern == null) return false;
        String normalizedInput = input.replace('\\', '/');
        String normalizedPattern = pattern.replace('\\', '/');

        boolean trailingSpaceStar = normalizedPattern.endsWith(" *");

        StringBuilder regex = new StringBuilder("^");
        List<String> literal = new ArrayList<>();
        for (int i = 0; i < normalizedPattern.length(); i++) {
            char c = normalizedPattern.charAt(i);
            if (c == '*') {
                if (trailingSpaceStar && i == normalizedPattern.length() - 1) {
                    if (!literal.isEmpty() && " ".equals(literal.get(literal.size() - 1))) {
                        literal.remove(literal.size() - 1);
                    }
                    flush(literal, regex);
                    regex.append("( .*)?");
                } else {
                    flush(literal, regex);
                    regex.append(".*");
                }
            } else if (c == '?') {
                flush(literal, regex);
                regex.append('.');
            } else {
                literal.add(String.valueOf(c));
            }
        }
        flush(literal, regex);

        regex.append('$');

        int flags = Pattern.DOTALL;
        if (isWindows()) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        return Pattern.compile(regex.toString(), flags).matcher(normalizedInput).matches();
    }

    private static void flush(List<String> literal, StringBuilder regex) {
        if (literal.isEmpty()) return;
        regex.append(Pattern.quote(String.join("", literal)));
        literal.clear();
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }
}