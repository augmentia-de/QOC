package de.augmentia.oc.engine.core.port.world;

import java.util.List;

/**
 * Email client port: fetch, read and send emails via IMAP/SMTP. Implementations
 * live in the adapter layer (e.g. Jakarta Mail).
 */
public interface EmailClient {

    record FolderInfo(String name, int messageCount, int unreadCount) {}

    record MessageSummary(String uid, String from, String subject, String date,
                          boolean read, boolean flagged, String snippet) {}

    record Attachment(String name, String contentType, long size, String base64Data) {}

    record MessageDetail(String uid, String from, List<String> to, List<String> cc,
                         String subject, String date, String textBody, String htmlBody,
                         List<Attachment> attachments) {}

    record Outgoing(String to, List<String> cc, List<String> bcc, String subject,
                    String body, boolean html, List<String> attachmentPaths) {}

    List<FolderInfo> listFolders();

    List<MessageSummary> listMessages(String folder, int limit, int offset, boolean unreadOnly, String search);

    MessageDetail readMessage(String folder, String uid);

    void send(Outgoing message);

    /** Close underlying connections. */
    void close();
}