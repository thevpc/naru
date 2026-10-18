package net.thevpc.naru.desktop.render;

import net.thevpc.naru.desktop.model.BlockStyle;
import net.thevpc.naru.desktop.model.TranscriptBlock;
import net.thevpc.naru.desktop.model.TranscriptModel;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Renders the display HTML for closed transcript blocks on a background thread and caches the
 * result on the block, then asks the view to repaint the affected rows.
 * <p>
 * This class exists because of a Phase 0 measurement: a per-row HTML cell costs 14.8 s over a
 * 50 000-row load (EDT ping 2.5 s average, 23 s backlog) against 26 ms for a plain-text cell.
 * So rendering is (a) never done on a live block — a streaming row is the plain-text tail,
 * (b) never done on the EDT, (c) done at most once per block, and (d) driven by what the
 * viewport actually shows rather than by every block in history.
 * <p>
 * Markdown blocks go through {@link MarkdownHtml}; the rest get their text escaped into a
 * {@code <pre>} so wrapping works without a second cache field. Either way the cost is paid
 * once, off the EDT.
 * <p>
 * The queue is bounded and lossy on purpose: a block that is skipped simply stays plain text,
 * which is the correct degradation, whereas an unbounded queue behind a 50 000-block transcript
 * would be a memory leak with extra steps.
 */
public final class MarkdownRenderer {

    public interface Callback {
        /** Rows whose cached html changed; called on the EDT. */
        void rowsChanged(List<TranscriptBlock> changed);
    }

    private static final int QUEUE_CAPACITY = 2048;

    private final BlockingQueue<TranscriptBlock> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Set<String> scheduled = Collections.synchronizedSet(new HashSet<>());
    private final Thread worker;
    private volatile boolean running = true;
    private volatile Callback callback;

    public MarkdownRenderer(Callback callback) {
        this.callback = callback;
        this.worker = new Thread(this::loop, "naru-markdown-renderer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /** Ask for {@code block} to be rendered, provided it is closed and its text can change no more. */
    public void request(TranscriptBlock block) {
        if (block == null || !block.isClosed() || block.htmlIsCurrent()) {
            return;
        }
        if (block.length() == 0) {
            block.setHtml("", 0);
            return;
        }
        if (!scheduled.add(block.id())) {
            return;
        }
        if (!queue.offer(block)) {
            scheduled.remove(block.id());
        }
    }

    /** Render everything currently worth rendering that the viewport shows. */
    public void requestVisible(TranscriptModel model, int firstRow, int lastRow) {
        if (lastRow < firstRow) {
            return;
        }
        int size = model.visibleSize();
        for (int row = Math.max(0, firstRow); row <= Math.min(size - 1, lastRow); row++) {
            request(model.visibleAt(row));
        }
    }

    private void loop() {
        while (running) {
            TranscriptBlock b;
            try {
                b = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            int lengthAtRender = b.length();
            String text = b.text();
            String html = BlockStyle.of(b.mode()).markdown()
                    ? MarkdownHtml.toHtml(text)
                    : "<pre>" + escape(text) + "</pre>";
            SwingUtilities.invokeLater(() -> {
                // only publish if the block has not grown since (it cannot: closed blocks are frozen)
                if (b.isClosed() && b.length() == lengthAtRender) {
                    b.setHtml(html, lengthAtRender);
                }
                scheduled.remove(b.id());
                Callback c = callback;
                if (c != null) {
                    c.rowsChanged(List.of(b));
                }
            });
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public void shutdown() {
        running = false;
        worker.interrupt();
    }

    public int pending() {
        return queue.size();
    }
}
