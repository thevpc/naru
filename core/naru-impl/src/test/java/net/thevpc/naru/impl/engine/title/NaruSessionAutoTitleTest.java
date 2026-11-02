package net.thevpc.naru.impl.engine.title;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.agent.NaruSessionNameSource;
import net.thevpc.naru.api.agent.NaruSessionTitler;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The automatic trigger: when a session may spend a model call on its own name, and what
 * happens when it should not.
 *
 * <p>The rule underneath these tests is that titling is a convenience. Anything that could
 * interrupt the user -- an error, a hang, a retry on every message, a name that overwrote
 * theirs -- is a bug even when the title came out nicely.
 */
public class NaruSessionAutoTitleTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    // ------------------------------------------------------------------ the placeholder

    @Test
    public void anUnnamedSessionIsReadableRatherThanUnnamed() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl session = TitlingHarness.session(TitlingHarness.tempProject(), probe, true);
        Assertions.assertTrue(session.name().matches("Session \\d+"),
                "a session must be listable before anyone names it: " + session.name());
        Assertions.assertEquals(NaruSessionNameSource.PLACEHOLDER, session.nameSource(),
                "the placeholder is the only state the automatic title is allowed to overwrite");
    }

    @Test
    public void twoUnnamedSessionsInOneStoreGetDifferentNumbers() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NPath dir = TitlingHarness.tempProject();
        NaruSession a = TitlingHarness.session(dir, probe, true);
        NaruSession b = TitlingHarness.session(dir, probe, true);
        Assertions.assertNotEquals(a.name(), b.name(),
                "the counter is per store, so a placeholder cannot label two sessions");
    }

    // ------------------------------------------------------------------ the trigger

    @Test
    public void theFirstUserMessageBuysExactlyOneTitle() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl session = TitlingHarness.session(TitlingHarness.tempProject(), probe, true);
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.await(() -> session.nameSource() == NaruSessionNameSource.AUTO);

        Assertions.assertEquals(TitlingHarness.ANSWER, session.name());
        Assertions.assertEquals(1, probe.calls.get());

        // a second message must not spend a second call on a session that is already named
        task.addHistory(NaruMessage.user("and then what"));
        TitlingHarness.assertQuietlyNothing();
        Assertions.assertEquals(1, probe.calls.get());
    }

    @Test
    public void aSessionRestoredWithoutAForegroundPointerIsStillTitled() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl session = TitlingHarness.session(TitlingHarness.tempProject(), probe, true);
        NaruTask task = TitlingHarness.unmarkedTask(session);

        task.addHistory(NaruMessage.user("how do I rotate a log file with logrotate"));
        TitlingHarness.await(() -> session.nameSource() == NaruSessionNameSource.AUTO);

        Assertions.assertEquals(TitlingHarness.ANSWER, session.name(),
                "a restored session the host never marked foreground is still the user's "
                        + "conversation and must not keep its placeholder forever");
    }

    @Test
    public void withNoForegroundPointerOnlyTheFirstTaskNamesTheSession() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl session = TitlingHarness.session(TitlingHarness.tempProject(), probe, true);
        TitlingHarness.unmarkedTask(session);
        NaruTask second = TitlingHarness.unmarkedTask(session);

        second.addHistory(NaruMessage.user("how do I rotate a log file with logrotate"));
        TitlingHarness.assertQuietlyNothing();

        Assertions.assertEquals(0, probe.calls.get(),
                "the fallback to the first task must not turn every task into a titler");
        Assertions.assertNotEquals(NaruSessionNameSource.AUTO, session.nameSource());
    }

    @Test
    public void theRenameReachesSessionListenersWithItsSource() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl session = TitlingHarness.session(TitlingHarness.tempProject(), probe, true);
        List<NaruSessionNameSource> sources = new CopyOnWriteArrayList<>();
        List<String> titles = new CopyOnWriteArrayList<>();
        session.addSessionListener(new NaruSessionListener() {
            @Override
            public void onEventAppended(net.thevpc.naru.api.scheduler.NaruEvent e) {
            }

            @Override
            public void sessionStarted(NaruSession s) {
            }

            @Override
            public void sessionStopped(NaruSession s) {
            }

            @Override
            public void onSessionReloaded(NaruSession s) {
            }

            @Override
            public void onSessionRenamed(NaruSession s, String oldName, String newName,
                                         NaruSessionNameSource source) {
                sources.add(source);
                titles.add(newName);
            }
        });
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.await(() -> !titles.isEmpty());

        Assertions.assertEquals(List.of(NaruSessionNameSource.AUTO), sources);
        Assertions.assertEquals(TitlingHarness.ANSWER, titles.get(0));
    }

    // ------------------------------------------------------------------ who outranks whom

    @Test
    public void aSessionTheUserAlreadyNamedIsNeverTitled() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl session = TitlingHarness.session(TitlingHarness.tempProject(), probe, true);
        session.rename("my migration notes", NaruSessionNameSource.USER);
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition"));
        TitlingHarness.assertQuietlyNothing();
        Assertions.assertEquals(0, probe.calls.get(),
                "a name a human typed is not a placeholder waiting to be replaced");
        Assertions.assertEquals("my migration notes", session.name());
    }

    /**
     * The race the version guard exists for: the user renames while the model is answering.
     * The title is discarded rather than applied over them.
     */
    @Test
    public void aRenameThatLandsMidCallWins() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        probe.delayMillis = 500;
        NaruSessionImpl session = TitlingHarness.session(TitlingHarness.tempProject(), probe, true);
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.await(() -> probe.calls.get() == 1);
        session.rename("my migration notes", NaruSessionNameSource.USER);
        TitlingHarness.await(() -> probe.answered);
        // the model has answered by now; give the worker the chance to commit, then check it did not
        TitlingHarness.assertQuietlyNothing();

        Assertions.assertEquals("my migration notes", session.name());
        Assertions.assertEquals(NaruSessionNameSource.USER, session.nameSource(),
                "the answer that arrived after the rename is dropped, not queued behind it");
    }

    // ------------------------------------------------------------------ opting out

    @Test
    public void aHostThatSaidNoNeverSpendsACall() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl session = TitlingHarness.session(TitlingHarness.tempProject(), probe, false);
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition"));
        TitlingHarness.assertQuietlyNothing();
        Assertions.assertEquals(0, probe.calls.get(),
                "no setting may put a model call into a product whose host opted out");
        Assertions.assertEquals(NaruSessionNameSource.PLACEHOLDER, session.nameSource());
    }

    @Test
    public void theProjectSettingTurnsItOffForEveryone() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl session = TitlingHarness.session(TitlingHarness.tempProject(), probe, true);
        session.setProjectEnv(NaruSessionTitler.AUTO_CONFIG_KEY, NElement.ofBoolean(false),
                NaruVisibility.PRIVATE);
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition"));
        TitlingHarness.assertQuietlyNothing();
        Assertions.assertEquals(0, probe.calls.get());
    }

    // ------------------------------------------------------------------ attempts

    @Test
    public void aFailedAttemptIsCountedBeforeTheCallSoRetriesAreBounded() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        probe.fail = true;
        NaruSessionImpl session = TitlingHarness.session(TitlingHarness.tempProject(), probe, true);
        session.setProjectEnv(NaruSessionTitler.MAX_ATTEMPTS_CONFIG_KEY, NElement.ofNumber(1),
                NaruVisibility.PRIVATE);
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.await(() -> probe.calls.get() == 1);

        // The session is still untitled, so the trigger fires again -- and must not.
        task.addHistory(NaruMessage.user("still here"));
        TitlingHarness.assertQuietlyNothing();
        Assertions.assertEquals(1, probe.calls.get(),
                "the attempt is spent even when it failed; a session that cannot be titled "
                        + "must not buy a model call on every message");
        Assertions.assertEquals(NaruSessionNameSource.PLACEHOLDER, session.nameSource());
    }

    @Test
    public void anUntitledSessionGetsAnotherTryOnTheNextMessage() {
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        probe.fail = true;
        NaruSessionImpl session = TitlingHarness.session(TitlingHarness.tempProject(), probe, true);
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.await(() -> probe.calls.get() == 1);

        probe.fail = false;
        task.addHistory(NaruMessage.user("actually, on lvm2"));
        TitlingHarness.await(() -> session.nameSource() == NaruSessionNameSource.AUTO);

        Assertions.assertEquals(TitlingHarness.ANSWER, session.name(),
                "the first message may be a dead end; the second is allowed to name the session");
    }
}
