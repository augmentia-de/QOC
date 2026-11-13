package de.augmentia.oc.engine.http;

import de.augmentia.qoc.workflowrunner.model.WorkflowDefinition.Agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort exporter: writes a {@link WorkflowDefinition.Agent} (SSOT JSON)
 * back to an OpenCode-compatible Markdown file. Lossy by design — the store
 * is canonical; Markdown is a projection for OpenCode tooling.
 */
public final class AgentMarkdownExporter {

    private AgentMarkdownExporter() {}

    public static String toMarkdown(Agent a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(quote(a.name())).append("\n");
        sb.append("mode: ").append(quote(a.mode() == null || a.mode().isBlank() ? "all" : a.mode())).append("\n");
        if (a.model() != null && !a.model().isBlank()) {
            sb.append("model: ").append(quote(a.model())).append("\n");
        }
        sb.append("hidden: ").append(!a.active()).append("\n");
        if (a.rolesAllowed() != null && !a.rolesAllowed().isEmpty()) {
            sb.append("rolesAllowed:\n");
            for (String r : a.rolesAllowed()) sb.append("  - ").append(quote(r)).append("\n");
        }
        if (a.tools() != null && !a.tools().isEmpty()) {
            sb.append("toolAllow:\n");
            for (String t : a.tools()) sb.append("  - ").append(quote(t)).append("\n");
        }
        sb.append("---\n\n");
        String body = a.systemPrompt() == null ? "" : a.systemPrompt().trim();
        if (!body.isEmpty()) sb.append(body).append("\n");
        return sb.toString();
    }

    public static void write(Path dir, Agent a) {
        if (dir == null || a == null) return;
        try {
            Files.createDirectories(dir);
            String slug = (a.id() == null || a.id().isBlank())
                    ? a.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-")
                    : a.id();
            if (slug.isBlank()) slug = "agent";
            Path file = dir.resolve(slug + ".md");
            Files.writeString(file, toMarkdown(a), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            // best-effort; never fail the request
        }
    }

    private static String quote(String v) {
        if (v == null) return "\"\"";
        if (v.contains(":") || v.contains("#") || v.contains("\"")) {
            return "\"" + v.replace("\"", "\\\"") + "\"";
        }
        return v;
    }
}
