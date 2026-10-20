package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.model.NaruMessagePart;

import javax.swing.JComponent;
import java.util.function.Consumer;

/** Renders one typed message part as a real Swing component. */
public interface ChatPartRenderer {
    String type();

    JComponent render(NaruMessagePart part, Consumer<String> actionHandler);
}
