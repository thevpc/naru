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
    private Thread reader;

    /** Uses the process terminal, writing through the terminal's own out. */
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
        sequence.incrementAndGet();
        for (NMsg line : render(mode, message)) {
            emit(line);
        }
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
