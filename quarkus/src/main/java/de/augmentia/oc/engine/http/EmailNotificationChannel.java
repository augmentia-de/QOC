package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.human.NotificationChannel;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;
import java.util.logging.Logger;

/**
 * Email notification channel for human-in-the-loop approvals. Sends a single-use
 * approval link to the configured recipient list. Falls back to logging the link
 * when SMTP is not configured.
 */
@ApplicationScoped
public class EmailNotificationChannel implements NotificationChannel {

    private static final Logger LOG = Logger.getLogger(EmailNotificationChannel.class.getName());

    @Inject
    @ConfigProperty(name = "engine.approval.email.smtp-host")
    java.util.Optional<String> smtpHost;

    @Inject
    @ConfigProperty(name = "engine.approval.email.smtp-port", defaultValue = "587")
    int smtpPort;

    @Inject
    @ConfigProperty(name = "engine.approval.email.username")
    java.util.Optional<String> username;

    @Inject
    @ConfigProperty(name = "engine.approval.email.password")
    java.util.Optional<String> password;

    @Inject
    @ConfigProperty(name = "engine.approval.email.from")
    java.util.Optional<String> from;

    @Inject
    @ConfigProperty(name = "engine.approval.email.to")
    java.util.Optional<String> to;

    @Inject
    @ConfigProperty(name = "engine.approval.email.starttls", defaultValue = "true")
    boolean starttls;

    @Override
    public void send(PermissionRequestId requestId, SessionId sessionId, String action,
                     String context, String approvalUrl) {
        String smtpHostVal = smtpHost.orElse("");
        String usernameVal = username.orElse("");
        String passwordVal = password.orElse("");
        String fromVal = from.orElse("");
        String toVal = to.orElse("");
        if (smtpHostVal.isBlank() || fromVal.isBlank() || toVal.isBlank()) {
            LOG.warning("Email channel not configured; approval link: " + approvalUrl
                    + " (request=" + requestId.value() + ", session=" + sessionId.value() + ")");
            return;
        }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHostVal);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            props.put("mail.smtp.auth", String.valueOf(!usernameVal.isBlank()));
            props.put("mail.smtp.starttls.enable", String.valueOf(starttls));

            Session session = usernameVal.isBlank()
                    ? Session.getInstance(props)
                    : Session.getInstance(props, new jakarta.mail.Authenticator() {
                        @Override
                        protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                            return new jakarta.mail.PasswordAuthentication(usernameVal, passwordVal);
                        }
                    });

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromVal));
            for (String recipient : toVal.split(",")) {
                String r = recipient.trim();
                if (!r.isBlank()) message.addRecipient(Message.RecipientType.TO, new InternetAddress(r));
            }
            message.setSubject("QOC approval required: " + action);
            message.setText("A QOC agent requires your approval.\n\n"
                    + "Action: " + action + "\n"
                    + "Session: " + sessionId.value() + "\n"
                    + "Context: " + (context == null ? "" : context) + "\n\n"
                    + "Approve: " + approvalUrl + "?decision=approve\n"
                    + "Deny:    " + approvalUrl + "?decision=deny\n\n"
                    + "This link expires in 7 days.");
            Transport.send(message);
            LOG.info("Approval email sent to " + toVal + " for " + requestId.value());
        } catch (Exception e) {
            LOG.warning("Failed to send approval email: " + e.getMessage());
        }
    }
}