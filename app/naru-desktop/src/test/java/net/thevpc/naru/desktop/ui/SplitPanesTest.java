package net.thevpc.naru.desktop.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A divider that stops short is worse than a divider that is missing, and what stops it is the
 * minimum size of the panes beside it — the floors this app pinned on its own composers and task
 * lists, and the smaller ones the look-and-feel hands every panel and scroll pane.
 * <p>
 * How far a pressed divider may travel is the range the pane reports for it, so that is what is
 * compared here, always against a pane built the same way with no floor at all: the numbers a range
 * answers with belong to the look-and-feel, and the tests should not repeat them. Where a drag ends
 * up once the range is used is a layout question that needs a window, and the live harness checks it.
 */
class SplitPanesTest {

    /** The width a floored pane asks for, and the width its divider costs a drag. */
    private static final int FLOOR = 240;

    /** How wide the pane under test is laid out. */
    private static final int WIDTH = 1000;

    @Test
    void anExplicitFloorHoldsTheDividerBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel pane = floored();
            int floored = laid(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JPanel(), pane))
                    .getMaximumDividerLocation();
            int free = freeRange();
            assertEquals(free - FLOOR, floored,
                    "the divider may not pass the floor beside it, so a drag loses exactly that width");
        });
    }

    @Test
    void zeroingTheFloorLetsTheDividerReachTheEnd() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel pane = floored();
            JSplitPane split = laid(SplitPanes.horizontal(new JPanel(), pane));
            assertEquals(new Dimension(0, 0), pane.getMinimumSize(),
                    "the floor the component arrived with has to go, not just the one on the split pane");
            assertEquals(freeRange(), split.getMaximumDividerLocation(),
                    "and the drag then reaches as far as it would have with a pane that was never floored");
            assertTrue(split.isOneTouchExpandable(),
                    "a pane that can be dragged shut needs a visible way back");
            assertTrue(split.isContinuousLayout(),
                    "the drag should show the new layout, not just a moving bar");
        });
    }

    /**
     * The look-and-feel floors components nobody thought about, so a plain scroll pane holds a
     * divider back all by itself.
     */
    @Test
    void theLookAndFeelFloorIsGoneToo() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JScrollPane scroll = new JScrollPane(new JTextPane());
            assertTrue(scroll.getMinimumSize().width > 0,
                    "the delegate gives a scroll pane a non-zero minimum; the premise needs checking");
            int held = laid(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JPanel(), scroll))
                    .getMaximumDividerLocation();
            int free = laid(SplitPanes.horizontal(new JPanel(), new JScrollPane(new JTextPane())))
                    .getMaximumDividerLocation();
            assertTrue(free > held, "that floor also stops the divider; it held it at " + held
                    + " and the fixed pane reaches " + free);
        });
    }

    @Test
    void theFileListStacksOverTheDiff() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DiffPanel diffs = new DiffPanel();
            JSplitPane stack = (JSplitPane) firstOf(diffs, JSplitPane.class);
            assertNotNull(stack, "the list is stacked over the diff by a split pane");
            assertEquals(JSplitPane.VERTICAL_SPLIT, stack.getOrientation(),
                    "three columns across a third of the window is what this layout had to stop being");
        });
    }

    /** The farthest a drag may go in a pane that was laid out at {@link #WIDTH} with nothing pinned. */
    private static int freeRange() {
        JPanel first = new JPanel();
        JPanel second = new JPanel();
        first.setMinimumSize(new Dimension(0, 0));
        second.setMinimumSize(new Dimension(0, 0));
        return laid(new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, first, second)).getMaximumDividerLocation();
    }

    /** A split pane only answers where a divider may go once it has a size and a layout pass. */
    private static JSplitPane laid(JSplitPane pane) {
        pane.setSize(WIDTH, 600);
        pane.doLayout();
        return pane;
    }

    /** A pane with the kind of floor the app used to pin on its own components. */
    private static JPanel floored() {
        JPanel p = new JPanel();
        p.setMinimumSize(new Dimension(FLOOR, 120));
        return p;
    }

    private static Component firstOf(Container c, Class<?> type) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k)) return k;
            if (k instanceof Container container) {
                Component hit = firstOf(container, type);
                if (hit != null) return hit;
            }
        }
        return null;
    }
}
