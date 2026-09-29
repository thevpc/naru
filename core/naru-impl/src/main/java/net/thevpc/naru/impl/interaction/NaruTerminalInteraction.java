package net.thevpc.naru.impl.interaction;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.util.NaruTerminalFormatter;
import net.thevpc.nuts.io.NTerminal;
import net.thevpc.nuts.log.NLogger;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NStringUtils;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The desktop implementation: a real terminal, a real readline thread.
 * <p>
 * This is where presentation lives. Indentation, the {@code ▌} gutter, and the per-mode
 * colour scheme were previously in {@code NaruAgentImpl.log}, where they applied to every
 * session at once; they are here now, so a headless session emitting the same events gets
 * plain text and no ANSI escapes.
 *
 * <h2>Why a thread at all</h2>
 * {@link #requestInput} is called on a scheduler worker that must not block, so the read
 * is pushed onto a dedicated thread. That thread parks on a queue and only touches the
 * terminal while a question is outstanding, which is what lets a batch session coexist
 * with a console in the same process.
 *
 * <h2>Streams are the host's choice</h2>
 * The terminal defaults to the process one, but two interactive sessions cannot share a
 * single stdin — whoever reads first wins. A host that needs two live consoles passes
 * distinct streams to the constructor instead.
 */
public class NaruTerminalInteraction implements NaruInteraction {

    private final NTerminal terminal;
    private final NLogger logger;
    private final BlockingQueue<NaruInputRequest> pending = new LinkedBlockingQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong();

    /**
     * The streamed line currently being drawn, or null when nothing is open.
     */
    private NaruLogMode streamMode;
    private boolean atLineStart = true;

    /**
     * Used only when there is no terminal to draw on; see {@link #writeStream}.
     */
    private final NaruBufferedStreamWriter bufferedStream = new NaruBufferedStreamWriter();

    private Thread reader;

    /**
     * Uses the process terminal, writing through the terminal's own out.
     */
    public NaruTerminalInteraction() {
        this(NTerminal.of(), null);
    }

    public NaruTerminalInteraction(NTerminal terminal) {
        this(terminal, null);
    }

    /**
     * @param terminal the terminal to read from and write to; null means the process one
     * @param logger   where styled output goes; null means the terminal's own out
     */
    public NaruTerminalInteraction(NTerminal terminal, NLogger logger) {
        this.terminal = terminal == null ? NTerminal.of() : terminal;
        this.logger = logger;
    }

    @Override
    public String name() {
        return NAME_TERMINAL;
    }

    @Override
    public void open(NaruSession session) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        closed.set(false);
        reader = new Thread(this::readLoop, "naru-readline");
        // daemon: this thread only services outstanding input questions. It must never
        // keep the JVM alive after the work of a batch session is done, otherwise the
        // process (and any test using it) never exits.
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        while (running.get()) {
            NaruInputRequest request;
            try {
                request = pending.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (closed.get()) {
                request.cancel("the session is closing");
                return;
            }
            String line;
            try {
                line = terminal.readLine(request.prompt());
            } catch (Exception ex) {
                request.cancel("terminal read failed: " + ex.getMessage());
                continue;
            }
            if (line == null) {
                // end of input: nobody is left to answer
                request.cancel("terminal input closed");
                continue;
            }
            request.deliver(line);
        }
    }

    @Override
    public void requestInput(NaruInputRequest request) {
        if (closed.get()) {
            request.cancel("the session is closing");
            return;
        }
        pending.add(request);
    }

    @Override
    public void write(NaruLogMode mode, NMsg message) {
        if (closed.get()) {
            return;
        }
        // a half-drawn streamed line must be terminated first, or this message
        // would continue it and the two would read as one sentence
        closeStreamLine();
        sequence.incrementAndGet();
        emitLines(mode, message);
    }

    private void emitLines(NaruLogMode mode, NMsg message) {
        for (NMsg line : render(mode, message)) {
            emit(line);
        }
    }

    /**
     * Draws a fragment as it arrives, so a long answer appears while it is being
     * written rather than all at once when it is finished.
     *
     * <p>Each physical line is prefixed exactly as {@link #render} would prefix it, so
     * a streamed answer and a batched one are indented identically. The content
     * itself is printed unstyled: markdown cannot be rendered on a fragment, because
     * half a code fence or an unmatched {@code **} is not markup, it is noise.
     */
    @Override
    public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
        if (closed.get()) {
            return;
        }
        if (logger != null || terminal == null) {
            // nowhere to draw incrementally: join and report once, same as a batch
            // call, rather than emitting half a line per token
            bufferedStream.write(this, mode, fragment, end);
            return;
        }
        if (streamMode != null && streamMode != mode) {
            // thinking ended, answer starting: the reasoning must not run into it
            closeStreamLine();
        }
        if (streamMode == null) {
            streamMode = mode;
            atLineStart = true;
        }
        String text = fragment == null ? "" : fragment.toString();
        int from = 0;
        boolean wasNewLine = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                print(text, from, i);
                terminal.out().println();
                from = i + 1;
                atLineStart = true;
                wasNewLine = true;
            } else {
                wasNewLine = false;
            }
        }
        print(text, from, text.length());
        if (end && streamMode == mode) {
            // guarded on the open mode: a caller that closes one channel after
            // another has already started must not terminate the live one
            closeStreamLine();
        }
    }

    private void print(String text, int from, int to) {
        if (to <= from) {
            return;
        }
        if (atLineStart) {
            terminal.out().print(streamPrefix(streamMode));
            atLineStart = false;
        }
        String s = text.substring(from, to);
        if (streamMode == NaruLogMode.MODEL_THINKING) {
            terminal.out().print(NMsg.ofC("%s", NMsg.ofStyledPale(s)));
        } else {
            terminal.out().print(NMsg.ofC("%s", s));
        }
    }

    /**
     * The per-line marker for a streamed model turn, matching {@link #render}.
     */
    private static NMsg streamPrefix(NaruLogMode mode) {
        switch (mode) {
            case MODEL_THINKING: {
                return NMsg.ofC("%s", NText.ofStyled("  \u258C", NTextStyle.primary9()));
            }
            case MODEL_RESPONSE: {
                return NMsg.ofC("%s", NText.ofStyled("  \u258C", NTextStyle.primary3()));
            }
            default: {
                return NMsg.ofC("%s", NText.ofStyled("  \u258C", NTextStyle.primary8()));
            }
        }
    }

    private void closeStreamLine() {
        if (streamMode == null) {
            return;
        }
        if (!atLineStart) {
            terminal.out().println();
        }
        streamMode = null;
        atLineStart = true;
    }

    /**
     * Turns a message into styled lines. Package-visible for tests, and the single place
     * that decides how each mode looks.
     */
    java.util.List<NMsg> render(NaruLogMode mode, NMsg message) {
        switch (mode) {
            case MODEL_RESPONSE: {
                return boxed(NaruTerminalFormatter.formatOutputLines(message.toString(),
                        NText.ofStyled("  \u258C", NTextStyle.primary3())));
            }
            case MODEL_THINKING: {
                return boxed(NaruTerminalFormatter.formatOutputLines(message.toString(),
                        NText.ofStyled("  \u258C", NTextStyle.primary9())));
            }
            case AGENT_RESPONSE: {
                return logLines(message, 1, "\u258C", 4);
            }
            case SCRIPT: {
                return logLines(message, 2, "\u2705\ufe0f", 5);
            }
            case TRACE: {
                return logLines(message, 2, "\u258C", 6);
            }
            case PROGRESS: {
                return logLines(message, 2, "\u258C", 7);
            }
            case DEBUG: {
                return logLines(message, 2, "\u258C", 8);
            }
            case SCHEDULER: {
                return logLines(message, 0, "\u258C", 9);
            }
            default: {
                return java.util.List.of(message);
            }
        }
    }

    private static java.util.List<NMsg> boxed(java.util.List<NText> lines) {
        java.util.List<NMsg> out = new java.util.ArrayList<>(lines.size());
        for (NText line : lines) {
            out.add(NMsg.ofC("%s", line));
        }
        return out;
    }

    private static java.util.List<NMsg> logLines(NMsg message, int indent, String prefix, int style) {
        java.util.List<NMsg> out = new java.util.ArrayList<>();
        String spaces = NStringUtils.repeat(" ", indent * 2);
        for (NText o : NText.of(message).split("\n", false)) {
            out.add(NMsg.ofC("%s%s %s", spaces, NMsg.ofStyled(prefix, NTextStyle.primary(style)), o));
        }
        return out;
    }

    private void emit(NMsg line) {
        if (logger != null) {
            logger.log(line);
        } else if (terminal != null) {
            terminal.out().println(line);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // Before anything else: a turn interrupted by shutdown must neither leave a
        // half-drawn line nor swallow the text the model already produced. The buffer
        // is drained through emitLines rather than write, because write() correctly
        // refuses everything once closed -- and this is the last chance to report.
        if (terminal != null) {
            closeStreamLine();
        }
        for (java.util.Map.Entry<NaruLogMode, NMsg> leftover : bufferedStream.drain()) {
            emitLines(leftover.getKey(), leftover.getValue());
        }
        running.set(false);
        Thread t = reader;
        if (t != null) {
            t.interrupt();
            try {
                // bounded: a terminal read that is not interruptible must not hang shutdown
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reader = null;
        }
        // anything still queued will never be read now
        NaruInputRequest leftover;
        while ((leftover = pending.poll()) != null) {
            leftover.cancel("the session is closing");
        }
    }
}
