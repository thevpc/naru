package net.thevpc.naru.desktop.render;

import net.thevpc.naru.desktop.ui.Typefaces;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plain text to HTML conversion. The point of every rule here is that Swing's HTML engine
 * refuses to break a {@code <pre>} line, and refuses to lay a paragraph out narrower than its
 * longest unbreakable run, so anything that must stay inside the message column has to be a
 * normal paragraph whose runs are all short enough to wrap.
 */
class TextHtmlTest {

    private static final Pattern SPAN = Pattern.compile("<span>(.*?)</span>");

    @Test
    void lineBreaksSurviveAsBrInsideOneParagraph() {
        String html = TextHtml.paragraphs("one\ntwo\n\nthree");
        assertTrue(html.startsWith("<p "), html);
        assertTrue(html.endsWith("</p>"), html);
        assertEquals(3, count(html, "<br>"), html);
        assertTrue(html.contains("one<br>two"), html);
        assertTrue(html.contains("two<br><br>three"), html);
    }

    @Test
    void leadingIndentationUsesNonCollapsingSpaces() {
        String html = TextHtml.paragraphs("    indented");
        assertTrue(html.contains("&nbsp;&nbsp;&nbsp;&nbsp;indented"), html);
    }

    @Test
    void longTokensAreSplitIntoWrappableSpans() {
        String url = "https://example.com/a/very/long/path/that/never/ends/anywhere/at/all";
        String html = TextHtml.paragraphs(url);
        assertTrue(html.contains("<span>"), html);
        // the spans are invisible: the text itself must read back unchanged
        assertEquals(url, plain(html));
        // and no single run can outlive the column the card gives the message
        Matcher spans = SPAN.matcher(html);
        int runs = 0;
        while (spans.find()) {
            runs++;
            assertTrue(spans.group(1).length() <= 8, spans.group(1));
        }
        assertTrue(runs >= 4, "a long token must be cut into several runs, got " + runs);
    }

    @Test
    void shortTokensStayIntact() {
        assertFalse(TextHtml.paragraphs("see https://x.y/z now").contains("<span>"));
    }

    @Test
    void hostileTextIsEscaped() {
        String html = TextHtml.paragraphs("<script>alert(1)</script>");
        assertFalse(html.contains("<script>"), html);
        assertTrue(plain(html).contains("&lt;script&gt;"), html);
    }

    @Test
    void codeBlockIsTheSameShapeInTheBundledMonospaceFace() {
        assertEquals("<p style='font-family:" + Typefaces.MONO + ";margin:2px 0'>a</p>",
                TextHtml.codeBlock("a"));
    }

    @Test
    void nullAndEmptyTextStillProduceAParagraph() {
        assertEquals("<p style='margin:0'></p>", TextHtml.paragraphs(null));
        assertEquals("<p style='margin:0'></p>", TextHtml.paragraphs(""));
        assertEquals("", TextHtml.escapeWrap(null));
    }

    /** The text a reader sees: no markup, no wrapping helpers. */
    private static String plain(String html) {
        String body = html.substring(html.indexOf('>') + 1, html.lastIndexOf("</p>"));
        return body.replaceAll("</?span>", "").replace("<br>", "\n");
    }

    private static int count(String text, String token) {
        int n = 0;
        for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) {
            n++;
        }
        return n;
    }
}
