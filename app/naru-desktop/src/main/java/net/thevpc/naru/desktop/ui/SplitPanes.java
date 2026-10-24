package net.thevpc.naru.desktop.ui;

import javax.swing.JComponent;
import javax.swing.JSplitPane;
import java.awt.Component;
import java.awt.Dimension;

/**
 * Split panes the user can drag all the way.
 * <p>
 * A divider stops when it reaches the minimum size of the component beside it, so the floor every
 * pane carries decides how far the layout can be tilted. Those floors come from two places: the ones
 * this app set itself — a composer that may not be smaller than 180x96, a task list that may not be
 * smaller than 240x120 — and the look-and-feel's own, which is small but not zero: a scroll pane
 * asks for 16x16 and a text area, which is what most of this window is made of, for 62x21. Either
 * way the divider stops a little short of the end and the pane beside it keeps more room than it was
 * given any part in choosing.
 * <p>
 * So the children of a split built here get an explicit zero floor and can be dragged shut. That
 * needs a way back, which is what the one-touch arrows on the divider are for: a pane collapsed to
 * nothing is still one click from coming whole again.
 */
public final class SplitPanes {

    private SplitPanes() {
    }

    public static JSplitPane horizontal(Component first, Component second) {
        return create(JSplitPane.HORIZONTAL_SPLIT, first, second);
    }

    public static JSplitPane vertical(Component first, Component second) {
        return create(JSplitPane.VERTICAL_SPLIT, first, second);
    }

    private static JSplitPane create(int orientation, Component first, Component second) {
        JSplitPane pane = new JSplitPane(orientation, first, second);
        for (Component c : new Component[]{first, second}) {
            if (c instanceof JComponent component) component.setMinimumSize(new Dimension(0, 0));
        }
        pane.setOneTouchExpandable(true);
        pane.setContinuousLayout(true);
        return pane;
    }
}
