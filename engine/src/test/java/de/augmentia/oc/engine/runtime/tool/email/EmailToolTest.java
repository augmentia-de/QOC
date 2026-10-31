package de.augmentia.oc.engine.runtime.tool.email;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.port.world.EmailClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailToolTest {

    private static FakeEmailClient client() {
        return new FakeEmailClient();
    }

    private static JsonValue args(Map<String, JsonValue> map) {
        return JsonValue.of(map);
    }

    private static String text(ToolOutcome outcome) {
        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        return ((ToolOutcome.Ok) outcome).result().content().stream()
                .filter(c -> c instanceof ToolContent.TextItem)
                .map(c -> ((ToolContent.TextItem) c).text())
                .findFirst().orElse("");
    }

    @Test
    void fetchListsMessages() {
        FakeEmailClient c = client();
        ToolDefinition def = EmailTool.fetchDefinition(c);
        ToolOutcome outcome = def.execute().execute(args(Map.of(
                "folder", JsonValue.of("INBOX"), "limit", JsonValue.of(5))), null);
        assertThat(text(outcome)).contains("Folder: INBOX").contains("hello");
    }

    @Test
    void readReturnsBodyAndAttachmentsWhenEnabled() {
        FakeEmailClient c = client();
        ToolDefinition def = EmailTool.readDefinition(c, EmailLimits.defaults());
        ToolOutcome outcome = def.execute().execute(args(Map.of("uid", JsonValue.of("1"))), null);
        assertThat(text(outcome)).contains("Subject: hi").contains("base64");
    }

    @Test
    void readHidesAttachmentsWhenDisabled() {
        FakeEmailClient c = client();
        EmailLimits limits = new EmailLimits(10, 5, 1000, 1000, 100,
                List.of(), List.of(), 10, false);
        ToolDefinition def = EmailTool.readDefinition(c, limits);
        ToolOutcome outcome = def.execute().execute(args(Map.of("uid", JsonValue.of("1"))), null);
        assertThat(text(outcome)).contains("Attachments disabled").doesNotContain("base64");
    }

    @Test
    void sendRejectsRecipientOutsideAllowList() {
        FakeEmailClient c = client();
        EmailLimits limits = new EmailLimits(10, 0, 1000, 1000, 100,
                List.of("ok@example.com"), List.of(), 10, false);
        ToolDefinition def = EmailTool.sendDefinition(c, limits, null);
        ToolOutcome outcome = def.execute().execute(args(Map.of(
                "to", JsonValue.of("bad@example.com"),
                "subject", JsonValue.of("s"),
                "body", JsonValue.of("b"))), null);
        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().toString()).contains("not allowed");
        assertThat(c.sent).isEmpty();
    }

    @Test
    void sendEnforcesMaxRecipients() {
        FakeEmailClient c = client();
        EmailLimits limits = new EmailLimits(1, 0, 1000, 1000, 100,
                List.of(), List.of(), 10, false);
        ToolDefinition def = EmailTool.sendDefinition(c, limits, null);
        ToolOutcome outcome = def.execute().execute(args(Map.of(
                "to", JsonValue.of("a@example.com,b@example.com"),
                "subject", JsonValue.of("s"),
                "body", JsonValue.of("b"))), null);
        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(c.sent).isEmpty();
    }

    @Test
    void sendRejectsAttachmentsWhenDisabled() {
        FakeEmailClient c = client();
        EmailLimits limits = new EmailLimits(10, 5, 1000, 1000, 100,
                List.of(), List.of(), 10, false);
        ToolDefinition def = EmailTool.sendDefinition(c, limits, null);
        ToolOutcome outcome = def.execute().execute(args(Map.of(
                "to", JsonValue.of("a@example.com"),
                "subject", JsonValue.of("s"),
                "body", JsonValue.of("b"),
                "attachments", JsonValue.of(List.of(JsonValue.of("a.txt"))))), null);
        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) outcome).error().toString()).contains("attachments are disabled");
    }

    @Test
    void sendEnforcesBodyLength() {
        FakeEmailClient c = client();
        EmailLimits limits = new EmailLimits(10, 0, 1000, 3, 100,
                List.of(), List.of(), 10, false);
        ToolDefinition def = EmailTool.sendDefinition(c, limits, null);
        ToolOutcome outcome = def.execute().execute(args(Map.of(
                "to", JsonValue.of("a@example.com"),
                "subject", JsonValue.of("s"),
                "body", JsonValue.of("too long"))), null);
        assertThat(outcome).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(c.sent).isEmpty();
    }

    @Test
    void sendIsRateLimited() {
        FakeEmailClient c = client();
        EmailLimits limits = new EmailLimits(10, 0, 1000, 1000, 100,
                List.of(), List.of(), 1, false);
        ToolDefinition def = EmailTool.sendDefinition(c, limits, null);
        JsonValue args = args(Map.of(
                "to", JsonValue.of("a@example.com"),
                "subject", JsonValue.of("s"),
                "body", JsonValue.of("b")));
        assertThat(def.execute().execute(args, null)).isInstanceOf(ToolOutcome.Ok.class);
        ToolOutcome second = def.execute().execute(args, null);
        assertThat(second).isInstanceOf(ToolOutcome.Failed.class);
        assertThat(((ToolOutcome.Failed) second).error().toString()).contains("rate limit");
    }

    @Test
    void sendAcceptsAllowedRecipient() {
        FakeEmailClient c = client();
        EmailLimits limits = new EmailLimits(10, 0, 1000, 1000, 100,
                List.of("a@example.com"), List.of(), 10, false);
        ToolDefinition def = EmailTool.sendDefinition(c, limits, null);
        ToolOutcome outcome = def.execute().execute(args(Map.of(
                "to", JsonValue.of("a@example.com"),
                "subject", JsonValue.of("s"),
                "body", JsonValue.of("b"))), null);
        assertThat(outcome).isInstanceOf(ToolOutcome.Ok.class);
        assertThat(c.sent).hasSize(1);
    }

    static final class FakeEmailClient implements EmailClient {
        final List<Outgoing> sent = new ArrayList<>();

        @Override
        public List<FolderInfo> listFolders() {
            return List.of(new FolderInfo("INBOX", 1, 0));
        }

        @Override
        public List<MessageSummary> listMessages(String folder, int limit, int offset,
                                                 boolean unreadOnly, String search) {
            return List.of(new MessageSummary("1", "a@example.com", "hello", "now", false, false, "snippet"));
        }

        @Override
        public MessageDetail readMessage(String folder, String uid) {
            return new MessageDetail(uid, "a@example.com", List.of("b@example.com"), List.of(),
                    "hi", "now", "text body", "", List.of(new Attachment("a.txt", "text/plain", 3, "YWJj")));
        }

        @Override
        public void send(Outgoing message) {
            sent.add(message);
        }

        @Override
        public void close() {
        }
    }
}