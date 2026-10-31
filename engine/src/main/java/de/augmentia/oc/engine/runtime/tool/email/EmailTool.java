package de.augmentia.oc.engine.runtime.tool.email;

import de.augmentia.oc.engine.core.contract.tool.ToolDefinition;
import de.augmentia.oc.engine.core.contract.tool.ToolOptions;
import de.augmentia.oc.engine.core.contract.tool.ToolOutcome;
import de.augmentia.oc.engine.core.contract.tool.ToolResult;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ToolContent;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.core.port.world.EmailClient;
import de.augmentia.oc.engine.runtime.tool.JsonArgs;
import de.augmentia.oc.engine.runtime.tool.fs.ReadTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class EmailTool {

    private EmailTool() {}

    public static ToolDefinition fetchDefinition(EmailClient client) {
        if (client == null) throw new IllegalArgumentException("client must not be null");
        Schema input = Schema.obj(Map.of(
                "folder", Schema.opt(Schema.str()),
                "limit", Schema.opt(Schema.intg()),
                "offset", Schema.opt(Schema.intg()),
                "unreadOnly", Schema.opt(Schema.bool()),
                "search", Schema.opt(Schema.str())));
        return new ToolDefinition(new ToolName("email_fetch"),
                "List emails in a folder (IMAP)",
                input, Option.none(),
                (value, context) -> fetch(client, value), null);
    }

    public static ToolDefinition readDefinition(EmailClient client) {
        return readDefinition(client, EmailLimits.defaults());
    }

    public static ToolDefinition readDefinition(EmailClient client, EmailLimits limits) {
        if (client == null) throw new IllegalArgumentException("client must not be null");
        EmailLimits effective = limits == null ? EmailLimits.defaults() : limits;
        Schema input = Schema.obj(Map.of(
                "folder", Schema.opt(Schema.str()),
                "uid", Schema.str()));
        return new ToolDefinition(new ToolName("email_read"),
                "Read a single email by UID (IMAP)",
                input, Option.none(),
                (value, context) -> read(client, value, effective), null);
    }

    public static ToolDefinition sendDefinition(EmailClient client) {
        return sendDefinition(client, EmailLimits.defaults(), null);
    }

    public static ToolDefinition sendDefinition(EmailClient client, EmailLimits limits, Path attachmentRoot) {
        if (client == null) throw new IllegalArgumentException("client must not be null");
        EmailLimits effective = limits == null ? EmailLimits.defaults() : limits;
        RateLimiter limiter = new RateLimiter(effective.maxPerHour());
        Schema input = Schema.obj(Map.of(
                "to", Schema.str(),
                "cc", Schema.opt(Schema.str()),
                "bcc", Schema.opt(Schema.str()),
                "subject", Schema.str(),
                "body", Schema.str(),
                "html", Schema.opt(Schema.bool()),
                "attachments", Schema.opt(Schema.arr(Schema.str()))));
        return new ToolDefinition(new ToolName("email_send"),
                "Send an email (SMTP)",
                input, Option.none(),
                (value, context) -> send(client, value, effective, attachmentRoot, limiter),
                new ToolOptions(Option.none(), Option.of("email_send"), false, false, false)
                        .withApprovalMessage(value -> {
                            String to = JsonArgs.string(value, "to");
                            String subject = JsonArgs.string(value, "subject");
                            return "Send email to " + (to == null ? "?" : to)
                                    + (subject == null || subject.isBlank() ? "" : " — Subject: " + subject);
                        }));
    }

    private static ToolOutcome fetch(EmailClient client, JsonValue value) {
        try {
            String folder = JsonArgs.string(value, "folder");
            if (folder == null || folder.isBlank()) folder = "INBOX";
            int limit = JsonArgs.intOr(value, "limit", 20);
            int offset = JsonArgs.intOr(value, "offset", 0);
            boolean unreadOnly = JsonArgs.boolOr(value, "unreadOnly", false);
            String search = JsonArgs.string(value, "search");

            List<EmailClient.MessageSummary> msgs = client.listMessages(folder, limit, offset, unreadOnly, search);
            StringBuilder sb = new StringBuilder();
            sb.append("Folder: ").append(folder).append(" (").append(msgs.size()).append(" messages)\n\n");
            for (EmailClient.MessageSummary m : msgs) {
                sb.append("UID: ").append(m.uid()).append("\n");
                sb.append("From: ").append(m.from()).append("\n");
                sb.append("Subject: ").append(m.subject()).append("\n");
                sb.append("Date: ").append(m.date()).append("\n");
                sb.append("Flags: ").append(m.read() ? "read" : "unread")
                        .append(m.flagged() ? " flagged" : "").append("\n");
                if (m.snippet() != null && !m.snippet().isBlank()) {
                    sb.append("Snippet: ").append(m.snippet()).append("\n");
                }
                sb.append("---\n");
            }
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(sb.toString().trim())), Map.of()));
        } catch (Exception e) {
            return ReadTool.failure("email_fetch", e.getMessage());
        }
    }

    private static ToolOutcome read(EmailClient client, JsonValue value, EmailLimits limits) {
        try {
            String folder = JsonArgs.string(value, "folder");
            if (folder == null || folder.isBlank()) folder = "INBOX";
            String uid = JsonArgs.requiredString(value, "uid");

            EmailClient.MessageDetail m = client.readMessage(folder, uid);
            StringBuilder sb = new StringBuilder();
            sb.append("UID: ").append(m.uid()).append("\n");
            sb.append("From: ").append(m.from()).append("\n");
            sb.append("To: ").append(String.join(", ", m.to())).append("\n");
            if (!m.cc().isEmpty()) sb.append("Cc: ").append(String.join(", ", m.cc())).append("\n");
            sb.append("Subject: ").append(m.subject()).append("\n");
            sb.append("Date: ").append(m.date()).append("\n");
            if (m.textBody() != null && !m.textBody().isBlank()) {
                sb.append("\n--- Body (text) ---\n").append(m.textBody());
            }
            if (m.htmlBody() != null && !m.htmlBody().isBlank()) {
                sb.append("\n--- Body (html) ---\n").append(m.htmlBody());
            }
            if (limits.attachmentsEnabled() && !m.attachments().isEmpty()) {
                sb.append("\n--- Attachments (").append(m.attachments().size()).append(") ---\n");
                for (EmailClient.Attachment a : m.attachments()) {
                    sb.append("- ").append(a.name()).append(" (").append(a.contentType())
                            .append(", ").append(a.size()).append(" bytes)\n");
                    if (a.base64Data() != null && !a.base64Data().isBlank()) {
                        sb.append("  base64: ").append(a.base64Data()).append("\n");
                    }
                }
            } else if (!limits.attachmentsEnabled() && !m.attachments().isEmpty()) {
                sb.append("\n--- Attachments disabled (").append(m.attachments().size()).append(") ---\n");
            }
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem(sb.toString().trim())), Map.of()));
        } catch (Exception e) {
            return ReadTool.failure("email_read", e.getMessage());
        }
    }

    private static ToolOutcome send(EmailClient client, JsonValue value, EmailLimits limits,
                                    Path attachmentRoot, RateLimiter limiter) {
        try {
            String to = JsonArgs.requiredString(value, "to");
            String subject = JsonArgs.requiredString(value, "subject");
            String body = JsonArgs.requiredString(value, "body");
            String cc = JsonArgs.string(value, "cc");
            String bcc = JsonArgs.string(value, "bcc");
            boolean html = JsonArgs.boolOr(value, "html", false);

            List<String> toList = split(to);
            List<String> ccList = split(cc);
            List<String> bccList = split(bcc);
            int recipients = toList.size() + ccList.size() + bccList.size();
            if (toList.isEmpty()) return ReadTool.failure("email_send", "at least one recipient is required");
            if (recipients > limits.maxRecipients()) {
                return ReadTool.failure("email_send", "too many recipients (max " + limits.maxRecipients() + ")");
            }
            for (String address : concat(toList, ccList, bccList)) {
                if (!limits.allows(address)) {
                    return ReadTool.failure("email_send", "recipient not allowed: " + address);
                }
            }
            if (subject.length() > limits.maxSubjectChars()) {
                return ReadTool.failure("email_send", "subject too long (max " + limits.maxSubjectChars() + ")");
            }
            if (body.length() > limits.maxBodyChars()) {
                return ReadTool.failure("email_send", "body too long (max " + limits.maxBodyChars() + ")");
            }

            List<String> attachments = new ArrayList<>();
            if (JsonArgs.field(value, "attachments") instanceof JsonValue.Arr arr) {
                for (JsonValue item : arr.values()) {
                    if (item instanceof JsonValue.Str s && !s.value().isBlank()) attachments.add(s.value());
                }
            }
            if (!attachments.isEmpty() && !limits.attachmentsEnabled()) {
                return ReadTool.failure("email_send", "attachments are disabled");
            }
            if (attachments.size() > limits.maxAttachments()) {
                return ReadTool.failure("email_send", "too many attachments (max " + limits.maxAttachments() + ")");
            }
            for (String path : attachments) {
                Path resolved = attachmentRoot == null ? Path.of(path) : attachmentRoot.resolve(path).normalize();
                if (attachmentRoot != null && !resolved.startsWith(attachmentRoot.toAbsolutePath().normalize())) {
                    return ReadTool.failure("email_send", "attachment outside workspace: " + path);
                }
                if (!Files.exists(resolved)) {
                    return ReadTool.failure("email_send", "attachment not found: " + path);
                }
                long size = Files.size(resolved);
                if (size > limits.maxAttachmentBytes()) {
                    return ReadTool.failure("email_send", "attachment too large: " + path);
                }
            }

            if (!limiter.tryAcquire()) {
                return ReadTool.failure("email_send", "rate limit reached (max " + limits.maxPerHour() + " per hour)");
            }

            EmailClient.Outgoing outgoing = new EmailClient.Outgoing(
                    to, ccList, bccList, subject, body, html, attachments);
            client.send(outgoing);
            return new ToolOutcome.Ok(new ToolResult(Option.none(),
                    List.of(new ToolContent.TextItem("Email sent to " + to)), Map.of()));
        } catch (Exception e) {
            return ReadTool.failure("email_send", e.getMessage());
        }
    }

    private static List<String> split(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    private static List<String> concat(List<String>... lists) {
        List<String> out = new ArrayList<>();
        for (List<String> list : lists) out.addAll(list);
        return out;
    }

    /** Simple in-process hourly rate limiter. */
    private static final class RateLimiter {
        private final int max;
        private final AtomicLong windowStart = new AtomicLong(0);
        private final AtomicInteger count = new AtomicInteger(0);

        RateLimiter(int max) {
            this.max = max;
        }

        boolean tryAcquire() {
            if (max <= 0) return true;
            long now = System.currentTimeMillis();
            long start = windowStart.get();
            if (start == 0 || now - start >= 3_600_000L) {
                if (windowStart.compareAndSet(start, now)) {
                    count.set(0);
                }
            }
            return count.incrementAndGet() <= max;
        }
    }
}