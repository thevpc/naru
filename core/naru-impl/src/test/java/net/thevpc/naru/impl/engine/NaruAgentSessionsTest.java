package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionBuilder;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The agent as a host of many sessions.
 * <p>
 * Two things are being pinned down here. First, that one JVM can run several sessions at
 * once and still tell them apart — the reason the interaction seam exists. Second, that
 * the agent's live-session view is safe to read and write from the concurrent places a
 * server would do so from: it used to be a plain {@code ArrayList}.
 */
public class NaruAgentSessionsTest {

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

    private static NaruAgentImpl newAgent() {
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-agent-sessions"));
        return agent;
    }

    /** A started session whose task cannot finish on its own. */
    private static NaruSession blocked(NaruAgent agent, String name) {
        NaruSession s = agent.newSession().interaction(silent()).build();
        s.newTask(NaruTaskSpec.of().statements("noop")).requestInput(NMsg.ofC("still there?"));
        return s.start();
    }

    private static NaruStreamInteraction silent() {
        return new NaruStreamInteraction(o -> {
        });
    }

    /**
     * Each task is left blocked on input on purpose. A task that runs to completion takes
     * its whole session down with it, so an "all five are still listed" assertion would
     * only be measuring how fast the work finished.
     */
    @Test
    public void anAgentCanRunManySessionsAtOnceAndSeeThemAll() {
        NaruAgent agent = newAgent();
        List<NaruSession> made = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            NaruSession s = agent.newSession().interaction(silent()).build();
            s.newTask(NaruTaskSpec.of().statements("noop"))
                    .requestInput(NMsg.ofC("still there?"));
            made.add(s.start());
        }

        List<NaruSession> live = agent.sessions();
        Assertions.assertEquals(5, live.size(), "every started session must be visible, got " + live.size());
        for (NaruSession s : made) {
            Assertions.assertTrue(live.contains(s), "a started session is missing from the agent's view");
            Assertions.assertTrue(s.isRunning(), "each session runs independently of the others");
        }
    }

    @Test
    public void aSessionIsFoundByItsId() {
        NaruAgent agent = newAgent();
        NaruSession session = blocked(agent, "lookup");

        Assertions.assertSame(session, agent.session(session.uuid()));
    }

    @Test
    public void anUnknownOrNullIdFindsNothing() {
        NaruAgent agent = newAgent();
        Assertions.assertNull(agent.session("no-such-id"));
        Assertions.assertNull(agent.session(null), "a null id must not blow up on a live server");
    }

    @Test
    public void aStoppedSessionLeavesTheAgentsView() {
        NaruAgent agent = newAgent();
        NaruSession session = blocked(agent, "stopping");
        String id = session.uuid();
        Assertions.assertSame(session, agent.session(id));

        session.stop();

        Assertions.assertNull(agent.session(id), "a stopped session must not linger in the live set");
        Assertions.assertFalse(agent.sessions().contains(session));
    }

    @Test
    public void theReturnedListIsASnapshotTheCallerCannotCorrupt() {
        NaruAgent agent = newAgent();
        agent.newSession().interaction(silent()).task(NaruTaskSpec.of().statements("noop")).build().start();

        List<NaruSession> snapshot = agent.sessions();
        try {
            snapshot.clear();
            Assertions.fail("callers must not be able to empty the agent's session set");
        } catch (UnsupportedOperationException expected) {
            // exactly right: it is a copy, not a window onto the real thing
        }
        Assertions.assertEquals(1, agent.sessions().size(), "the agent's own view is unaffected");
    }

    /**
     * The regression this guards: sessions are added and removed from lifecycle callbacks
     * that run on their own threads, so concurrent start/stop used to corrupt the list.
     */
    @Test
    public void concurrentSessionChurnIsSafe() throws Exception {
        NaruAgent agent = newAgent();
        int threads = 8;
        int perThread = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<NaruSession> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        NaruSession s = agent.newSession().interaction(silent())
                                .task(NaruTaskSpec.of().statements("noop")).build().start();
                        seen.add(s);
                        agent.sessions();
                        agent.session(s.uuid());
                        s.stop();
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        Assertions.assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "workers did not finish");
        Assertions.assertTrue(errors.isEmpty(), "concurrent churn raised " + errors);

        Assertions.assertEquals(threads * perThread, seen.size());
        Assertions.assertTrue(agent.sessions().isEmpty(),
                "every session was stopped, so none may be left behind, got " + agent.sessions().size());
    }

    /**
     * The builder must be the only way in, and it must not have started anything yet:
     * a host that wants to configure a session before running it needs that gap.
     */
    @Test
    public void buildDoesNotStartTheSession() {
        NaruAgent agent = newAgent();
        NaruSession session = agent.newSession().interaction(silent())
                .task(NaruTaskSpec.of().statements("noop")).build();
        Assertions.assertTrue(agent.sessions().isEmpty(), "build must not start a session");
        session.start();
        Assertions.assertEquals(1, agent.sessions().size());
    }

    @Test
    public void theSessionDirectoryDefaultsToTheProjectDirectory() {
        NaruAgentImpl agent = newAgent();
        NaruSessionBuilder builder = agent.newSession().interaction(silent());
        NaruSession session = builder.task(NaruTaskSpec.of().statements("noop")).build();
        Assertions.assertNotNull(session.uuid());
        Assertions.assertEquals(agent.projectDirectory(), session.projectDir(),
                "a session with no explicit directory belongs to the project");
    }
}
