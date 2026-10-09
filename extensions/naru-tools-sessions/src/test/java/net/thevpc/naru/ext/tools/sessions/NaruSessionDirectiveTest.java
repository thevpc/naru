package net.thevpc.naru.ext.tools.sessions;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.cmdline.NaruNArgCompleteResolver;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.cmdline.NArgCompleteCandidate;
import net.thevpc.nuts.cmdline.NArgCompletePosition;
import net.thevpc.nuts.cmdline.NArgCompleteResult;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives /session the way the REPL does, so a subcommand that is broken is caught here
 * rather than by typing it at a prompt.
 */
@Timeout(60)
public class NaruSessionDirectiveTest {

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void sessionStarted(NaruSession session) {
        }

        @Override
        public void sessionStopped(NaruSession session) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }
    };

    private NPath projectDir;
    private NaruSessionImpl session;

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Throwable e) {
            System.err.println("[first workspace attempt failed]");
            e.printStackTrace();
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Throwable ignored) {
                System.err.println("[second workspace attempt failed]");
                ignored.printStackTrace();
            }
        }
    }

    @BeforeEach
    public void setUp() {
        projectDir = NPath.ofTempFolder("naru-session-directive-" + System.nanoTime());
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        session = new NaruSessionImpl(agent, projectDir, new NaruStreamInteraction(o -> {
        }), true, NOOP_LISTENER, null, null, null);
        session.name("arch-review");
    }

    private NaruTask task() {
        return session.newTask(NaruTaskSpec.of());
    }

    private NaruStmtResult call(String argument) {
        NaruTask task = task();
        NaruDirective directive = session.registry().findDirective("session")
                .orElseThrow(() -> new AssertionError("no /session directive"));
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("session", argument, task);
        return directive.execute(ctx);
    }

    @Test
    public void listingSavedSessionsSucceeds() {
        session.save();
        assertFalse(session.sessionStoreManager().list().isEmpty(), "fixture should save a session");

        NaruStmtResult r = call("list");

        assertEquals(0, r.exitCode(), "/session list must not fail: " + r.errorValue());
        assertTrue(r.successValue() instanceof String, "expected the listing as a string value");
        assertFalse(((String) r.successValue()).isEmpty(), "an empty listing means nothing was printed");
    }

    /**
     * The catalogue is shared by delete/load/continue: if one unreadable session file on
     * disk can break listing, every one of those stops working too.
     */
    @Test
    public void listingSurvivesAnUnreadableSessionFolder() {
        session.save();
        NPath junk = projectDir.resolve(".naru/local/sessions/aaaaaaaa-bogus");
        junk.mkParentDirs().mkdirs();
        junk.resolve("session.tson").writeText(net.thevpc.nuts.text.NText.ofPlain("{ this is not tson"));

        NaruStmtResult r = call("list");

        assertEquals(0, r.exitCode(), "one bad folder must not break /session list: " + r.errorValue());
    }

    @Test
    public void loadAndDeleteCompleteTheirSessionArgument() {
        session.save();

        for (String sub : new String[]{"load", "delete"}) {
            List<String> values = complete("/session", sub, "");
            assertTrue(values.contains("arch-review"),
                    "/" + sub + " must offer saved session names, got " + values);
        }
    }

    private List<String> complete(String... words) {
        NCmdLine cmdLine = NCmdLine.of(words);
        int last = words.length - 1;
        NArgCompletePosition pos = NArgCompletePosition.of(last, words[last].length(), 0);
        NArgCompleteResult result = new NaruNArgCompleteResolver(session)
                .resolveCandidates(cmdLine.completePosition(pos), pos);
        List<String> out = new ArrayList<>();
        for (NArgCompleteCandidate c : result.candidates()) {
            out.add(c.value());
        }
        return out;
    }

    /**
     * A stop-the-world that waits for the workers cannot be requested by a worker: the
     * thread it is waiting for is the one waiting for it. /session restore used to do
     * exactly that and wedged the whole scheduler.
     */
    @Test
    public void restoreFromInsideATaskDoesNotDeadlock() {
        NaruTask t = session.newTask(NaruTaskSpec.of().statements("/session restore"));
        session.start();
        session.waitFor();
        assertEquals(NaruTaskStatus.DONE, t.status(),
                "the restore task must complete instead of hanging the scheduler");
    }

    @Test
    public void saveFromInsideATaskDoesNotDeadlock() {
        NaruTask t = session.newTask(NaruTaskSpec.of().statements("/session save"));
        session.start();
        session.waitFor();
        assertEquals(NaruTaskStatus.DONE, t.status(),
                "the save task must complete instead of hanging the scheduler");
    }

    @Test
    public void listFromInsideATaskWorks() {
        session.save();
        NaruTask t = session.newTask(NaruTaskSpec.of().statements("/session list"));
        session.start();
        session.waitFor();
        assertEquals(NaruTaskStatus.DONE, t.status(),
                "the list task must complete");
    }
}
