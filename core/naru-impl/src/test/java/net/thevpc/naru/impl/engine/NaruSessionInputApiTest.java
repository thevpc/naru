package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruPendingInput;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * E3: the questions a host with more than one task on screen has to ask, reachable from the
 * {@link NaruSession} interface rather than from an implementation class it has no business
 * holding.
 * <p>
 * A terminal has one foreground and cannot be wrong about it, which is why none of this was ever
 * needed before. A desktop shows several parked tasks at once: it must know who is waiting, answer
 * one of them by id, and know whether the session may be put to sleep — and every one of those
 * answers has to come from the engine, because the engine owns the state.
 */
public class NaruSessionInputApiTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    /** Records the request the way a headless host would; it never answers, so the task stays parked. */
    private static NaruInteraction silentHost(List<NaruInputRequest> seen) {
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
                seen.add(request);
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

    private static NaruSession newSession(List<NaruInputRequest> seen) {
        NaruWorkspace agent = NaruWorkspace.of();
        NPath dir = NPath.ofTempFolder("naru-session-input-api");
        agent.projectDirectory(dir);
        return agent.newSession()
                .directory(dir)
                .interaction(silentHost(seen))
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
    }

    /**
     * A task parked on input, registered with the session the same way the scheduler registers it,
     * so the pending map has something in it. Returns the session as the interface: everything the
     * test then calls has to be reachable that way.
     */
    private static NaruTask parkOne(NaruSession s, NaruSessionImpl impl) {
        NaruTask task = s.newTask(NaruTaskSpec.of().statements("noop"));
        task.requestInput(NMsg.ofC("which one?"));
        impl.onInputRequested(task);
        return task;
    }

    @Test
    void aHostHoldingOnlyTheInterfaceCanSeeWhoIsWaiting() {
        List<NaruInputRequest> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        NaruSessionImpl impl = (NaruSessionImpl) newSession(seen);
        NaruSession s = impl;

        Assertions.assertFalse(s.hasLiveTasks(), "a session with no tasks says so");
        Assertions.assertTrue(s.pendingInputs().isEmpty(), "and nothing is waiting");

        NaruTask task = parkOne(s, impl);
        Assertions.assertTrue(s.hasLiveTasks(), "a parked task is still a live task");
        List<NaruPendingInput> pending = s.pendingInputs();
        Assertions.assertEquals(1, pending.size(), "the engine knows the one request it is holding");
        Assertions.assertEquals(task.id(), pending.get(0).taskId());
        Assertions.assertEquals("which one?", pending.get(0).prompt());
        Assertions.assertNotNull(pending.get(0).requestId(),
                "without an id the host cannot answer this one rather than some other");
    }

    @Test
    void answeringByRequestIdIsExactAndOnlyWorksOnce() {
        List<NaruInputRequest> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        NaruSessionImpl impl = (NaruSessionImpl) newSession(seen);
        NaruSession s = impl;
        parkOne(s, impl);

        String id = s.pendingInputs().get(0).requestId();
        Assertions.assertTrue(s.deliverPendingInput(id, "42"), "the request is there to be answered");
        Assertions.assertFalse(s.deliverPendingInput(id, "again"),
                "a second answer to the same id is refused, not queued behind the first");
        Assertions.assertFalse(s.deliverPendingInput("no-such-id", "x"),
                "an unknown id says so instead of dropping the line");
        Assertions.assertTrue(s.pendingInputs().isEmpty(), "answering removes it from the pending set");
    }

    /**
     * The two-argument and three-argument doors must not disagree: the idempotency key is the only
     * thing standing between a browser that retries a lost POST and a task that gets two answers.
     */
    @Test
    void theIdempotentDoorAnswersTheSameRequest() {
        List<NaruInputRequest> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        NaruSessionImpl impl = (NaruSessionImpl) newSession(seen);
        NaruSession s = impl;
        parkOne(s, impl);

        String id = s.pendingInputs().get(0).requestId();
        Assertions.assertTrue(s.deliverPendingInput(id, "42", "key-1"));
        Assertions.assertFalse(s.deliverPendingInput(id, "42", "key-1"),
                "the same request id is spent, whatever key comes with it");
    }

    @Test
    void hibernateEligibilityIsTheEnginesAnswerNotAGuess() {
        List<NaruInputRequest> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        NaruSessionImpl impl = (NaruSessionImpl) newSession(seen);
        NaruSession s = impl;

        Assertions.assertFalse(s.isHibernateEligible(),
                "an empty session is not eligible: there is nothing to wake up later");

        NaruTask task = parkOne(s, impl);
        Assertions.assertEquals(NaruTaskStatus.BLOCKED_ON_INPUT, task.status());
        Assertions.assertTrue(s.isHibernateEligible(),
                "every task parked on input is exactly the state hibernate is for");

        s.deliverPendingInput(s.pendingInputs().get(0).requestId(), "42");
        // answering leaves the task no longer parked on input, which is the case the host must not
        // get wrong: it is the difference between a clean sleep and an IllegalStateException
        Assertions.assertFalse(s.isHibernateEligible(),
                "once a task is off the input park it is no longer quiescent");
    }

    @Test
    void theVersionHandleIsScopedToThisSession() {
        List<NaruInputRequest> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        NaruSession s = (NaruSessionImpl) newSession(seen);
        Assertions.assertNotNull(s.versionStore());
        Assertions.assertTrue(s.versionStore().list().isEmpty(),
                "a session that never committed has no versions, and saying so must not throw");
        Assertions.assertFalse(s.versionStore().head().isPresent(),
                "and no HEAD to point at");
        Assertions.assertFalse(s.versionStore().find("1").isPresent());
        Assertions.assertThrows(IllegalStateException.class,
                () -> s.versionStore().commit("first", null, null),
                "a write has to say why it cannot happen, not fail in a store three layers down");
    }
}
