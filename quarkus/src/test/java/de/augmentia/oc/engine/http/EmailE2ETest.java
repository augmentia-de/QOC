package de.augmentia.oc.engine.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestProfile(EmailE2ETest.Profile.class)
class EmailE2ETest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> o = new HashMap<>();
            String imapHost = env("ENGINE_EMAIL_IMAP_HOST", "mail3.netbeat.de");
            String imapPort = env("ENGINE_EMAIL_IMAP_PORT", "143");
            String imapSsl = env("ENGINE_EMAIL_IMAP_SSL", "false");
            String imapUser = env("ENGINE_EMAIL_IMAP_USER", "test@schippel.info");
            String imapPass = env("ENGINE_EMAIL_IMAP_PASSWORD", "testTEST12");
            String smtpHost = env("ENGINE_EMAIL_SMTP_HOST", "mailout.netbeat.de");
            String smtpPort = env("ENGINE_EMAIL_SMTP_PORT", "587");
            String smtpStarttls = env("ENGINE_EMAIL_SMTP_STARTTLS", "true");
            String smtpUser = env("ENGINE_EMAIL_SMTP_USER", "test@schippel.info");
            String smtpPass = env("ENGINE_EMAIL_SMTP_PASSWORD", "testTEST12");

            o.put("engine.email.imap-host", imapHost);
            o.put("engine.email.imap-port", imapPort);
            o.put("engine.email.imap-ssl", imapSsl);
            o.put("engine.email.imap-user", imapUser);
            o.put("engine.email.imap-password", imapPass);
            o.put("engine.email.smtp-host", smtpHost);
            o.put("engine.email.smtp-port", smtpPort);
            o.put("engine.email.smtp-starttls", smtpStarttls);
            o.put("engine.email.smtp-user", smtpUser);
            o.put("engine.email.smtp-password", smtpPass);
            String apiKey = System.getenv("OPENAI_API_KEY");
            String baseUrl = System.getenv("OPENAI_BASE_URL");
            String model = System.getenv("OPENAI_MODEL");
            String providerUrl = System.getenv("ENGINE_PROVIDER_URL");
            if (apiKey != null) o.put("engine.provider.api-key", apiKey);
            if (baseUrl != null) o.put("engine.provider.url", baseUrl + "/chat/completions");
            if (providerUrl != null) o.put("engine.provider.url", providerUrl);
            if (model != null) o.put("engine.model.id", model);
            o.put("engine.provider.scripted", "false");
            o.put("engine.workspace", System.getProperty("java.io.tmpdir") + "/qoc-email-e2e");
            o.put("engine.instructions.enabled", "false");
            o.put("quarkus.datasource.jdbc.url", "jdbc:postgresql://localhost:20000/qoc?sslmode=disable");
            o.put("quarkus.datasource.username", "postgres");
            o.put("quarkus.datasource.password", "postgres");
            return o;
        }

        private static String env(String key, String def) {
            String v = System.getenv(key);
            return (v == null || v.isBlank()) ? def : v;
        }
    }

    private static boolean available() {
        String key = System.getenv("OPENAI_API_KEY");
        return key != null && !key.isBlank();
    }

    @Test
    void fetchListsInbox() {
        Assumptions.assumeTrue(available());
        String id = createSession("build");
        String text = prompt(id, "Call the tool email_fetch with arguments {\\\"folder\\\": \\\"INBOX\\\", \\\"limit\\\": 3}. Then summarize the result in one sentence.");
        assertThat(text).isNotBlank();
        System.out.println("FETCH RESULT: " + text);
    }

    @Test
    void readFirstMessage() {
        Assumptions.assumeTrue(available());
        String id = createSession("build");
        String text = prompt(id, "Call email_fetch with {\\\"folder\\\": \\\"INBOX\\\", \\\"limit\\\": 1}. Then call email_read with the uid from the result. Report the subject.");
        assertThat(text).isNotBlank();
        System.out.println("READ RESULT: " + text);
    }

    @Test
    void sendEmail() {
        Assumptions.assumeTrue(available());
        String id = createSession("build");
        String subject = "QOC E2E test " + System.currentTimeMillis();
        String text = prompt(id, "Use email_send to send an email to test@schippel.info with subject '" + subject + "' and body 'Hello from QOC E2E test'. Confirm success.");
        assertThat(text).isNotBlank();
        System.out.println("SEND RESULT: " + text);
    }

    private static String createSession(String agent) {
        return given()
                .contentType("application/json")
                .body(Map.of("agent", agent))
                .post("/api/session")
                .then().statusCode(200)
                .extract().path("id");
    }

    private static String prompt(String sessionId, String text) {
        return given()
                .config(io.restassured.config.RestAssuredConfig.config()
                        .httpClient(io.restassured.config.HttpClientConfig.httpClientConfig()
                                .setParam("http.connection.timeout", 10000)
                                .setParam("http.socket.timeout", 120000)))
                .contentType("application/json")
                .body(Map.of("text", text))
                .post("/api/session/" + sessionId + "/prompt")
                .then().statusCode(200)
                .extract().path("text");
    }
}