package de.augmentia.oc.engine.runtime.tool.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Small HTML reader for {@code webfetch}: plain text or a Markdown subset.
 * Script/style/embedded content is removed first. Output is capped so one page
 * cannot fill the context or the tool result.
 */
public final class HtmlMarkdown {

    public static final int MAX_BYTES = 5 * 1024 * 1024;

    private HtmlMarkdown() {}

    public static String toText(String html) {
        return limit(clean(html).text().trim());
    }

    public static String toMarkdown(String html) {
        StringBuilder out = new StringBuilder();
        renderChildren(clean(html).body(), out);
        String normalized = out.toString()
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return limit(normalized);
    }

    private static Document clean(String html) {
        Document document = Jsoup.parse(html == null ? "" : html);
        document.select("script, style, noscript, iframe, object, embed, svg").remove();
        return document;
    }

    private static void renderChildren(Element element, StringBuilder out) {
        for (Node child : element.childNodes()) {
            render(child, out);
        }
    }

    private static void render(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            out.append(text.text());
            return;
        }
        if (!(node instanceof Element element)) {
            for (Node child : node.childNodes()) render(child, out);
            return;
        }
        switch (element.tagName()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                newline(out);
                int level = element.tagName().charAt(1) - '0';
                out.append("#".repeat(level)).append(' ');
                renderChildren(element, out);
                newline(out);
                newline(out);
            }
            case "p", "div", "section", "article", "header", "footer", "main", "aside" -> {
                newline(out);
                renderChildren(element, out);
                newline(out);
                newline(out);
            }
            case "br" -> newline(out);
            case "hr" -> {
                newline(out);
                out.append("---");
                newline(out);
            }
            case "strong", "b" -> wrap(element, out, "**");
            case "em", "i" -> wrap(element, out, "*");
            case "code" -> {
                if (element.parent() != null && "pre".equals(element.parent().tagName())) {
                    renderChildren(element, out);
                } else {
                    out.append('`');
                    renderChildren(element, out);
                    out.append('`');
                }
            }
            case "pre" -> {
                newline(out);
                out.append("```\n");
                renderChildren(element, out);
                newline(out);
                out.append("```");
                newline(out);
                newline(out);
            }
            case "a" -> {
                String href = element.attr("href");
                if (href.isBlank()) {
                    renderChildren(element, out);
                } else {
                    out.append('[');
                    renderChildren(element, out);
                    out.append("](").append(href).append(')');
                }
            }
            case "img" -> {
                String alt = element.attr("alt");
                String src = element.attr("src");
                if (!src.isBlank()) out.append("![").append(alt).append("](").append(src).append(')');
            }
            case "ul", "ol" -> {
                newline(out);
                renderList(element, out);
                newline(out);
            }
            case "li" -> {
                newline(out);
                out.append("- ");
                renderChildren(element, out);
            }
            case "blockquote" -> {
                newline(out);
                out.append("> ");
                renderChildren(element, out);
                newline(out);
            }
            case "table", "thead", "tbody", "tr", "td", "th" -> {
                renderChildren(element, out);
                if ("tr".equals(element.tagName())) newline(out);
                else if ("td".equals(element.tagName()) || "th".equals(element.tagName())) out.append(" | ");
            }
            default -> renderChildren(element, out);
        }
    }

    private static void renderList(Element list, StringBuilder out) {
        boolean ordered = "ol".equals(list.tagName());
        int index = 1;
        for (Element item : list.children()) {
            if (!"li".equals(item.tagName())) continue;
            newline(out);
            out.append(ordered ? index++ + ". " : "- ");
            for (Node child : item.childNodes()) {
                if (child instanceof Element element && ("ul".equals(element.tagName()) || "ol".equals(element.tagName()))) {
                    newline(out);
                    renderList(element, out);
                } else {
                    render(child, out);
                }
            }
        }
    }

    private static void wrap(Element element, StringBuilder out, String marker) {
        out.append(marker);
        renderChildren(element, out);
        out.append(marker);
    }

    private static void newline(StringBuilder out) {
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') out.append('\n');
    }

    private static String limit(String text) {
        if (text == null) return "";
        java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
        if (text.getBytes(utf8).length <= MAX_BYTES) return text;
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (text.substring(0, mid).getBytes(utf8).length <= MAX_BYTES) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, low);
    }
}
