package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.context.NaruCompactionException;
import net.thevpc.naru.api.context.NaruCompactionResult;
import net.thevpc.naru.api.context.NaruCompactors;
import net.thevpc.naru.api.model.NaruContextSpec;
import net.thevpc.naru.api.model.NaruContextViews;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public facade, with no model anywhere.
 *
 * <p>Everything asserted here stops before the summarizer would run -- either because there is
 * nothing old enough to compact, or because there is no usable model. What it pins down is the
 * part that has to work regardless: the call reaches a compactor, the task reaches the
 * summarizer, and nothing is written to the task.
 */
class NaruCompactorsFacadeTest {

    private NaruSession session;

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

    @BeforeEach
    public void setUp() {
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-compactors-facade-" + System.nanoTime()));
        session = new NaruSessionImpl(agent, agent.projectDirectory(), null, true,
                NOOP_LISTENER, null, null, null);
    }

    @AfterEach
    public void tearDown() {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private static List<NaruMessage> items(int count) {
        List<NaruMessage> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(NaruMessage.user("message " + i).setTurnBoundary(i % 2 == 0));
        }
        return out;
    }

    @Test
    void compactionIsInstalledInASessionThatHasTheExtension() {
        assertTrue(NaruCompactors.isInstalled(session));
        assertNotNull(NaruCompactors.find(session));
    }

    @Test
    void aViewShorterThanItsWindowIsLeftAloneRatherThanReportedAsAFailure() {
        // A short conversation is not a failed compaction, and the difference shows up in
        // every caller that branches on the outcome.
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.setHistory(items(3));
        NaruCompactionResult r = NaruCompactors.preview(task,
                NaruContextSpec.of(100_000, net.thevpc.naru.api.model.NaruWindowSpec.lastItems(50)));
        assertEquals(NaruCompactionResult.Outcome.NOTHING_TO_COMPACT, r.outcome());
        assertEquals(3, task.history().size(), "nothing to compact means nothing is written");
    }

    @Test
    void previewLeavesTheTaskExactlyAsItWas() {
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.setHistory(items(30));
        int before = task.history().size();
        NaruContextSpec spec = NaruContextSpec.of(100_000,
                net.thevpc.naru.api.model.NaruWindowSpec.lastItems(4));
        // No model is configured in this harness, so the attempt fails -- which is the point:
        // a failed preview must still leave the task untouched.
        assertThrows(NaruCompactionException.class, () -> NaruCompactors.preview(task, spec));
        assertEquals(before, task.history().size());
        assertTrue(task.history().stream().noneMatch(NaruMessage::isExcluded));
    }

    @Test
    void previewOverAnExplicitViewDoesNotWriteTheViewIntoTheTask() {
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.setHistory(items(2));
        // A view the caller built by hand, which has nothing to do with the task's history.
        // Summarizing it must not touch the task: the task is a handle, not the subject.
        assertThrows(NaruCompactionException.class, () -> NaruCompactors.preview(task,
                items(30),
                NaruContextSpec.of(100_000,
                        net.thevpc.naru.api.model.NaruWindowSpec.lastItems(4))));
        assertEquals(2, task.history().size());
    }

    @Test
    void theSessionOnlyPreviewFailsWithAnExplanationNotAnInternalError() {
        // This compactor summarizes through a task, so the session-only overload cannot carry
        // the call through. It has to say so: with no task there is no current model either, so
        // which of the two complaints comes first depends on the configuration.
        NaruCompactionException e = assertThrows(NaruCompactionException.class,
                () -> NaruCompactors.preview(session, items(30),
                        NaruContextSpec.of(100_000,
                                net.thevpc.naru.api.model.NaruWindowSpec.lastItems(4))));
        String message = e.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("model") || message.contains("task"), message);
        assertTrue(message.length() > 20, "a caller needs an actionable message: " + message);
    }

    @Test
    void nullsAreRejectedWithAnExplanation() {
        assertThrows(NaruCompactionException.class,
                () -> NaruCompactors.preview((NaruTask) null, NaruContextSpec.keepAll()));
        assertThrows(NaruCompactionException.class,
                () -> NaruCompactors.compact((NaruTask) null, NaruContextSpec.keepAll()));
        assertThrows(NaruCompactionException.class, () -> NaruCompactors.find(null));
        assertTrue(!NaruCompactors.isInstalled(null));
    }

    @Test
    void theContextViewIsWhatALiveTaskSees() {
        // The facade's own contract: a caller that builds its input from history instead of the
        // view would summarize content a summary already stands in for.
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.setHistory(items(5));
        assertEquals(5, NaruContextViews.contextView(task.history()).size());
    }

    /** A listener that ignores everything, so these tests can stay about the facade. */
    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }

        @Override
        public void sessionStarted(NaruSession naruSession) {
        }

        @Override
        public void sessionStopped(NaruSession naruSession) {
        }
    };
}