package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruMessagePart;
import net.thevpc.nuts.elem.NElement;
import org.junit.jupiter.api.Test;

import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conversation must re-flow text at the width it is given: message bodies wrap and report a
 * height measured for that wrap, and only parts that explicitly ask for it (forms) get a
 * horizontal scroller.
 */
class ChatMessageCardWrapTest {

    /**
     * Laying a card out reads its HTML document, and a markdown part hands its converted text to
     * the event thread, which writes that same document. Doing the layout here on the test thread
     * therefore deadlocks the two: the test thread holds the Swing tree lock and waits for the
     * document, the event thread holds the document and waits for the tree lock. The whole test
     * runs on the event thread instead, so the two never meet.
     */
    private static void onEdt(Runnable body) {
        try {
            SwingUtilities.invokeAndWait(body);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            if (cause instanceof AssertionError error) throw error;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        }
    }

    @Test
    void aBodyWrapsAtItsWidthAndRemeasuresWhenResized() {
        onEdt(() -> {
            String longText = "word ".repeat(40) + "https://example.invalid/" + "x".repeat(200);
            ChatMessageCard card = new ChatMessageCard(NaruMessage.assistant(longText),
                    new ChatPartRenderers(), null);
            layout(card, 360);
            JEditorPane body = findBody(card);
            assertNotNull(body, "the message text host");
            int heightAtNarrow = body.getPreferredSize().height;
            assertTrue(heightAtNarrow > 100, "a long message must wrap over several lines, got " + heightAtNarrow);

            // Widening the conversation re-wraps the same text: fewer lines, same content.
            layout(card, 900);
            assertEquals(body.getWidth(), body.getPreferredSize().width, "body must measure at its real width");
            int heightAtWide = body.getPreferredSize().height;
            assertTrue(heightAtWide < heightAtNarrow,
                    "resizing wider must recompute the wrap: " + heightAtWide + " !< " + heightAtNarrow);
        });
    }

    @Test
    void plainTextNeverAsksForHorizontalScroll() {
        onEdt(() -> {
            ChatMessageCard card = new ChatMessageCard(NaruMessage.assistant("x".repeat(5000)),
                    new ChatPartRenderers(), null);
            layout(card, 400);
            assertEquals(0, countClass(card, JScrollPane.class), "text-only cards must wrap, never scroll sideways");
        });
    }

    @Test
    void partsThatAskForItAreHostedInTheirOwnHorizontalScroller() {
        net.thevpc.nuts.Nuts.require();
        NElement payload = NElement.ofObjectBuilder()
                .set("title", "Input required")
                .set("fields", NElement.ofArray(NElement.ofString("name")))
                .build();
        NaruMessage message = NaruMessage.assistant("filler")
                .setParts(List.of(new NaruMessagePart("form", payload)));
        onEdt(() -> {
            ChatMessageCard card = new ChatMessageCard(message, new ChatPartRenderers(), null);
            layout(card, 300);
            assertEquals(1, countClass(card, JScrollPane.class),
                    "a form part must be hosted in its own horizontal scroller");
        });
    }

    /** Size the card and lay its whole tree out, the way the conversation panel would. */
    private static void layout(Component root, int width) {
        root.setSize(width, Integer.MAX_VALUE);
        doLayoutTree(root);
    }

    private static void doLayoutTree(Component c) {
        if (c instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) {
                doLayoutTree(child);
            }
        }
    }

    private static JEditorPane findBody(Container root) {
        for (Component c : all(root)) {
            if (c instanceof JEditorPane pane) return pane;
        }
        return null;
    }

    private static int countClass(Container root, Class<? extends Component> type) {
        int count = 0;
        for (Component c : all(root)) {
            if (type.isInstance(c)) count++;
        }
        return count;
    }

    private static List<Component> all(Container root) {
        List<Component> out = new ArrayList<>();
        for (Component c : root.getComponents()) {
            out.add(c);
            if (c instanceof Container inner && !(c instanceof JEditorPane)) out.addAll(all(inner));
        }
        return out;
    }
}
