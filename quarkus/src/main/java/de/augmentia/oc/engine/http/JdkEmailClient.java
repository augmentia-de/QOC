package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.port.world.EmailClient;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.search.FlagTerm;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/**
 * Jakarta Mail based email client (IMAP for fetch/read, SMTP for send).
 */
public final class JdkEmailClient implements EmailClient {

    private final String imapHost;
    private final int imapPort;
    private final boolean imapSsl;
    private final boolean imapStarttls;
    private final String imapUser;
    private final String imapPassword;

    private final String smtpHost;
    private final int smtpPort;
    private final boolean smtpStarttls;
    private final String smtpUser;
    private final String smtpPassword;

    public JdkEmailClient(String imapHost, int imapPort, boolean imapSsl, boolean imapStarttls, String imapUser, String imapPassword,
                          String smtpHost, int smtpPort, boolean smtpStarttls, String smtpUser, String smtpPassword) {
        this.imapHost = imapHost;
        this.imapPort = imapPort;
        this.imapSsl = imapSsl;
        this.imapStarttls = imapStarttls;
        this.imapUser = imapUser;
        this.imapPassword = imapPassword;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpStarttls = smtpStarttls;
        this.smtpUser = smtpUser;
        this.smtpPassword = smtpPassword;
    }

    private Store connectImap() throws MessagingException {
        String proto = imapSsl ? "imaps" : "imap";
        Properties props = new Properties();
        props.put("mail.store.protocol", proto);
        props.put("mail." + proto + ".host", imapHost);
        props.put("mail." + proto + ".port", String.valueOf(imapPort));
        if (!imapSsl && imapStarttls) {
            props.put("mail." + proto + ".starttls.enable", "true");
        }
        Session session = Session.getInstance(props);
        Store store = session.getStore(imapSsl ? "imaps" : "imap");
        store.connect(imapHost, imapPort, imapUser, imapPassword);
        return store;
    }

    @Override
    public List<FolderInfo> listFolders() {
        List<FolderInfo> out = new ArrayList<>();
        try (Store store = connectImap()) {
            Folder defaultFolder = store.getDefaultFolder();
            for (Folder f : defaultFolder.list("*")) {
                f.open(Folder.READ_ONLY);
                int total = f.getMessageCount();
                int unread = f.getUnreadMessageCount();
                out.add(new FolderInfo(f.getFullName(), total, unread));
                f.close(false);
            }
        } catch (MessagingException e) {
            throw new RuntimeException("IMAP listFolders failed: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public List<MessageSummary> listMessages(String folder, int limit, int offset, boolean unreadOnly, String search) {
        List<MessageSummary> out = new ArrayList<>();
        try (Store store = connectImap()) {
            Folder f = store.getFolder(folder == null || folder.isBlank() ? "INBOX" : folder);
            f.open(Folder.READ_ONLY);
            int total = f.getMessageCount();
            int start = Math.max(1, total - offset - limit + 1);
            int end = Math.max(1, total - offset);
            if (start > end) start = end;
            Message[] msgs = f.getMessages(start, end);
            for (int i = msgs.length - 1; i >= 0; i--) {
                Message m = msgs[i];
                if (unreadOnly && m.isSet(Flags.Flag.SEEN)) continue;
                String uid = String.valueOf(m.getMessageNumber());
                String from = m.getFrom() != null && m.getFrom().length > 0 ? m.getFrom()[0].toString() : "";
                String subject = m.getSubject();
                String date = m.getSentDate() != null ? m.getSentDate().toString() : "";
                boolean read = m.isSet(Flags.Flag.SEEN);
                boolean flagged = m.isSet(Flags.Flag.FLAGGED);
                String snippet = "";
                try {
                    Object content = m.getContent();
                    if (content instanceof String s) snippet = s.length() > 120 ? s.substring(0, 120) : s;
                } catch (Exception ignored) {}
                if (search != null && !search.isBlank()) {
                    String hay = (from + " " + subject + " " + snippet).toLowerCase();
                    if (!hay.contains(search.toLowerCase())) continue;
                }
                out.add(new MessageSummary(uid, from, subject, date, read, flagged, snippet));
            }
            f.close(false);
        } catch (MessagingException e) {
            throw new RuntimeException("IMAP listMessages failed: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public MessageDetail readMessage(String folder, String uid) {
        try (Store store = connectImap()) {
            Folder f = store.getFolder(folder == null || folder.isBlank() ? "INBOX" : folder);
            f.open(Folder.READ_ONLY);
            int num = Integer.parseInt(uid);
            Message m = f.getMessage(num);
            m.setFlag(Flags.Flag.SEEN, true);
            String from = m.getFrom() != null && m.getFrom().length > 0 ? m.getFrom()[0].toString() : "";
            List<String> to = addresses(m.getRecipients(Message.RecipientType.TO));
            List<String> cc = addresses(m.getRecipients(Message.RecipientType.CC));
            String subject = m.getSubject();
            String date = m.getSentDate() != null ? m.getSentDate().toString() : "";
            String textBody = "";
            String htmlBody = "";
            List<Attachment> attachments = new ArrayList<>();
            Object content = m.getContent();
            if (content instanceof String s) {
                textBody = s;
            } else if (content instanceof Multipart mp) {
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart bp = mp.getBodyPart(i);
                    String disp = bp.getDisposition();
                    if (disp != null && (disp.equalsIgnoreCase(Part.ATTACHMENT) || disp.equalsIgnoreCase(Part.INLINE))) {
                        String name = bp.getFileName();
                        byte[] bytes = bp.getInputStream().readAllBytes();
                        attachments.add(new Attachment(name, bp.getContentType(), bytes.length,
                                Base64.getEncoder().encodeToString(bytes)));
                    } else if (bp.isMimeType("text/plain")) {
                        textBody = bp.getContent().toString();
                    } else if (bp.isMimeType("text/html")) {
                        htmlBody = bp.getContent().toString();
                    } else {
                        // nested multipart
                        if (bp.getContent() instanceof Multipart nmp) {
                            for (int j = 0; j < nmp.getCount(); j++) {
                                BodyPart nbp = nmp.getBodyPart(j);
                                if (nbp.isMimeType("text/plain")) textBody = nbp.getContent().toString();
                                else if (nbp.isMimeType("text/html")) htmlBody = nbp.getContent().toString();
                            }
                        }
                    }
                }
            }
            f.close(false);
            return new MessageDetail(uid, from, to, cc, subject, date, textBody, htmlBody, attachments);
        } catch (Exception e) {
            throw new RuntimeException("IMAP readMessage failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void send(Outgoing message) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            props.put("mail.smtp.auth", String.valueOf(!smtpUser.isBlank()));
            props.put("mail.smtp.starttls.enable", String.valueOf(smtpStarttls));
            Session session = smtpUser.isBlank()
                    ? Session.getInstance(props)
                    : Session.getInstance(props, new jakarta.mail.Authenticator() {
                        @Override
                        protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                            return new jakarta.mail.PasswordAuthentication(smtpUser, smtpPassword);
                        }
                    });
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(smtpUser.isBlank() ? "noreply@localhost" : smtpUser));
            for (String r : split(message.to())) msg.addRecipient(Message.RecipientType.TO, new InternetAddress(r));
            for (String r : message.cc()) msg.addRecipient(Message.RecipientType.CC, new InternetAddress(r));
            for (String r : message.bcc()) msg.addRecipient(Message.RecipientType.BCC, new InternetAddress(r));
            msg.setSubject(message.subject());
            if (message.attachmentPaths() != null && !message.attachmentPaths().isEmpty()) {
                Multipart multipart = new MimeMultipart();
                BodyPart textPart = new MimeBodyPart();
                if (message.html()) textPart.setContent(message.body(), "text/html; charset=utf-8");
                else textPart.setText(message.body());
                multipart.addBodyPart(textPart);
                for (String path : message.attachmentPaths()) {
                    MimeBodyPart attach = new MimeBodyPart();
                    attach.attachFile(path);
                    multipart.addBodyPart(attach);
                }
                msg.setContent(multipart);
            } else {
                if (message.html()) msg.setContent(message.body(), "text/html; charset=utf-8");
                else msg.setText(message.body());
            }
            Transport.send(msg);
        } catch (Exception e) {
            throw new RuntimeException("SMTP send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        // connections are opened/closed per operation
    }

    private List<String> addresses(Address[] arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (Address a : arr) out.add(a.toString());
        return out;
    }

    private List<String> split(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            String t = p.trim();
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }
}