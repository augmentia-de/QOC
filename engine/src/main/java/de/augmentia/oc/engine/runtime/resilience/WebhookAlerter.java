package de.augmentia.oc.engine.runtime.resilience;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Webhook-based alerting for escalation (FR-6.1, FR-6.2).
 *
 * <p>Sends structured alerts to Slack, Telegram, or a generic webhook endpoint
 * when a task fails permanently or requires human intervention.</p>
 */
public interface WebhookAlerter {

    /** Sends an alert. */
    void alert(Alert alert);

    record Alert(
            String taskId,
            String errorType,
            String message,
            String lastState,
            List<String> actionLinks,
            Instant timestamp) {
        public Alert {
            actionLinks = actionLinks == null ? List.of() : List.copyOf(actionLinks);
            if (timestamp == null) timestamp = Instant.now();
        }
    }

    /** Default implementation using java.net.http. */
    final class Default implements WebhookAlerter {
        private final String webhookUrl;
        private final String format;
        private final HttpClient httpClient;

        public Default() {
            this(null, "generic");
        }

        public Default(String webhookUrl, String format) {
            this.webhookUrl = (webhookUrl == null || webhookUrl.isBlank() || "none".equalsIgnoreCase(webhookUrl)) ? null : webhookUrl;
            this.format = format != null ? format : "generic";
            this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        }

        @Override
        public void alert(Alert alert) {
            if (webhookUrl == null || webhookUrl.isBlank()) return;
            try {
                String body = formatBody(alert);
                HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                // Alerting must not crash the engine
                java.util.logging.Logger.getLogger("WebhookAlerter")
                        .warning("WebhookAlerter failed: " + e.getMessage());
            }
        }

        private String formatBody(Alert alert) {
            return switch (format) {
                case "slack" -> String.format(
                        "{\"text\":\"Task %s failed: %s\",\"attachments\":[{\"title\":\"%s\",\"text\":\"%s\"}]}",
                        alert.taskId(), alert.errorType(), alert.errorType(), alert.message());
                case "telegram" -> String.format(
                        "{\"text\":\"Task %s failed: %s\\n%s\"}",
                        alert.taskId(), alert.errorType(), alert.message());
                default -> String.format(
                        "{\"task_id\":\"%s\",\"error_type\":\"%s\",\"message\":\"%s\",\"last_state\":\"%s\",\"timestamp\":\"%s\"}",
                        alert.taskId(), alert.errorType(), alert.message().replace("\"", "\\\\\""),
                        alert.lastState() != null ? alert.lastState().replace("\"", "\\\\\"") : "",
                        alert.timestamp());
            };
        }
    }
}
