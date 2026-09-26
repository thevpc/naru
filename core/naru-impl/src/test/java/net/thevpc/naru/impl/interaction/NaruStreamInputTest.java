package net.thevpc.naru.impl.interaction;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The web/headless case, end to end: a host with no terminal and no reader thread.
 * <p>
 * A request has to reach the host, and the answer has to come back from somewhere else
 * entirely — another thread here, an HTTP handler in production. The previous
 * implementation dropped the request on the floor, which left any interactive headless
 * task blocked forever with no way for the host to know it was waiting.
 */
public class NaruStreamInputTest {

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

    /** Stands in for a browser: records the request, answers it from a second thread. */
    private static class FakeHost implements NaruStreamInteraction.InputListener {
        final AtomicReference<NaruInputRequest> request = new AtomicReference<>();
        final CountDownLatch arrived = new CountDownLatch(1);

        @Override
        public void onInputRequested(NaruInputRequest r) {
            request.set(r);
            arrived.countDown();
        }
    }

    /** Minimal interaction so the session has something to route through. */
    private static class Routing implements NaruInteraction {
        final NaruStreamInteraction stream;

        Routing(NaruStreamInteraction stream) {
            this.stream = stream;
        }

        @Override
        public String name() {
            return "routing";
        }

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void requestInput(NaruInputRequest request) {
            stream.requestInput(request);
        }

        @Override
        public void write(NaruLogMode mode, NMsg message) {
            stream.write(mode, message);
        }

        @Override
        public void close() {
            stream.close();
        }
    }

    @Test
    public void aHeadlessHostIsActuallyToldWhenInputIsNeeded() throws Exception {
        FakeHost host = new FakeHost();
        NaruStreamInteraction interaction = new NaruStreamInteraction(o -> {
        }, host);
        NaruSessionImpl session = newSession(new Routing(interaction));

        NaruTask task = blockedTask(session);
        session.onInputRequested(task);

        Assertions.assertTrue(host.arrived.await(5, TimeUnit.SECONDS),
                "the host must be notified, otherwise a web session hangs with no visible question");
        Assertions.assertNotNull(host.request.get().prompt(),
                "the host needs the prompt text to render the question");
        Assertions.assertSame(task, host.request.get().task());

        // the answer comes from a different thread, exactly like an HTTP handler
        Thread browser = new Thread(() -> host.request.get().deliver("42"));
        browser.start();
        browser.join(5000);
        Assertions.assertEquals("42", task.consumeInput());
    }

    @Test
    public void answeringThroughTheRequestAndTheSessionAgree() {
        FakeHost host = new FakeHost();
        NaruStreamInteraction interaction = new NaruStreamInteraction(o -> {
        }, host);
        NaruSessionImpl session = newSession(new Routing(interaction));

        NaruTask task = blockedTask(session);
        session.onInputRequested(task);
        host.request.get().deliver("via-request");

        // both doors lead to the same queue, and the second one must not be a second answer
        session.deliverInput("via-session");
        Assertions.assertEquals("via-request", task.consumeInput(),
                "a delivered request is finished; a later session-level answer is ignored");
    }

    @Test
    public void noHostListenerMeansTheTaskFailsInsteadOfHanging() {
        NaruStreamInteraction interaction = new NaruStreamInteraction(o -> {
        });
        NaruSessionImpl session = newSession(new Routing(interaction));

        NaruTask task = blockedTask(session);
        session.onInputRequested(task);

        Assertions.assertEquals(NaruTaskStatus.FAILED, task.status(),
                "an unanswerable question must terminate the task, not park it forever");
    }

    @Test
    public void closingTheSessionUnblocksAWaitingTask() {
        FakeHost host = new FakeHost();
        NaruStreamInteraction interaction = new NaruStreamInteraction(o -> {
        }, host);
        NaruSessionImpl session = newSession(new Routing(interaction));

        NaruTask task = blockedTask(session);
        session.onInputRequested(task);
        interaction.close();

        Assertions.assertEquals(NaruTaskStatus.FAILED, task.status(),
                "shutting the host down must not leave a worker blocked on a question nobody will answer");
    }

    @Test
    public void aThrowingHostDoesNotBreakTheSession() {
        NaruStreamInteraction interaction = new NaruStreamInteraction(o -> {
        }, r -> {
            throw new IllegalStateException("host is broken");
        });
        NaruSessionImpl session = newSession(new Routing(interaction));

        NaruTask task = blockedTask(session);
        session.onInputRequested(task);

        Assertions.assertEquals(NaruTaskStatus.FAILED, task.status());
        // and the channel still works afterwards
        List<NaruOutput> got = new CopyOnWriteArrayList<>();
        NaruStreamInteraction ok = new NaruStreamInteraction(got::add);
        ok.write(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("still alive"));
        Assertions.assertEquals(1, got.size());
    }

    private static NaruTask blockedTask(NaruSessionImpl session) {
        NaruTask task = session.newTask(NaruTaskSpec.of().statements("noop"));
        task.requestInput(NMsg.ofC("what is your answer?"));
        return task;
    }

    private static NaruSessionImpl newSession(NaruInteraction interaction) {
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-stream-input"));
        return new NaruSessionImpl(agent, agent.projectDirectory(),
                interaction, false, null, null, null, null);
    }
}
