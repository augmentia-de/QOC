package de.augmentia.oc.engine.runtime.resilience;

import de.augmentia.oc.engine.core.model.SessionId;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Proactive validation before task start (FR-1.1, FR-1.2, FR-1.3).
 *
 * <p>Checks DNS resolution, HTTP health, API keys, database connectivity,
 * and workspace writability. If any blocking check fails, the agent must not
 * start.</p>
 */
public interface PreflightChecker {

    /** Runs all pre-flight checks and returns the result. */
    PreflightResult check(PreflightContext ctx);

    /** Functional interface for database connectivity check (avoids java.sql in resilience package). */
    @FunctionalInterface
    interface DatabaseChecker {
        boolean isHealthy();
    }

    /** Context for pre-flight checks. */
    record PreflightContext(
            String providerUrl,
            String apiKey,
            DatabaseChecker databaseChecker,
            java.nio.file.Path workspacePath) {}

    /** Result of pre-flight checks. */
    record PreflightResult(boolean passed, List<PreflightIssue> issues) {
        public PreflightResult {
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    /** A single pre-flight issue. */
    record PreflightIssue(String type, String message, boolean blocking) {}
}