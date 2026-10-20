package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.model.NaruMessagePart;

import javax.swing.JComponent;
import java.util.function.Consumer;

/** Renders one typed message part as a real Swing component. */
public interface ChatPartRenderer {
    String type();

    JComponent render(NaruMessagePart part, Consumer<String> actionHandler);

    /**
     * True when this part must keep its natural width (forms, grids, …) instead of being
     * force-wrapped to the conversation width. The conversation panel then hosts the part in its
     * own horizontal scroller; every other part wraps and never scrolls horizontally.
     */
    default boolean horizontalScrollRequested() {
        return false;
    }
}
