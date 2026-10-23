package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Who the transcript scrolls for. A stream that is writing has to keep the newest line on screen
 * for a reader already at the bottom, and must leave a reader who scrolled up exactly where they
 * are — the wheel used to be missed, so the next flush yanked them back down.
 * <p>
 * The pane is laid out by hand rather than shown in a frame: {@code isNearBottom()} reads the
 * scrollbar's real model, and until the scroll pane is laid out that model is Swing's placeholder
 * (0–100), which says nothing about where the reader is.
 */
class TranscriptPaneScrollTest {

    /** Enough wheel units to clear the pane's stick margin and count as a deliberate scroll. */
    private static final int SCROLL_UP = 12;

    /**
     * A thinking block tall enough to overflow the viewport but short enough to stay unfolded: a
     * folded card stops growing, and growth is what the follow-tail rule is about.
     */
    private static final int LINES = 90;

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    @BeforeEach
    void strict() {
        EDT.setStrict(true);
    }

    @AfterEach
    void relaxed() {
        EDT.setStrict(false);
    }

    private NaruSessionModel model;
    private InteractionHost host;
    private TranscriptPane pane;
    private JScrollPane scroll;
    private Component view;
    private int blocks;

    private void buildPane() throws Exception {
        onEdt(() -> {
            host = new InteractionHost();
            model = new NaruSessionModel(() -> null,
                    NPath.ofTempFolder("naru-desktop-transcript-scroll-" + System.nanoTime()), host);
            pane = new TranscriptPane(model);
            scroll = (JScrollPane) pane.getComponent(1);
            view = scroll.getViewport().getView();
            relayout();
        });
    }

    /**
     * Lay the pane out the way a shown window would. The pane is never displayed, so nothing sizes
     * the card column for it: {@code ScrollPaneLayout} only runs when it is asked to. Two details
     * make the difference between measuring a real transcript and measuring Swing's placeholder
     * scrollbar model (0–100): the view has to be given the height its cards ask for, and the pass
     * has to be repeated until the scrollbar stops moving, because an HTML card reports its height
     * one layout pass late.
     */
    private void relayout() {
        pane.setSize(900, 320);
        int maximum = -1;
        for (int pass = 0; pass < 6 && scroll.getVerticalScrollBar().getMaximum() != maximum; pass++) {
            Dimension pref = view.getPreferredSize();
            view.setSize(Math.max(pref.width, scroll.getViewport().getExtentSize().width), pref.height);
            pane.doLayout();
            scroll.doLayout();
            // The scrollbar's model belongs to the scroll pane's UI, which refreshes it when the
            // viewport reports a different view rectangle. A hand-sized view never fires that, so
            // the extent is poked at its own value: the same thing a window resize does.
            javax.swing.JViewport vp = scroll.getViewport();
            Dimension extent = vp.getExtentSize();
            vp.setExtentSize(new Dimension(extent.width, Math.max(1, extent.height - 1)));
            vp.setExtentSize(extent);
            maximum = scroll.getVerticalScrollBar().getMaximum();
        }
        // A shown scroll pane copies the view's scrollable increment onto the bar; without it the
        // wheel would move one pixel per notch instead of the 16 a reader actually gets, which is
        // not enough to leave the stick margin and count as a deliberate scroll.
        if (view instanceof Scrollable s) {
            scroll.getVerticalScrollBar().setUnitIncrement(
                    s.getScrollableUnitIncrement(scroll.getViewport().getViewRect(),
                            javax.swing.SwingConstants.VERTICAL, 0));
        }
    }

    /** One tall thinking block: unfolded, and far taller than the viewport. */
    private void fillTranscript() throws Exception {
        onEdt(() -> model.onBatch(List.of(new TranscriptEvent.Chunk("t1",
                NaruLogMode.MODEL_THINKING, lines(LINES), false)), host));
        onEdt(this::relayout);
    }

    private int barValue() throws Exception {
        int[] out = new int[1];
        onEdt(() -> out[0] = scroll.getVerticalScrollBar().getValue());
        return out[0];
    }

    private int barMaximum() throws Exception {
        int[] out = new int[1];
        onEdt(() -> out[0] = scroll.getVerticalScrollBar().getMaximum());
        return out[0];
    }

    private int barExtent() throws Exception {
        int[] out = new int[1];
        onEdt(() -> out[0] = scroll.getVerticalScrollBar().getVisibleAmount());
        return out[0];
    }

    private boolean atTail() throws Exception {
        boolean[] out = new boolean[1];
        onEdt(() -> out[0] = pane.isStuckToTail());
        return out[0];
    }

    /**
     * Hand the pane a wheel gesture of {@code units} steps — negative scrolls up. The bar moves by
     * the unit increment per step, exactly as Swing's own wheel handler computes it, and the event
     * then reaches the pane's listeners, which decide whether that reader is still at the tail.
     */
    private void wheel(int units) throws Exception {
        onEdt(() -> {
            JScrollBar bar = scroll.getVerticalScrollBar();
            java.awt.event.MouseWheelEvent event = new java.awt.event.MouseWheelEvent(
                    view, java.awt.event.MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
                    0, 400, 150, 1, false, 0, 0, units);
            // dispatched by hand, so it must be marked as such to reach the scroll pane's UI
            event.setSource(scroll);
            // A shown scroll pane moves the bar through the viewport; an undisplayed one never
            // reaches that handler, so the test moves the bar the way the wheel does first.
            int bottom = bar.getMaximum() - bar.getVisibleAmount();
            int y = Math.max(0, Math.min(bottom, bar.getValue() + units * bar.getUnitIncrement()));
            scroll.getViewport().setViewPosition(new Point(0, y));
            scroll.dispatchEvent(event);
        });
        onEdt(this::relayout);
    }

    /** Scroll down until nothing is left below the viewport, however tall the column turns out. */
    private void wheelToTail() throws Exception {
        int[] unit = new int[1];
        onEdt(() -> unit[0] = Math.max(1, scroll.getVerticalScrollBar().getUnitIncrement()));
        for (int i = 0; i < 6 && !atTail(); i++) {
            int distance = barMaximum() - barExtent() - barValue();
            wheel(Math.max(1, (distance + unit[0] - 1) / unit[0] + 1));
        }
    }

    @Test
    void theTranscriptIsTallerThanTheViewport() throws Exception {
        buildPane();
        fillTranscript();
        assertTrue(barMaximum() > 400, "a 90-line transcript overflows a 320px viewport");
    }

    @Test
    void aReaderAtTheBottomIsCarriedAlongByTheStream() throws Exception {
        buildPane();
        fillTranscript();
        followToTail();
        assertTrue(atTail(), "the pane starts at the tail");
        int before = barValue();
        write(6);
        assertTrue(barValue() > before,
                "a reader at the tail follows the newest line: " + before + " -> " + barValue());
    }

    @Test
    void aReaderWhoScrolledUpStaysPutWhileTheStreamWrites() throws Exception {
        buildPane();
        fillTranscript();
        followToTail();
        assertTrue(atTail(), "starts at the tail");
        int start = barValue();
        wheel(-SCROLL_UP);
        int parked = barValue();
        assertTrue(parked < start, "the wheel moved the view up");
        assertTrue(!atTail(), "the wheel moved the reader away from the tail");
        write(6);
        assertEquals(parked, barValue(),
                "the view must not move for a reader who scrolled away");
        // scrolling back down to the tail hands the stream its reader back
        wheelToTail();
        assertTrue(atTail(), "the reader is at the tail again");
        int bottom = barValue();
        write(6);
        assertTrue(barValue() > bottom, "and the stream pulls the view down with it");
    }

    /** Move to the newest row the way a reader at the tail is moved, and settle the layout. */
    private void followToTail() throws Exception {
        for (int i = 0; i < 10; i++) {
            onEdt(this::relayout);
            onEdt(() -> pane.followTail());
            // demanding the exact bottom, not just isStuckToTail(): before the first real layout the
            // scrollbar still carries Swing's placeholder model, which reads as "at the tail"
            // while the view is actually parked at the top of a column nobody has measured yet.
            if (barValue() + barExtent() >= barMaximum()) {
                return;
            }
        }
        fail("the pane could not be settled at the tail: value=" + barValue()
                + " extent=" + barExtent() + " maximum=" + barMaximum());
    }

    /**
     * Stream the next turns of the conversation. Every one is a new card, so the column grows by a
     * real amount — that growth is what a reader at the tail chases and a scrolled-away reader
     * must not.
     */
    private void write(int turns) throws Exception {
        List<TranscriptEvent> batch = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            batch.add(new TranscriptEvent.Chunk("turn-" + (blocks++),
                    NaruLogMode.MODEL_THINKING, lines(20), false));
        }
        // The layout happens in the same EDT turn as the batch: a shown pane revalidates while the
        // cards are added, so the deferred follow-tail runnable measures the grown column, not the
        // old one.
        onEdt(() -> {
            model.onBatch(batch, host);
            relayout();
        });
    }

    private static String lines(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                b.append('\n');
            }
            b.append("line ").append(i);
        }
        return b.toString();
    }

    private void onEdt(ThrowingRunnable body) throws Exception {
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
