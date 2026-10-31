package de.augmentia.oc.engine.runtime.tool.email;

import java.util.List;

/**
 * Guardrails for the email tool. Limits are intentionally conservative and
 * configurable; an empty allow-list means "no allow-list restriction".
 */
public record EmailLimits(
        int maxRecipients,
        int maxAttachments,
        long maxAttachmentBytes,
        int maxBodyChars,
        int maxSubjectChars,
        List<String> allowedRecipients,
        List<String> deniedRecipients,
        int maxPerHour,
        boolean attachmentsEnabled) {

    public EmailLimits {
        if (maxRecipients <= 0) maxRecipients = 10;
        if (maxAttachments < 0) maxAttachments = 5;
        if (maxAttachmentBytes <= 0) maxAttachmentBytes = 10L * 1024 * 1024;
        if (maxBodyChars <= 0) maxBodyChars = 50_000;
        if (maxSubjectChars <= 0) maxSubjectChars = 200;
        allowedRecipients = allowedRecipients == null ? List.of() : List.copyOf(allowedRecipients);
        deniedRecipients = deniedRecipients == null ? List.of() : List.copyOf(deniedRecipients);
        if (maxPerHour < 0) maxPerHour = 20;
    }

    public static EmailLimits defaults() {
        return new EmailLimits(10, 5, 10L * 1024 * 1024, 50_000, 200, List.of(), List.of(), 20, true);
    }

    public boolean allows(String address) {
        if (address == null || address.isBlank()) return false;
        String value = address.trim().toLowerCase();
        for (String denied : deniedRecipients) {
            if (matches(denied, value)) return false;
        }
        if (allowedRecipients.isEmpty()) return true;
        for (String allowed : allowedRecipients) {
            if (matches(allowed, value)) return true;
        }
        return false;
    }

    private static boolean matches(String pattern, String value) {
        if (pattern == null) return false;
        String p = pattern.trim().toLowerCase();
        if (p.isEmpty()) return false;
        if (p.equals("*")) return true;
        StringBuilder regex = new StringBuilder("^");
        for (char c : p.toCharArray()) {
            if (c == '*') {
                regex.append(".*");
            } else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append('$');
        return value.matches(regex.toString());
    }
}