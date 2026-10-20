package net.thevpc.naru.desktop.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The markdown subset, tested for the two things that matter in a transcript: the shapes agents
 * actually produce render, and hostile or odd input cannot inject markup into a Swing cell.
 */
class MarkdownHtmlTest {

    @Test
    void rendersACodeFenceAsPreformattedText() {
        String html = MarkdownHtml.toHtml("```java\nint x = 1;\n```\n");
        // a <pre> would never wrap in Swing, so the fence is a monospace paragraph
        assertTrue(html.contains("<p style='font-family:monospace;margin:2px 0'>int x = 1;<br></p>"), html);
    }

    @Test
    void rendersInlineCodeBoldAndLists() {
        String html = MarkdownHtml.toHtml("Use `naru --task` now.\n- first\n- second\n");
        assertTrue(html.contains("<tt><b>naru --task</b></tt>"), html);
        assertTrue(html.contains("<ul"), html);
        assertTrue(html.contains("<li>first</li>"), html);
        assertTrue(html.contains("<li>second</li>"), html);
    }

    @Test
    void escapesHtmlSoModelOutputCannotFakeMarkup() {
        String html = MarkdownHtml.toHtml("<script>alert(1)</script> and <b>not bold</b>");
        assertFalse(html.contains("<script>"), html);
        // the run may be cut into wrappable spans, but the text that survives escaping is intact
        assertTrue(html.replaceAll("</?span>", "").contains("&lt;script&gt;"), html);
        assertTrue(html.replaceAll("</?span>", "").contains("&lt;b&gt;not bold&lt;/b&gt;"), html);
    }

    @Test
    void headingsAndRules() {
        String html = MarkdownHtml.toHtml("# Title\n\nsome text\n\n---\n");
        assertTrue(html.contains("1.35em"), html);
        assertTrue(html.contains("Title"), html);
        assertTrue(html.contains("<hr>"), html);
    }

    @Test
    void emptyAndNullInputProduceNothing() {
        assertEquals("", MarkdownHtml.toHtml(""));
        assertEquals("", MarkdownHtml.toHtml(null));
    }

    @Test
    void orderedListsBecomeOlItems() {
        String html = MarkdownHtml.toHtml("1. alpha\n2. beta\n");
        assertTrue(html.contains("<ol"), html);
        assertTrue(html.contains("<li>alpha</li>"), html);
    }
}
