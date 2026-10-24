package de.augmentia.oc.engine.runtime;

import de.augmentia.oc.engine.runtime.tool.web.HtmlMarkdown;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlMarkdownTest {

    @Test
    void rendersHeadingsParagraphsAndInlineFormatting() {
        String markdown = HtmlMarkdown.toMarkdown("""
                <h1>One</h1><h2>Two</h2><h3>Three</h3>
                <p>Hello <strong>bold</strong> and <em>italic</em> and <code>code</code>.</p>
                <hr><br>""");

        assertThat(markdown).contains("# One").contains("## Two").contains("### Three");
        assertThat(markdown).contains("**bold**").contains("*italic*").contains("`code`");
        assertThat(markdown).contains("---");
    }

    @Test
    void rendersLinksImagesAndCodeBlocks() {
        String markdown = HtmlMarkdown.toMarkdown("""
                <p><a href="https://example.com">link</a>
                <a>plain</a>
                <img alt="pic" src="https://example.com/a.png"></p>
                <pre><code>line1
                line2</code></pre>""");

        assertThat(markdown).contains("[link](https://example.com)");
        assertThat(markdown).contains("plain");
        assertThat(markdown).contains("![pic](https://example.com/a.png)");
        assertThat(markdown).contains("```");
        assertThat(markdown).contains("line1");
    }

    @Test
    void rendersOrderedListsNestedListsAndQuotes() {
        String markdown = HtmlMarkdown.toMarkdown("""
                <ol><li>first</li><li>second<ul><li>nested</li></ul></li></ol>
                <blockquote>quote</blockquote>""");

        assertThat(markdown).contains("1. first").contains("2. second").contains("- nested");
        assertThat(markdown).contains("> quote");
    }

    @Test
    void rendersTablesAsTextCells() {
        String markdown = HtmlMarkdown.toMarkdown(
                "<table><tr><td>a</td><td>b</td></tr><tr><td>c</td><td>d</td></tr></table>");

        assertThat(markdown).contains("a").contains("b").contains("c").contains("d");
    }

    @Test
    void textFormatRemovesMarkupAndWhitespace() {
        assertThat(HtmlMarkdown.toText("<p>Hello   <b>world</b></p>")).isEqualTo("Hello world");
    }

    @Test
    void emptyInputIsEmpty() {
        assertThat(HtmlMarkdown.toMarkdown("")).isEmpty();
        assertThat(HtmlMarkdown.toText(null)).isEmpty();
    }

    @Test
    void outputIsBounded() {
        String huge = "<p>" + "x".repeat(HtmlMarkdown.MAX_BYTES + 1024) + "</p>";
        assertThat(HtmlMarkdown.toMarkdown(huge).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(HtmlMarkdown.MAX_BYTES);
    }
}
