package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The composer is the one field a question cannot be answered without, so the split pane has to
 * hand it enough room when the agent's prompt panel appears above it.
 */
class InputPaneLayoutTest {

    private JFrame frame;
    private InputPane input;
    private NaruSessionModel model;

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    @AfterEach
    void tearDown() throws Exception {
        onEdt(() -> {
            if (input != null) {
                input.dispose();
                input = null;
            }
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
            model = null;
        });
    }

    @Test
    void anAgentPromptGrowsTheComposerInsteadOfHidingItsField() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "the layout is measured in a real frame");
        onEdt(() -> {
            frame = new JFrame("naru input layout test");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            model = new NaruSessionModel(() -> null,
                    NPath.ofTempFolder("naru-desktop-input-layout-" + System.nanoTime()),
                    new InteractionHost());
            input = new InputPane(model, () -> { });
            JPanel transcript = new JPanel();
            JSplitPane split = SplitPanes.vertical(transcript, input);
            frame.getContentPane().add(split);
            frame.setSize(760, 240);
            frame.setLocation(0, 0);
            frame.setVisible(true);
            // squeeze the composer the way a remembered divider or a short window does
            split.setDividerLocation(150);
            frame.validate();
        });
        flushEdt();

        assertTrue(onEdtReturn(() -> input.composer().getHeight()) > 0,
                "the composer field starts visible, so the premise is a prompt that changes the layout");

        onEdt(() -> input.showPrompt(promptWithChoices(8)));
        flushEdt();

        int fieldHeight = onEdtReturn(() -> input.composer().getHeight());
        assertTrue(fieldHeight > 0,
                "the prompt panel must not squeeze the composer field to nothing until a window resize");
        assertTrue(onEdtReturn(() -> input.getHeight()) > 150,
                "the split pane gives the composer the height its own prompt panel asks for");
    }

    @Test
    void aConfiguredContextWindowWinsOverTheModelMaximum() {
        assertEquals("32,000 ctx", InputPane.contextLabel(32_000, 131_072, true),
                "the registration's contextLength is the value the request actually carries");
        assertEquals("default ctx", InputPane.contextLabel(-1, 131_072, true),
                "a local server keeps its own default unless contextLength is set");
        assertEquals("131,072 max ctx", InputPane.contextLabel(-1, 131_072, false),
                "a hosted model's reported window is its maximum");
        assertEquals("? ctx", InputPane.contextLabel(-1, -1, false));
    }

    private static NaruInputRequest promptWithChoices(int count) {
        StringBuilder text = new StringBuilder("Which one?\n");
        for (int i = 1; i <= count; i++) {
            text.append(i).append(". choice ").append(i).append('\n');
        }
        text.append("Reply with a number or type another answer.");
        return new NaruInputRequest() {
            @Override public NMsg prompt() { return NMsg.ofC("%s", text.toString()); }
            @Override public NaruTask task() { return null; }
            @Override public void deliver(String line) { }
            @Override public void cancel(String reason) { }
        };
    }

    private static void flushEdt() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    private static void onEdt(ThrowingRunnable body) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static <T> T onEdtReturn(Edt<T> body) throws Exception {
        Object[] out = new Object[1];
        onEdt(() -> out[0] = body.get());
        @SuppressWarnings("unchecked")
        T value = (T) out[0];
        return value;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private interface Edt<T> {
        T get() throws Exception;
    }
}
