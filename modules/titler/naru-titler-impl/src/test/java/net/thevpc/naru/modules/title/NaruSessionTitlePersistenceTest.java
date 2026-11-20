package net.thevpc.naru.modules.title;

import net.thevpc.naru.api.agent.NaruSessionNameSource;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.title.NaruSessionTitler;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.store.NaruSessionData;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What the store has to keep, because a session outlives the process that titled it.
 *
 * <p>The titler counts its automatic attempts in its own persisted extension state, and writes
 * the count <b>before</b> the model is called, so a crash mid-call costs one of the three
 * allowed attempts rather than leaving a counter that a restart wipes clean -- without that, a
 * session whose provider is unhealthy buys a model call on every message, forever. The count is
 * the feature's, not the session header's: the kernel does not know that titling retries. The
 * other half is the legacy shape: records written before
 * {@code nameSource} existed carry a name and no authority, and reading one as anything other
 * than {@code USER} would let an automatic title overwrite something a human typed.
 */
public class NaruSessionTitlePersistenceTest {

    private static final String UUID = "77777777-7777-7777-7777-777777777777";

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSessionData stored(NPath dir) {
        return NaruSessionData.of(NElementReader.ofTson().read(header(dir)).asObject().get());
    }

    private static NPath header(NPath dir) {
        return dir.resolve(".naru/local/sessions").resolve(UUID).resolve("session.tson");
    }

    /** The titler's own persisted state: its attempt counter lives here, not in the header. */
    private static NPath titlerState(NPath dir) {
        return dir.resolve(".naru/local/sessions").resolve(UUID).resolve("ext").resolve("title.tson");
    }

    @Test
    public void anAppliedAutoTitleSurvivesTheRestart() {
        NPath dir = TitlingHarness.tempProject();
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl session = TitlingHarness.session(dir, probe, true, UUID);
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.modelRequest(session, task);
        TitlingHarness.await(() -> session.nameSource() == NaruSessionNameSource.AUTO);
        session.save();

        TitlingHarness.Probe secondProbe = new TitlingHarness.Probe();
        NaruSessionImpl again = TitlingHarness.session(dir, secondProbe, true, UUID);

        Assertions.assertEquals(TitlingHarness.ANSWER, again.name());
        Assertions.assertEquals(NaruSessionNameSource.AUTO, again.nameSource(),
                "a title is part of the session, not of the process that asked for it");

        NaruTask nextTask = TitlingHarness.mainTask(again);
        nextTask.addHistory(NaruMessage.user("and what about ext4"));
        TitlingHarness.modelRequest(again, nextTask);
        TitlingHarness.assertQuietlyNothing();
        Assertions.assertEquals(0, secondProbe.calls.get(),
                "a session that came back named does not spend a call on being named again");
    }

    /**
     * The window the counter exists for: the call is running, nothing has been named yet, and
     * the attempt is already saved.
     *
     * <p>Asserted as a behaviour rather than as a field, because the count is the feature's own
     * state now. A second session on the same store is what a crash looks like from outside, and
     * the attempt cap is what decides whether it retries.
     */
    @Test
    public void theAttemptIsSavedBeforeTheAnswerArrives() {
        NPath dir = TitlingHarness.tempProject();
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        probe.delayMillis = 600;
        NaruSessionImpl session = TitlingHarness.session(dir, probe, true, UUID);
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.modelRequest(session, task);
        TitlingHarness.await(() -> probe.calls.get() == 1);
        // The attempt is counted in memory and reaches the disk through persist(), which does
        // not wait for its write. The restart therefore waits for the record itself rather
        // than guessing how long a background save needs.
        TitlingHarness.await(() -> titlerState(dir).exists() && header(dir).exists());

        Assertions.assertEquals(NaruSessionNameSource.PLACEHOLDER, stored(dir).nameSource(),
                "the name is only written once the answer has been committed");

        TitlingHarness.Probe crashProbe = new TitlingHarness.Probe();
        NaruSessionImpl restarted = TitlingHarness.session(dir, crashProbe, true, UUID);
        restarted.setProjectEnv(NaruSessionTitler.MAX_ATTEMPTS_CONFIG_KEY, NElement.ofNumber(1),
                NaruVisibility.PRIVATE);
        NaruTask restartedTask = TitlingHarness.mainTask(restarted);
        restartedTask.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.modelRequest(restarted, restartedTask);
        TitlingHarness.assertQuietlyNothing();
        Assertions.assertEquals(0, crashProbe.calls.get(),
                "a process killed during the call must not come back with a full counter");

        TitlingHarness.await(() -> session.nameSource() == NaruSessionNameSource.AUTO);
    }

    /**
     * A placeholder is a name too: keeping it across a restart is what stops a resumed
     * session from quietly taking a second number, and it stays titleable because the
     * automatic trigger only ever overwrites this source.
     */
    @Test
    public void aPlaceholderKeepsItsNumberAndStaysTitleable() {
        NPath dir = TitlingHarness.tempProject();
        NaruSessionImpl session = TitlingHarness.session(dir, new TitlingHarness.Probe(), true, UUID);
        String placeholder = session.name();
        session.save();

        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl again = TitlingHarness.session(dir, probe, true, UUID);
        Assertions.assertEquals(placeholder, again.name(),
                "the store numbers a session once; a restart is not a rename");
        Assertions.assertEquals(NaruSessionNameSource.PLACEHOLDER, again.nameSource());

        NaruTask task = TitlingHarness.mainTask(again);
        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.modelRequest(again, task);
        TitlingHarness.await(() -> again.nameSource() == NaruSessionNameSource.AUTO);
        Assertions.assertEquals(TitlingHarness.ANSWER, again.name());
    }

    @Test
    public void aNameStoredWithoutASourceComesBackAsTheUsersAndIsLeftAlone() {
        NPath dir = TitlingHarness.tempProject();
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl session = TitlingHarness.session(dir, probe, true, UUID);
        session.rename("migration notes", NaruSessionNameSource.USER);
        session.save();

        // the shape an older naru wrote: a name, and no field saying who chose it
        NaruSessionData legacy = stored(dir);
        legacy.nameSource(null);
        NElementWriter.ofTson().write(legacy.toElement(), header(dir));

        TitlingHarness.Probe restartProbe = new TitlingHarness.Probe();
        NaruSessionImpl again = TitlingHarness.session(dir, restartProbe, true, UUID);
        Assertions.assertEquals("migration notes", again.name());
        Assertions.assertEquals(NaruSessionNameSource.USER, again.nameSource(),
                "a name with no source could only have been typed by a human");

        NaruTask task = TitlingHarness.mainTask(again);
        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.modelRequest(again, task);
        TitlingHarness.assertQuietlyNothing();
        Assertions.assertEquals(0, restartProbe.calls.get(),
                "reading a legacy record as a placeholder would let a restart rename the session");
    }
}
