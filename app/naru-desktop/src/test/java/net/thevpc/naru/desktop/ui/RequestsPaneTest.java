package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.model.NaruCacheableContext;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.desktop.model.ModelCallLog;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.time.NDuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import javax.swing.JList;
import javax.swing.ListModel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The Requests tab as an object, built off-screen.
 * <p>
 * {@code ModelCallLogTest} proves what the store holds; this proves the three things only the widget
 * can get wrong: that a reader at the head is carried to each new call instead of watching the row
 * they were reading slide down the list, that a reader who has chosen a call keeps it, and that a
 * click on a group header lands on a message rather than on the label.
 */
class RequestsPaneTest {

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

    private static NaruModelRequest request(int turn) {
        List<NaruMessage> stable = List.of(
                NaruMessage.system("you are a coding agent"),
                NaruMessage.user("the question that stays in the prefix"));
        List<NaruMessage> tail = List.of(NaruMessage.user("turn " + turn));
        List<NaruMessage> all = new ArrayList<>(stable);
        all.addAll(tail);
        return new NaruModelRequest(all, List.of(), Map.of("cwd", NElement.of("naru")))
                .withCacheableContext(NaruCacheableContext.builder()
                        .cacheable("system-prompt", stable)
                        .volatileMessages("turn-tail", tail)
                        .build());
    }

    private static NaruResponse answer(String text) {
        NaruResponse r = new NaruResponse(NaruMessage.engine(text), true);
        r.setPromptTokens(100);
        r.setEvalTokens(10);
        r.setCacheReadTokens(50);
        r.setStopReason("end_turn");
        return r;
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

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static List<JList<?>> lists(Container c) {
        List<JList<?>> out = new ArrayList<>();
        for (Component ch : c.getComponents()) {
            if (ch instanceof JList<?> l) {
                out.add(l);
            }
            if (ch instanceof Container inner) {
                out.addAll(lists(inner));
            }
        }
        return out;
    }

    private static JTextField filter(Container c) {
        for (Component ch : c.getComponents()) {
            if (ch instanceof JTextField f) {
                return f;
            }
            if (ch instanceof Container inner) {
                JTextField found = filter(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static ModelCallLog.Call callAt(ModelCallLog log, int indexFromNewest) {
        return log.calls().get(indexFromNewest);
    }

    @Test
    void aReaderAtTheHeadIsCarriedToEachNewCall() throws Exception {
        ModelCallLog log = new ModelCallLog();
        RequestsPane pane = new RequestsPane(log);
        List<JList<?>> found = lists(pane);
        JList<?> calls = found.get(0);
        JList<?> rail = found.get(1);

        onEdt(() -> {
            log.onModelCall(null, new NaruModelKey("anthropic", "claude"), request(1), answer("one"),
                    NDuration.ofMillis(100));
            assertEquals(0, calls.getSelectedIndex());
            log.onModelCall(null, new NaruModelKey("anthropic", "claude"), request(2), answer("two"),
                    NDuration.ofMillis(100));
            assertEquals(0, calls.getSelectedIndex(), "the head must stay the head as calls arrive");
            log.onModelCall(null, new NaruModelKey("anthropic", "claude"), request(3), answer("three"),
                    NDuration.ofMillis(100));
            assertEquals(0, calls.getSelectedIndex());
        });
        assertEquals(3, ((ListModel<?>) calls.getModel()).getSize());
        assertSame(callAt(log, 0), calls.getSelectedValue());
        // the rail is rebuilt for the newest call and lands on its first message, not on a header
        assertEquals(1, rail.getSelectedIndex());
    }

    @Test
    void aReaderWhoChoseACallKeepsIt() throws Exception {
        ModelCallLog log = new ModelCallLog();
        RequestsPane pane = new RequestsPane(log);
        JList<?> calls = lists(pane).get(0);
        onEdt(() -> {
            for (int i = 1; i <= 3; i++) {
                log.onModelCall(null, new NaruModelKey("anthropic", "claude"), request(i), answer("a" + i),
                        NDuration.ofMillis(100));
            }
        });
        Object chosen = callAt(log, 2);
        onEdt(() -> {
            calls.setSelectedValue(chosen, true);
            assertEquals(2, calls.getSelectedIndex());
            log.onModelCall(null, new NaruModelKey("anthropic", "claude"), request(4), answer("four"),
                    NDuration.ofMillis(100));
        });
        assertSame(chosen, calls.getSelectedValue(),
                "a call the reader opened must not be swapped for the one that just landed");
        assertEquals(3, calls.getSelectedIndex());
    }

    @Test
    void aGroupHeaderSelectsTheMessageUnderIt() throws Exception {
        ModelCallLog log = new ModelCallLog();
        RequestsPane pane = new RequestsPane(log);
        JList<?> rail = lists(pane).get(1);
        onEdt(() -> log.onModelCall(null, new NaruModelKey("anthropic", "claude"), request(1), answer("one"),
                NDuration.ofMillis(100)));
        onEdt(() -> {
            rail.setSelectedIndex(0);
            assertEquals(1, rail.getSelectedIndex(),
                    "a header is a label, so the click belongs to the first row under it");
        });
        // header, the two cached messages, the volatile header and its one message, then env's
        // header and its row; the request carried no tool definitions, so there is no tools group
        assertEquals(7, ((ListModel<?>) rail.getModel()).getSize());
    }

    @Test
    void theFilterNarrowsTheListAndKeepsTheHeadRule() throws Exception {
        ModelCallLog log = new ModelCallLog();
        RequestsPane pane = new RequestsPane(log);
        JList<?> calls = lists(pane).get(0);
        JTextField box = filter(pane);
        onEdt(() -> {
            log.onModelCall(null, new NaruModelKey("anthropic", "claude"), request(1), answer("one"),
                    NDuration.ofMillis(100));
            log.onModelCall(null, new NaruModelKey("ollama", "qwen"), request(2), answer("two"),
                    NDuration.ofMillis(100));
        });
        assertEquals(2, ((ListModel<?>) calls.getModel()).getSize());
        onEdt(() -> box.setText("qwen"));
        assertEquals(1, ((ListModel<?>) calls.getModel()).getSize());
        assertEquals("ollama / qwen", ((ModelCallLog.Call) calls.getSelectedValue()).modelLabel(),
                "the surviving row is the one the reader is left looking at");
        onEdt(() -> box.setText(""));
        assertEquals(2, ((ListModel<?>) calls.getModel()).getSize());
        assertNotNull(calls.getSelectedValue());
    }
}
