package net.thevpc.naru.impl.interaction;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.io.NPrintStream;
import net.thevpc.nuts.io.NTerminal;
import net.thevpc.nuts.text.NTextStyle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Covers the headless half of the interaction seam: output is an ordered, plain, in-process
 * stream, and no thread or terminal is involved.
 */
public class NaruStreamInteractionTest {

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void outputArrivesInOrderWithItsOwnMetadata() {
        List<NaruOutput> got = new ArrayList<>();
        NaruStreamInteraction interaction = new NaruStreamInteraction(got::add);

        interaction.write(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("first"));
        interaction.write(NaruLogMode.SCRIPT, NMsg.ofC("second"));
        interaction.write(NaruLogMode.TRACE, NMsg.ofC("third"));

        Assertions.assertEquals(3, got.size());
        Assertions.assertEquals(List.of(1L, 2L, 3L),
                got.stream().map(NaruOutput::sequence).toList(),
                "sequence must be monotonic so a reconnecting client can tell what it missed");
        Assertions.assertEquals(List.of(NaruLogMode.AGENT_RESPONSE, NaruLogMode.SCRIPT, NaruLogMode.TRACE),
                got.stream().map(NaruOutput::mode).toList());
        Assertions.assertEquals(List.of("first", "second", "third"),
                got.stream().map(o -> o.message().toString()).toList());
        for (NaruOutput o : got) {
            Assertions.assertNotNull(o.instant());
        }
    }

    /**
     * The whole reason output is styled in the terminal implementation and nowhere else: a
     * browser must never receive escape sequences it would have to strip.
     */
    @Test
    public void messagesAreCarriedThroughVerbatim() {
        List<NaruOutput> got = new ArrayList<>();
        NaruStreamInteraction interaction = new NaruStreamInteraction(got::add);

        NMsg styled = NMsg.ofC("%s", NMsg.ofStyled("hello", NTextStyle.primary1()));
        interaction.write(NaruLogMode.RAW, styled);

        String text = got.get(0).message().toString();
        Assertions.assertEquals("hello", text);
        Assertions.assertFalse(text.contains("\u001B"),
                "a headless consumer must not receive ANSI escapes, got " + text.length() + " chars");
    }

    @Test
    public void aThrowingConsumerDoesNotBreakTheSession() {
        NaruStreamInteraction interaction = new NaruStreamInteraction(o -> {
            throw new IllegalStateException("websocket is gone");
        });
        Assertions.assertDoesNotThrow(() -> interaction.write(NaruLogMode.RAW, NMsg.ofC("x")),
                "output is a side channel; a dead consumer must not fail the work behind it");
    }

    @Test
    public void nothingIsEmittedAfterClose() {
        List<NaruOutput> got = new ArrayList<>();
        NaruStreamInteraction interaction = new NaruStreamInteraction(got::add);
        interaction.open(null);
        interaction.write(NaruLogMode.RAW, NMsg.ofC("before"));
        interaction.close();
        interaction.write(NaruLogMode.RAW, NMsg.ofC("after"));

        Assertions.assertEquals(1, got.size());
        Assertions.assertEquals("before", got.get(0).message().toString());
    }

    /**
     * A headless session must not leave a thread parked on the process stdin. This is the
     * difference between hosting many sessions in one JVM and not being able to.
     */
    @Test
    public void noThreadIsCreatedForAHeadlessSession() throws Exception {
        int before = Thread.activeCount();
        NaruStreamInteraction interaction = new NaruStreamInteraction(o -> {
        });
        interaction.open(null);
        interaction.close();
        Thread.sleep(100);
        Assertions.assertTrue(Thread.activeCount() <= before + 1,
                "a headless interaction must not start a reader thread");
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("naru-readline".equals(t.getName()) && t.isAlive()) {
                t.interrupt();
            }
        }
    }

    /** The terminal implementation owns the prompt styling; keep it identical per mode. */
    @Test
    public void terminalRenderingKeepsItsPerModeGutter() {
        NaruTerminalInteraction terminal = new NaruTerminalInteraction();

        List<NMsg> agent = terminal.render(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("hello"));
        Assertions.assertEquals(1, agent.size());
        Assertions.assertTrue(agent.get(0).toString().contains("\u258C"),
                "AGENT_RESPONSE keeps the gutter marker");
        Assertions.assertTrue(agent.get(0).toString().startsWith("  "),
                "AGENT_RESPONSE is indented by one level");

        List<NMsg> script = terminal.render(NaruLogMode.SCRIPT, NMsg.ofC("a\nb"));
        Assertions.assertEquals(2, script.size(), "multi-line input renders one line each");
        Assertions.assertTrue(script.get(0).toString().startsWith("    "),
                "SCRIPT is indented by two levels");

        List<NMsg> raw = terminal.render(NaruLogMode.RAW, NMsg.ofC("plain"));
        Assertions.assertEquals(1, raw.size());
        Assertions.assertEquals("plain", raw.get(0).toString(),
                "RAW passes through with no decoration");
    }

    /**
     * The terminal implementation, end to end over a real pipe: the request reaches the
     * reader thread, the line typed into the pipe is delivered, and closing releases
     * anything still outstanding. This is the path a console session actually uses.
     */
    @Test
    public void terminalDeliversWhatWasTypedIntoItsStream() throws Exception {
        PipedOutputStream typing = new PipedOutputStream();
        PipedInputStream terminalIn = new PipedInputStream(typing);
        NPrintStream out = NPrintStream.of(new ByteArrayOutputStream());
        NaruTerminalInteraction terminal = new NaruTerminalInteraction(
                NTerminal.of(terminalIn, out, out));

        RecordingRequest request = new RecordingRequest();
        terminal.open(null);
        terminal.requestInput(request);

        // the reader thread is now blocked in readLine; type a line
        typing.write("hello from the pipe\n".getBytes(StandardCharsets.UTF_8));
        typing.flush();

        Assertions.assertTrue(request.answered.await(10, TimeUnit.SECONDS),
                "the reader thread should have delivered the typed line");
        Assertions.assertEquals("hello from the pipe", request.line);
        Assertions.assertNull(request.cancelReason, "a delivered request must not also be cancelled");
        terminal.close();
    }

    /** Nothing left to answer: the request is cancelled rather than left blocked. */
    @Test
    public void terminalCancelsWhatItCannotAnswer() throws Exception {
        PipedOutputStream typing = new PipedOutputStream();
        PipedInputStream terminalIn = new PipedInputStream(typing);
        NPrintStream out = NPrintStream.of(new ByteArrayOutputStream());
        NaruTerminalInteraction terminal = new NaruTerminalInteraction(
                NTerminal.of(terminalIn, out, out));

        RecordingRequest request = new RecordingRequest();
        terminal.open(null);
        terminal.requestInput(request);

        // closing the input stream is what a terminal does at end-of-input (Ctrl-D)
        terminalIn.close();

        Assertions.assertTrue(request.answered.await(10, TimeUnit.SECONDS),
                "end of input must resolve the request, not strand the task");
        Assertions.assertNull(request.line);
        Assertions.assertNotNull(request.cancelReason);
        terminal.close();
    }

    /** A session shutting down must not leave a task blocked on a question nobody will see. */
    @Test
    public void closingCancelsOutstandingRequests() throws Exception {
        PipedOutputStream typing = new PipedOutputStream();
        PipedInputStream terminalIn = new PipedInputStream(typing);
        NPrintStream out = NPrintStream.of(new ByteArrayOutputStream());
        NaruTerminalInteraction terminal = new NaruTerminalInteraction(
                NTerminal.of(terminalIn, out, out));

        RecordingRequest first = new RecordingRequest();
        RecordingRequest second = new RecordingRequest();
        terminal.open(null);
        terminal.requestInput(first);
        terminal.requestInput(second);

        terminal.close();

        Assertions.assertNotNull(first.cancelReason, "an outstanding question must be cancelled on close");
        Assertions.assertNotNull(second.cancelReason);
    }

    private static class RecordingRequest implements NaruInputRequest {
        final CountDownLatch answered = new CountDownLatch(1);
        volatile String line;
        volatile String cancelReason;

        @Override
        public NMsg prompt() {
            return NMsg.ofC("? ");
        }

        @Override
        public NaruTask task() {
            return null;
        }

        @Override
        public void deliver(String l) {
            this.line = l;
            answered.countDown();
        }

        @Override
        public void cancel(String reason) {
            this.cancelReason = reason;
            answered.countDown();
        }
    }

}
