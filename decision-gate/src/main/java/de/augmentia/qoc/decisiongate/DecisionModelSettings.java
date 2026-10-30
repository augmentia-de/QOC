package de.augmentia.qoc.decisiongate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * Configuration for a System One compatible decision model.
 *
 * <p>The same settings can be read from environment variables, from a
 * {@link Properties} object, or from an {@code application.properties} file.
 * The API key is never included in {@link #toString()}.</p>
 */
public record DecisionModelSettings(
        String baseUrl,
        String apiKey,
        String modelName,
        Duration timeout,
        int maxRetries) {

    public static final String DEFAULT_BASE_URL = "https://api.typesafe.ai";
    public static final String DEFAULT_MODEL = "jev-latest";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    public DecisionModelSettings {
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = DEFAULT_BASE_URL;
        if (modelName == null || modelName.isBlank()) modelName = DEFAULT_MODEL;
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be >= 0");
        apiKey = apiKey == null ? "" : apiKey;
    }

    /** Reads {@code TYPESAFE_*} or {@code QOC_DECISION_*} environment variables. */
    public static DecisionModelSettings fromEnvironment() {
        return from(DecisionModelSettings::environmentValue);
    }

    /** Reads {@code qoc.decision.*} system properties, falling back to the environment. */
    public static DecisionModelSettings fromSystemProperties() {
        return from(name -> {
            String value = System.getProperty(name);
            return value != null ? value : environmentValue(name);
        });
    }

    /**
     * Reads settings from a standard properties file such as
     * {@code application.properties}. Values of the form {@code ${NAME}} are
     * resolved from the environment or JVM system properties.
     */
    public static DecisionModelSettings fromApplicationProperties(Path file) throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return from(name -> resolvePlaceholder(properties.getProperty(name)));
    }

    /** Reads settings from an arbitrary lookup function. */
    public static DecisionModelSettings from(Function<String, String> lookup) {
        Objects.requireNonNull(lookup, "lookup must not be null");
        String baseUrl = first(lookup, "qoc.decision.base-url", "QOC_DECISION_BASE_URL", "TYPESAFE_BASE_URL");
        String apiKey = first(lookup, "qoc.decision.api-key", "QOC_DECISION_API_KEY", "TYPESAFE_API_KEY");
        String model = first(lookup, "qoc.decision.model", "QOC_DECISION_MODEL", "TYPESAFE_MODEL");
        String timeoutText = first(lookup, "qoc.decision.timeout", "QOC_DECISION_TIMEOUT", "TYPESAFE_TIMEOUT");
        String retriesText = first(lookup, "qoc.decision.max-retries", "QOC_DECISION_MAX_RETRIES",
                "TYPESAFE_MAX_RETRIES");
        return new DecisionModelSettings(
                blankToDefault(baseUrl, DEFAULT_BASE_URL),
                apiKey == null ? "" : apiKey,
                blankToDefault(model, DEFAULT_MODEL),
                parseDuration(blankToDefault(timeoutText, "10s")),
                parseRetries(blankToDefault(retriesText, "2")));
    }

    /** Converts a property map; useful for tests and embedded configuration. */
    public static DecisionModelSettings fromMap(Map<String, String> values) {
        return from(values::get);
    }

    @Override
    public String toString() {
        return "DecisionModelSettings[baseUrl=" + baseUrl
                + ", apiKey=***, modelName=" + modelName
                + ", timeout=" + timeout
                + ", maxRetries=" + maxRetries + "]";
    }

    private static String first(Function<String, String> lookup, String... names) {
        for (String name : names) {
            String value = lookup.apply(name);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String environmentValue(String name) {
        return switch (name) {
            case "qoc.decision.base-url" -> System.getenv("QOC_DECISION_BASE_URL");
            case "qoc.decision.api-key" -> System.getenv("QOC_DECISION_API_KEY");
            case "qoc.decision.model" -> System.getenv("QOC_DECISION_MODEL");
            case "qoc.decision.timeout" -> System.getenv("QOC_DECISION_TIMEOUT");
            case "qoc.decision.max-retries" -> System.getenv("QOC_DECISION_MAX_RETRIES");
            default -> System.getenv(name);
        };
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Duration parseDuration(String value) {
        try {
            if (value.matches("\\d+")) return Duration.ofSeconds(Long.parseLong(value));
            if (value.matches("\\d+ms")) return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
            if (value.matches("\\d+s")) return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
            if (value.matches("\\d+m")) return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
            return Duration.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid decision model timeout: " + value, e);
        }
    }

    private static int parseRetries(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid decision model max-retries: " + value, e);
        }
    }

    private static String resolvePlaceholder(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (!trimmed.startsWith("${") || !trimmed.endsWith("}")) return value;
        String name = trimmed.substring(2, trimmed.length() - 1);
        String resolved = System.getenv(name);
        if (resolved == null) resolved = System.getProperty(name);
        return resolved == null ? "" : resolved;
    }
}
