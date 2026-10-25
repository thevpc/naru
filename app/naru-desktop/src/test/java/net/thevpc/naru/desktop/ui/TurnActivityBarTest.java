package net.thevpc.naru.desktop.ui;

import net.thevpc.naru.api.agent.NaruInputRequest;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.diff.GitChangeSummary;
import net.thevpc.naru.desktop.host.InteractionHost;
import net.thevpc.naru.desktop.model.NaruSessionModel;
import net.thevpc.naru.desktop.model.TurnActivity;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnActivityBarTest {

    @BeforeAll
    static void requireNuts() {
        Nuts.require();
    }

    @Test
    void theSummaryShowsFileCountsInsteadOfToolCountsWhenTheWorktreeHasChanges() {
        TurnActivity activity = new TurnActivity();
        activity.beginTurn();
        GitChangeSummary.Snapshot changes = GitChangeSummary.merge(
                "4\t6\tsrc/A.java\n2\t0\tsrc/B.java\n", "");

        String text = TurnActivityBar.summaryText(changes, activity.snapshot());

        assertTrue(text.contains("2 files"), text);
        assertTrue(text.contains("+6"), text);
        assertTrue(text.contains("&#8722;6"), text);
        assertFalse(text.contains("tool"), text);
    }

    @Test
    void theSummaryTooltipListsEachChangedFileAndItsCounts() {
        TurnActivity activity = new TurnActivity();
        activity.beginTurn();
        GitChangeSummary.Snapshot changes = GitChangeSummary.merge(
                "4\t6\tsrc/A.java\n-\t-\tassets/logo.png\n", "");

        String tip = TurnActivityBar.summaryTooltip(changes, activity.snapshot());

        assertTrue(tip.startsWith("<html>"), tip);
        assertTrue(tip.contains("src/A.java"), tip);
        assertTrue(tip.contains("assets/logo.png"), tip);
        assertTrue(tip.contains("binary"), tip);
        assertTrue(tip.contains("+4"), tip);
    }

    @Test
    void waitingForInputKeepsTheVerbAndSpinnerStillEvenIfTheTaskLooksBusy() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            InteractionHost host = new InteractionHost();
            NPath dir = NPath.ofTempFolder("naru-activity-bar-" + System.nanoTime());
            NaruSessionModel session = new NaruSessionModel(() -> null, dir, host);
            try {
                setField(session, "running", true);
                setField(session, "streamingOutput", true);
                setField(session, "pending", new NaruInputRequest() {
                    @Override
                    public NMsg prompt() {
                        return null;
                    }

                    @Override
                    public NaruTask task() {
                        return null;
                    }

                    @Override
                    public void deliver(String line) {
                    }

                    @Override
                    public void cancel(String reason) {
                    }
                });
                session.turnActivity().beginTurn();

                assertTrue(session.isWaitingForInput());
                assertFalse(session.isWorking(),
                        "an open output block must not keep a parked turn animated");

                TurnActivityBar bar = new TurnActivityBar(session);
                try {
                    assertEquals("Waiting for your reply", bar.activityText());
                    assertFalse(bar.spinnerSpinning(),
                            "a paused turn must not paint a spinner next to the Send button");
                } finally {
                    bar.dispose();
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            } finally {
                session.shutdown();
            }
        });
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = NaruSessionModel.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
