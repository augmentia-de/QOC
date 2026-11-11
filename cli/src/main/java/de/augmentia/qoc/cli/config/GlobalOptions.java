package de.augmentia.qoc.cli.config;

import de.augmentia.qoc.cli.client.QocClient;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class GlobalOptions {

    private static String rootUrl;
    private static String rootToken;
    private static int rootTimeoutSeconds = -1;
    private static boolean rootJson;

    @Option(names = {"-u", "--url"}, description = "QOC server base URL", defaultValue = "${QOC_URL:}")
    public String url;

    @Option(names = {"-t", "--token"}, description = "Bearer token", defaultValue = "${QOC_TOKEN:}")
    public String token;

    @Option(names = {"--timeout"}, description = "HTTP timeout in seconds", defaultValue = "60")
    public int timeoutSeconds = 60;

    @Option(names = {"--json"}, description = "Print raw JSON")
    public boolean json;

    public static void setRootUrl(String value) { rootUrl = value; }
    public static void setRootToken(String value) { rootToken = value; }
    public static void setRootTimeoutSeconds(int value) { rootTimeoutSeconds = value; }
    public static void setRootJson(boolean value) { rootJson = value; }

    public String resolvedUrl() {
        if (url != null && !url.isBlank()) return url;
        if (rootUrl != null && !rootUrl.isBlank()) return rootUrl;
        String env = System.getenv("QOC_URL");
        if (env != null && !env.isBlank()) return env;
        return "http://localhost:8080";
    }

    public String resolvedToken() {
        if (token != null && !token.isBlank()) return token;
        if (rootToken != null && !rootToken.isBlank()) return rootToken;
        String env = System.getenv("QOC_TOKEN");
        if (env != null && !env.isBlank()) return env;
        return null;
    }

    public int resolvedTimeoutSeconds() {
        if (timeoutSeconds > 0) return timeoutSeconds;
        if (rootTimeoutSeconds > 0) return rootTimeoutSeconds;
        return 60;
    }

    public boolean isJson() {
        return json || rootJson;
    }

    public QocClient client() {
        return new QocClient(resolvedUrl(), resolvedToken(),
                Duration.ofSeconds(resolvedTimeoutSeconds()));
    }

    public static String defaultSessionId() {
        try {
            Path p = Path.of(System.getProperty("user.home"), ".qoc", "session");
            if (Files.exists(p)) return Files.readString(p).trim();
        } catch (IOException ignored) {}
        return null;
    }

    public static void saveSessionId(String id) {
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".qoc");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("session"), id);
        } catch (IOException ignored) {}
    }
}
