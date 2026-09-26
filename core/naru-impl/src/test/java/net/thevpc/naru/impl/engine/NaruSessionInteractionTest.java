package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The session half of the interaction seam: a blocked task becomes a question handed to
 * whatever interaction the host chose, and the answer comes back through
 * {@link NaruSession#deliverInput(String)} or the request itself.
 */
public class NaruSessionInteractionTest {

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

    /** Captures what the session asks, so the test can answer as a host would. */
    private static class CapturingInteraction implements NaruInteraction {
        final List<NaruInputRequest> requests = new CopyOnWriteArrayList<>();
        final List<NMsg> output = new CopyOnWriteArrayList<>();

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
            requests.add(request);
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
            output.add(message);
        }

        @Override
        public void close() {
        }
    }

    private static NaruSessionImpl newSession(NaruInteraction interaction, String name) {
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-interaction-" + name));
        return new NaruSessionImpl(agent, agent.projectDirectory(), interaction, false,
                null, null, null, null);
    }

    private static NaruTask blockedTask(NaruSessionImpl session) {
        NaruTask task = session.newTask(NaruTaskSpec.of().statements("noop"));
        task.requestInput(NMsg.ofC("what is your name?"));
        return task;
    }

    @Test
    public void aBlockedTaskReachesTheHostAsAQuestion() {
        CapturingInteraction interaction = new CapturingInteraction();
        NaruSessionImpl session = newSession(interaction, "question");
        NaruTask task = blockedTask(session);

        session.onInputRequested(task);

        Assertions.assertEquals(1, interaction.requests.size(),
                "the host must be told a question is outstanding");
        NaruInputRequest request = interaction.requests.get(0);
        Assertions.assertTrue(request.prompt().toString().contains("what is your name?"));
        Assertions.assertSame(task, request.task());
    }

    @Test
    public void answeringDeliversTheLineToTheTask() {
        CapturingInteraction interaction = new CapturingInteraction();
        NaruSessionImpl session = newSession(interaction, "answer");
        NaruTask task = blockedTask(session);
        session.onInputRequested(task);

        interaction.requests.get(0).deliver("ada");

        Assertions.assertEquals(NaruTaskStatus.READY, task.status(),
                "an answered task must be runnable again, not left blocked");
        Assertions.assertEquals("ada", task.consumeInput());
    }

    /**
     * A browser that reconnects and replays a submission must not resume the same task
     * twice. Two answers would queue two lines and the task would execute both.
     */
    @Test
    public void aSecondAnswerIsIgnored() {
        CapturingInteraction interaction = new CapturingInteraction();
        NaruSessionImpl session = newSession(interaction, "double");
        NaruTask task = blockedTask(session);
        session.onInputRequested(task);

        NaruInputRequest request = interaction.requests.get(0);
        request.deliver("first");
        request.deliver("second");

        Assertions.assertEquals("first", task.consumeInput());
        Assertions.assertNull(task.consumeInput(),
                "the replayed answer must be dropped, not queued behind the first");
    }

    /**
     * A user closing the tab leaves nobody to answer. The task has to fail rather than stay
     * blocked forever, because nothing would ever resume it.
     */
    @Test
    public void cancellingStopsTheTaskFromWaitingForever() {
        CapturingInteraction interaction = new CapturingInteraction();
        NaruSessionImpl session = newSession(interaction, "cancel");
        NaruTask task = blockedTask(session);
        session.onInputRequested(task);

        interaction.requests.get(0).cancel("websocket closed");

        Assertions.assertNotEquals(NaruTaskStatus.BLOCKED_ON_INPUT, task.status(),
                "a cancelled question must not leave the task blocked");
        Assertions.assertEquals(NaruTaskStatus.FAILED, task.status());
    }

    @Test
    public void cancelAfterDeliveryChangesNothing() {
        CapturingInteraction interaction = new CapturingInteraction();
        NaruSessionImpl session = newSession(interaction, "race");
        NaruTask task = blockedTask(session);
        session.onInputRequested(task);

        NaruInputRequest request = interaction.requests.get(0);
        request.deliver("ada");
        request.cancel("too late");

        Assertions.assertEquals("ada", task.consumeInput(),
                "a late cancel must not undo an answer already accepted");
    }

    @Test
    public void sessionOutputGoesToTheInteractionNotTheProcessConsole() {
        List<NMsg> got = new ArrayList<>();
        NaruSessionImpl session = newSession(new NaruStreamInteraction(o -> got.add(o.message())),
                "output");

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("hello from the session"));

        // Session construction may already have reported a model-resolution problem
        // through the same channel, so assert on the message rather than the count.
        Assertions.assertTrue(
                got.stream().anyMatch(m -> "hello from the session".equals(m.toString())),
                "the session's own output must reach its interaction, got " + got);
    }

    /**
     * The warning a session emits while resolving its model used to be written straight to
     * the process stdout, where with several sessions running nobody could tell which one
     * was complaining.
     */
    @Test
    public void modelResolutionProblemsAreReportedThroughTheInteraction() {
        CapturingInteraction interaction = new CapturingInteraction();
        newSession(interaction, "warn");

        Assertions.assertFalse(interaction.output.isEmpty(),
                "a session that cannot find a usable model must say so on its own channel");
    }
}
