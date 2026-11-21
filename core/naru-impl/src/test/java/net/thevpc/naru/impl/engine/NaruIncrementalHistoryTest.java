package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.scheduler.NaruTaskImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

/**
 * A save appends the new messages when the stored ones are untouched, and rewrites the
 * history when one of them changed in place. Both have to land: the append is the fast path
 * of every turn, the rewrite is what keeps an edit (a compaction marking a message excluded,
 * say) from being lost because the message object is the one already stored.
 */
@Timeout(60)
public class NaruIncrementalHistoryTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSession newSession(NPath dir) {
        NaruWorkspace agent = NaruWorkspace.of();
        agent.projectDirectory(dir);
        return agent.newSession()
                .sessionDir(dir)
                .interaction(new net.thevpc.naru.impl.interaction.NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .build();
    }

    /** The conversation: the engine also records events (a task spawning) in the history. */
    private static List<NaruMessage> said(List<NaruMessage> history) {
        return history.stream()
                .filter(m -> m.getRole() == NaruRole.user || m.getRole() == NaruRole.assistant).toList();
    }

    private static List<String> texts(List<NaruMessage> history) {
        return said(history).stream().map(NaruMessage::getFilteredContent).toList();
    }

    @Test
    public void appendsAndInPlaceEditsBothSurviveAReload() {
        NPath dir = NPath.ofTempFolder("naru-incremental-" + System.nanoTime());
        NaruSession session = newSession(dir);
        NaruTaskImpl task = (NaruTaskImpl) session.newTask(NaruTaskSpec.of().name("chat"));

        task.addHistory(NaruMessage.user("first"));
        task.addHistory(NaruMessage.assistant("second"));
        session.save();

        // the stored messages are untouched: this save only appends
        task.addHistory(NaruMessage.user("third"));
        session.save();

        // the first message changes in place: same object, so only its revision tells
        said(task.history()).get(0).setExcludedBy("compaction-1");
        session.save();

        NaruSession reloaded = newSession(dir);
        reloaded.load(session.uuid());
        NaruTask back = reloaded.findTask(task.id()).orNull();
        Assertions.assertNotNull(back, "the task must come back from the store");
        List<NaruMessage> history = back.history();
        Assertions.assertEquals(List.of("first", "second", "third"), texts(history));
        Assertions.assertEquals("compaction-1", said(history).get(0).getExcludedBy(),
                "an in-place edit of a stored message must be saved");
    }

    @Test
    public void aWokenSessionAppendsToTheHistoryItLoaded() {
        NPath dir = NPath.ofTempFolder("naru-incremental-wake-" + System.nanoTime());
        NaruSession session = newSession(dir);
        NaruTaskImpl task = (NaruTaskImpl) session.newTask(NaruTaskSpec.of().name("chat"));
        task.addHistory(NaruMessage.user("before sleep"));
        session.save();

        // a second session loads it (history read lazily), adds to it and saves
        NaruSession woken = newSession(dir);
        woken.load(session.uuid());
        NaruTaskImpl again = (NaruTaskImpl) woken.findTask(task.id()).orNull();
        again.addHistory(NaruMessage.assistant("after waking"));
        woken.save();

        NaruSession reloaded = newSession(dir);
        reloaded.load(session.uuid());
        Assertions.assertEquals(List.of("before sleep", "after waking"),
                texts(reloaded.findTask(task.id()).orNull().history()));
    }
}
