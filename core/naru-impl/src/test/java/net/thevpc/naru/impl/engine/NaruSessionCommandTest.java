package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.scheduler.NaruTaskMode;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The line nobody used to catch: {@code deliverInput} with no foreground task.
 * <p>
 * A terminal cannot produce that state — its readline thread parks a task before a human can type —
 * but a headless host hits it as the very first thing it does, and until now the engine answered by
 * returning from an empty method. The line vanished with no error, no log and no task. These tests
 * pin the replacement: the line becomes the turn it would have been at a prompt, and the session
 * survives the whole exchange.
 */
public class NaruSessionCommandTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    /** Records everything the session writes, and never answers a request on its own. */
    private static class Recorder implements NaruInteraction {
        final List<String> outputs = new CopyOnWriteArrayList<>();
        final List<NaruInputRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public String name() {
            return "recorder";
        }

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
            requests.add(request);
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
            outputs.add(mode + " " + message);
        }

        @Override
        public void writeStream(NaruLogMode mode, NMsg fragment, boolean end) {
            outputs.add(mode + " " + fragment);
        }

        @Override
        public void close() {
        }
    }

    private static NaruSession newSession(Recorder host) {
        NaruWorkspace agent = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-session-command");
        agent.projectDirectory(dir);
        NaruSession s = agent.newSession()
                .directory(dir)
                .interaction(host)
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        // a session built without defaults has an empty registry; the terminal gets its
        // directives from the profile the app installs, so this test installs them itself
        s.registry().registerDirectiveProvider(new net.thevpc.naru.impl.registry.NaruBuiltinDirectiveProvider());
        s.start();
        return s;
    }

    private static boolean await(java.util.function.BooleanSupplier condition, long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    @Test
    void aLineWithNoForegroundTaskBecomesATask() {
        Recorder host = new Recorder();
        NaruSession s = newSession(host);
        Assertions.assertFalse(s.hasLiveTasks(), "a fresh session has nothing in the foreground");

        s.deliverInput("/help");

        Assertions.assertTrue(await(() -> s.hasLiveTasks(), 5000),
                "the line must produce a task; dropping it silently is the bug this replaces");
        long fg = s.foregroundTaskId();
        NOptional<NaruTask> task = s.findTask(fg);
        Assertions.assertTrue(task.isPresent(), "and it is the foreground the next line goes to");
        Assertions.assertEquals(NaruTaskMode.INTERACTIVE, task.get().taskMode(),
                "interactive, so the turn ends by asking for the next line instead of terminating");
        Assertions.assertTrue(await(() -> !s.pendingInputs().isEmpty(), 5000),
                "the task parks on readline, which is what makes the session answerable again");
        s.stop();
    }

    /**
     * The point of leaving an INTERACTIVE task in the foreground: the second line is an answer to
     * the first task's prompt, not a second session-level turn.
     */
    @Test
    void theSecondLineGoesToTheTaskTheFirstOneCreated() {
        Recorder host = new Recorder();
        NaruSession s = newSession(host);
        s.deliverInput("/help");
        Assertions.assertTrue(await(() -> !s.pendingInputs().isEmpty(), 5000), "setup: first line parked");
        int tasksAfterFirst = s.tasks().size();

        String id = s.pendingInputs().get(0).requestId();
        s.deliverInput("/name second");
        Assertions.assertTrue(s.deliverPendingInput(id, "ignored"),
                "the parked request was still open until the line answered it");
        Assertions.assertEquals(tasksAfterFirst, s.tasks().size(),
                "a line with a foreground is an answer, not a new session-level task");
        s.stop();
    }

    @Test
    void aBlankLineCreatesNothing() {
        Recorder host = new Recorder();
        NaruSession s = newSession(host);
        s.deliverInput("   ");
        Assertions.assertFalse(s.hasLiveTasks(),
                "whitespace is not a turn, and creating a task for it would make every stray Enter one");
        s.stop();
    }

    /**
     * An unparseable line used to be a non-event. Now it is a logged failure — and the session has
     * to outlive it. A task that reaches a terminal state is deregistered, and the last task leaving
     * the session stops it, so "clean up the failed line by killing the task" would turn a typo into
     * a shutdown.
     */
    @Test
    void anUnparseableLineLeavesTheSessionRunning() {
        Recorder host = new Recorder();
        NaruSession s = newSession(host);
        s.deliverInput("/no-such-directive-xyz");

        Assertions.assertTrue(await(() -> !s.pendingInputs().isEmpty(), 5000),
                "the task parks on input rather than terminating");
        Assertions.assertTrue(s.isRunning(), "and the session survives a line it could not parse");
        NaruTask task = s.findTask(s.foregroundTaskId()).orNull();
        Assertions.assertNotNull(task);
        Assertions.assertNotEquals(NaruTaskStatus.KILLED, task.status(),
                "the failed line is not the task's fault, so it is not punished as one");
        s.stop();
    }
}
