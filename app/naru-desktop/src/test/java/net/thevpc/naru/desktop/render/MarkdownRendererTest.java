package net.thevpc.naru.desktop.render;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.desktop.model.BlockStyle;
import net.thevpc.naru.desktop.model.TranscriptBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rendering path behind plan §6's "markdown for agent answers".
 * <p>
 * {@link MarkdownHtmlTest} covers the parser. This covers the two things only the pipeline can
 * show: that an agent answer is the mode the pipeline renders as markdown — the flag on
 * {@link BlockStyle} is the entire switch, so a test that stops at the parser would pass while the
 * transcript still printed literal asterisks — and that the rules Phase 0 measured are still
 * enforced: a streaming block is never rendered, a closed one is rendered once, off the EDT, and
 * reported back to the view.
 */
class MarkdownRendererTest {

    private MarkdownRenderer renderer;

    @AfterEach
    void stopRenderer() {
        if (renderer != null) {
            renderer.shutdown();
        }
    }

    @Test
    void anAgentAnswerIsTheModeThatRendersAsMarkdown() {
        assertTrue(BlockStyle.of(NaruLogMode.AGENT_RESPONSE).markdown(),
                "§6 promises markdown for agent answers, and this flag is what the renderer reads");
        // the raw model turn is deliberately left verbatim: it is the verbose-only view of the same
        // text, and rendering it twice would be a second place for the parser to disagree with itself
        assertFalse(BlockStyle.of(NaruLogMode.MODEL_RESPONSE).markdown());
        assertFalse(BlockStyle.of(NaruLogMode.SCRIPT).markdown(), "command output is fixed-width text");
        assertFalse(BlockStyle.TOOL.markdown(), "a tool card shows a result, not a document");
    }

    @Test
    void aClosedAgentBlockComesBackAsHtmlNotAsterisks() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        List<TranscriptBlock> changed = new ArrayList<>();
        renderer = new MarkdownRenderer(blocks -> {
            synchronized (changed) {
                changed.addAll(blocks);
            }
            done.countDown();
        });
        TranscriptBlock b = block(NaruLogMode.AGENT_RESPONSE,
                "## Plan\n\nUse `naru --task` to **start** it.\n- first\n- second\n");
        b.close();
        renderer.request(b);
        assertTrue(done.await(15, TimeUnit.SECONDS), "the renderer never answered for a closed block");
        String html = await(b::html);
        assertNotNull(html, "the cache field is what the cell reads");
        assertTrue(html.contains("<b>start</b>"), "bold landed as markup, not as asterisks: " + html);
        assertTrue(html.contains("naru --task"), html);
        assertTrue(html.contains("<li>first</li>"), html);
        assertFalse(html.contains("## Plan"), "the heading marker is gone: " + html);
        synchronized (changed) {
            assertEquals(List.of(b), changed, "the view is told which row changed");
        }
        assertTrue(b.htmlIsCurrent(),
                "a settled block reports itself current, so scrolling past it costs nothing");
    }

    @Test
    void aStreamingBlockIsNeverRenderedAndItsTextIsNotCachedEarly() throws Exception {
        renderer = new MarkdownRenderer(blocks -> {
        });
        TranscriptBlock b = block(NaruLogMode.AGENT_RESPONSE, "half an answer, still arriv");
        renderer.request(b);
        // the queue is polled by a live worker, so "still null" is only meaningful after a wait: the
        // Phase 0 measurement that made the streaming tail plain text is a per-row HTML cost, and a
        // renderer that grabbed open blocks would put that cost back on every keystroke
        Thread.sleep(400);
        assertNull(b.html(), "an open block must not have cached html");
        assertEquals(0, renderer.pending(), "and it must not even sit in the queue");

        b.append("ing…");
        b.close();
        renderer.request(b);
        assertNotNull(await(b::html), "once closed, the same block is rendered");
        assertTrue(b.html().contains("arriving"), b.html());
    }

    @Test
    void aSecondRequestForTheSameBlockIsIgnored() throws Exception {
        renderer = new MarkdownRenderer(blocks -> {
        });
        TranscriptBlock b = block(NaruLogMode.AGENT_RESPONSE, "one answer");
        b.close();
        renderer.request(b);
        assertNotNull(await(b::html));
        String first = b.html();
        renderer.request(b);
        renderer.request(b);
        assertEquals(0, renderer.pending(),
                "an already-rendered block is dropped at the door, not queued and re-parsed");
        assertEquals(first, b.html(), "and the cache keeps the one rendering it made");
    }

    private static TranscriptBlock block(NaruLogMode mode, String text) {
        TranscriptBlock b = new TranscriptBlock("b-" + mode + "-" + System.nanoTime(), mode, 0);
        b.append(text);
        return b;
    }

    /** Poll for the worker's answer; the callback and the cache are written on the EDT. */
    private static String await(java.util.function.Supplier<String> getter) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        String v = null;
        while (until > System.currentTimeMillis()) {
            v = getter.get();
            if (v != null) {
                return v;
            }
            Thread.sleep(25);
        }
        return v;
    }
}
