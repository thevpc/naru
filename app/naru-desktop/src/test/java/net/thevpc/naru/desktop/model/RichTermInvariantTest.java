package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.agent.NaruWorkspaceBuilder;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NIO;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NSystemTerminal;
import net.thevpc.nuts.spi.base.NSystemTerminalBase;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan §4.4, process-global state: "never route any session through {@code richTerm(true)} in the
 * desktop host."
 * <p>
 * Until now that rule lived in a comment and in one {@code .richTerm(false)} call, so anyone adding
 * a builder line could undo it silently. The damage is not local: {@code NaruSessionBuilderImpl
 * .build()} calls {@code NSystemTerminal.enableRichTerm()}, which asks Nuts to load {@code nuts-term}
 * and <b>replace the base of the JVM-wide system terminal</b>, then installs a completer, a
 * highlighter and a Ctrl+C handler owned by the <i>last</i> interactive session in the JVM. A cockpit
 * holding several sessions would take the console out from under a terminal REPL in the same process.
 * <p>
 * Nuts exposes no {@code isRichTerm()} getter, and {@code isAutoCompleteSupported()} turned out to be
 * an unreliable witness: measured in this module's JVM it stays {@code false} even after {@code
 * richTerm(true)}, because the answer depends on the terminal Nuts detected rather than on the
 * request. The witness that does move is {@code NIO.of().systemTerminal().base()} — the system
 * terminal is a delegating ref, and enabling rich term swaps the object behind it — so that is what
 * both tests read, alongside the two decorators the console installs.
 * <p>
 * Deliberately not a mock: the first test drives the real {@link NaruSessionModel} boot thread with
 * the real workspace factory, so it asserts on the code path the window actually runs.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RichTermInvariantTest {

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    /** The rule itself: booting a desktop session leaves the shared terminal exactly as it found it. */
    @Test
    @Order(1)
    void bootingADesktopSessionLeavesTheSystemTerminalAlone() throws Exception {
        NSystemTerminal ref = NIO.of().systemTerminal();
        NSystemTerminalBase plainBase = ref.base();
        assertNotNull(plainBase, "the system terminal ref has no base before anything runs");
        assertNull(ref.autoCompleteResolver(), "premise: nothing has installed a command completer yet");
        assertNull(ref.commandHighlighter(), "premise: nothing has installed a command highlighter yet");

        NPath dir = NPath.ofTempFolder("naru-desktop-richterm");
        NaruSessionModel[] model = new NaruSessionModel[1];
        SwingUtilities.invokeAndWait(() -> {
            InteractionHost host = new InteractionHost();
            host.startFlushTimer();
            // the same factory the window builds, on the same worker-thread boot path
            model[0] = new NaruSessionModel(
                    () -> NaruWorkspaceBuilder.of().projectDirectory(dir).build(), dir, host);
            model[0].start();
        });
        try {
            NaruSession session = awaitSession(model[0], 180_000);
            assertNotNull(session, "the desktop session never booted; status was " + model[0].statusText());
            // `running` is set by the boot thread's invokeLater, so it lands a beat after the field;
            // wait for it instead of asserting a race
            long deadline = System.currentTimeMillis() + 10_000;
            while (!model[0].isRunning() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(model[0].isRunning(), "session built but the desktop did not report it running");

            NSystemTerminal after = NIO.of().systemTerminal();
            assertSame(plainBase, after.base(),
                    "the desktop session-build path swapped the JVM-wide system terminal — that is what "
                            + "NaruSessionBuilderImpl.enableRichTerm() does, and NaruSessionModel.boot() "
                            + "must keep richTerm(false)");
            assertNull(after.autoCompleteResolver(),
                    "a desktop host installed the console's command completer on the shared terminal");
            assertNull(after.commandHighlighter(),
                    "a desktop host installed the console's command highlighter on the shared terminal");
            assertFalse(after.isAutoCompleteSupported(), "the desktop session enabled the rich terminal");
        } finally {
            SwingUtilities.invokeAndWait(model[0]::shutdown);
        }
    }

    /**
     * The same channel, seen from the other side. Without this, the test above might be passing
     * because {@code base()} is a constant in this JVM — and it would go on passing the day the
     * desktop starts calling {@code richTerm(true)}.
     * <p>
     * Measured here: {@code richTerm(true)} really does replace the base (the plain
     * {@code DefaultNSystemTerminalBase} becomes {@code DefaultSystemTerminal}), which is exactly the
     * global mutation §4.4 exists to prevent.
     * <p>
     * Ordered last and undone in a {@code finally}, because Nuts has no "disable rich term": the only
     * way back is to put the previous base object. Handing the <em>ref</em> itself to {@code
     * NIO.systemTerminal(...)} makes {@code base()} delegate to itself, and the next {@code
     * isAutoCompleteSupported()} call then recurses until the stack overflows — which is why this test
     * snapshots {@code ref.base()} rather than the terminal.
     */
    @Test
    @Order(2)
    void theWitnessMovesWhenASessionAsksForARichTerminal() {
        NSystemTerminal ref = NIO.of().systemTerminal();
        NSystemTerminalBase plainBase = ref.base();
        assertNotNull(plainBase, "no terminal base to restore; this test must not run");
        assertSame(ref, NIO.of().systemTerminal(), "the system terminal ref is not a JVM singleton");
        try {
            NPath dir = NPath.ofTempFolder("naru-desktop-richterm-control");
            NaruWorkspace agent = NaruWorkspace.of();
            agent.projectDirectory(dir);
            NaruSession session = agent.newSession()
                    .directory(dir)
                    // not a NaruTerminalInteraction: only a terminal interaction installs the
                    // completer and the highlighter, and this test is about the global swap
                    .interaction(silentHost())
                    .statements(new String[0])
                    .interactive()
                    .banner(false)
                    .richTerm(true)
                    .build();
            assertNotNull(session);
            assertNotSame(plainBase, NIO.of().systemTerminal().base(),
                    "richTerm(true) left the shared terminal untouched, so the base-identity check in the "
                            + "test above witnesses nothing and has to be replaced");
        } finally {
            NIO.of().systemTerminal(plainBase);
            assertSame(plainBase, NIO.of().systemTerminal().base(),
                    "restoring the plain base did not take — this test would leak rich term into the rest "
                            + "of the module's tests");
        }
    }

    /** Boot runs on {@code naru-session-boot}; poll the volatile field rather than assume it. */
    private static NaruSession awaitSession(NaruSessionModel model, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (model.isClosed()) {
                return null;
            }
            NaruSession s = model.session();
            if (s != null) {
                return s;
            }
            Thread.sleep(50);
        }
        return null;
    }

    /** A host that accepts the session and never answers, so nothing runs and nothing reads stdin. */
    private static NaruInteraction silentHost() {
        return new NaruInteraction() {
            @Override
            public String name() {
                return "silent";
            }

            @Override
            public void open(NaruSession session) {
            }

            @Override
            public void requestInput(NaruInputRequest request) {
            }

            @Override
            public void write(NaruLogMode mode, NMsg message) {
            }

            @Override
            public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            }

            @Override
            public void close() {
            }
        };
    }
}
