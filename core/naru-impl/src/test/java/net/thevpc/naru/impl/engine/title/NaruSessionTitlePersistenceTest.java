package net.thevpc.naru.impl.engine.title;

import net.thevpc.naru.api.agent.NaruSessionNameSource;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.store.NaruSessionData;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What the store has to keep, because a session outlives the process that titled it.
 *
 * <p>{@code titleAttempts} is incremented and written <b>before</b> the model is called, so a
 * crash mid-call costs one of the three allowed attempts rather than leaving a counter that a
 * restart wipes clean -- without that, a session whose provider is unhealthy buys a model call
 * on every message, forever. The other half is the legacy shape: records written before
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

    @Test
    public void anAppliedAutoTitleSurvivesTheRestart() {
        NPath dir = TitlingHarness.tempProject();
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        NaruSessionImpl session = TitlingHarness.session(dir, probe, true, UUID);
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.await(() -> session.nameSource() == NaruSessionNameSource.AUTO);
        session.save();

        TitlingHarness.Probe secondProbe = new TitlingHarness.Probe();
        NaruSessionImpl again = TitlingHarness.session(dir, secondProbe, true, UUID);

        Assertions.assertEquals(TitlingHarness.ANSWER, again.name());
        Assertions.assertEquals(NaruSessionNameSource.AUTO, again.nameSource(),
                "a title is part of the session, not of the process that asked for it");
        Assertions.assertEquals(1, stored(dir).titleAttempts());
    }

    /**
     * The window the counter exists for: the call is running, nothing has been named yet, and
     * the attempt is already on disk.
     */
    @Test
    public void theAttemptIsOnDiskBeforeTheAnswerArrives() {
        NPath dir = TitlingHarness.tempProject();
        TitlingHarness.Probe probe = new TitlingHarness.Probe();
        probe.delayMillis = 600;
        NaruSessionImpl session = TitlingHarness.session(dir, probe, true, UUID);
        NaruTask task = TitlingHarness.mainTask(session);

        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        TitlingHarness.await(() -> probe.calls.get() == 1);
        session.save();

        NaruSessionData mid = stored(dir);
        Assertions.assertEquals(1, mid.titleAttempts(),
                "a process killed during the call must not come back with a full counter");
        Assertions.assertEquals(NaruSessionNameSource.PLACEHOLDER, mid.nameSource(),
                "the name is only written once the answer has been committed");

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
        TitlingHarness.assertQuietlyNothing();
        Assertions.assertEquals(0, restartProbe.calls.get(),
                "reading a legacy record as a placeholder would let a restart rename the session");
    }
}
