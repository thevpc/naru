package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.scheduler.NaruTaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code session.tasks()} is a flat collection in whatever order the registry happens to iterate;
 * the monitor draws a tree, and every interesting case in that conversion — a child whose parent is
 * gone, two roots, a cycle — is something a live session will not produce on request. So the
 * arrangement is a pure function and is tested as one.
 */
class TaskTreeTest {

    private static NaruSessionModel.TaskRow row(long id, long parentId) {
        return new NaruSessionModel.TaskRow(id, "t" + id, NaruTaskStatus.READY, false,
                parentId, false, 0, Instant.EPOCH, null, 0);
    }

    private static List<Long> ids(List<NaruSessionModel.TaskRow> rows) {
        List<Long> out = new ArrayList<>();
        for (NaruSessionModel.TaskRow r : rows) {
            out.add(r.id());
        }
        return out;
    }

    @Test
    void childrenFollowTheirParentAndDeeperRowsSortByIds() {
        // deliberately fed in reverse: the registry's order is not the display's order
        List<NaruSessionModel.TaskRow> in = List.of(row(30, 10), row(20, 10), row(10, -1));
        List<NaruSessionModel.TaskRow> out = TaskTree.arrange(in);

        assertEquals(List.of(10L, 20L, 30L), ids(out), "a spawn is drawn under the task that made it");
        assertEquals(0, out.get(0).depth());
        assertEquals(1, out.get(1).depth());
        assertEquals(1, out.get(2).depth());
    }

    @Test
    void aGrandchildIsNestedNotSiblings() {
        List<NaruSessionModel.TaskRow> out = TaskTree.arrange(
                List.of(row(10, -1), row(11, 10), row(12, 11)));
        assertEquals(List.of(10L, 11L, 12L), ids(out));
        assertEquals(List.of(0, 1, 2),
                List.of(out.get(0).depth(), out.get(1).depth(), out.get(2).depth()));
    }

    @Test
    void aChildWhoseParentDeregisteredIsARootNotADisappearedRow() {
        // the common case, not an error: a spawn finishes and its child keeps running
        List<NaruSessionModel.TaskRow> out = TaskTree.arrange(List.of(row(7, 3), row(9, -1)));
        assertEquals(List.of(7L, 9L), ids(out), "a live task must never vanish from the monitor");
        assertEquals(0, out.get(0).depth());
        assertEquals(0, out.get(1).depth());
    }

    @Test
    void aParentCycleDrawsEveryTaskOnceInsteadOfOverflowing() {
        // not reachable through the engine today, and exactly why recursion is not used here: ids
        // are assigned by the session, so nothing stops two tasks naming each other
        List<NaruSessionModel.TaskRow> out = TaskTree.arrange(List.of(row(1, 2), row(2, 1)));
        assertEquals(2, out.size(), "a cycle must drop nobody");
        assertEquals(List.of(1L, 2L), ids(out));
        assertTrue(out.stream().allMatch(r -> r.depth() == 0),
                "both end up as roots, because there is no top of a cycle to nest under");
    }

    @Test
    void aTaskThatIsItsOwnParentIsNotLosingItsChild() {
        List<NaruSessionModel.TaskRow> out = TaskTree.arrange(List.of(row(4, 4), row(5, 4)));
        assertEquals(List.of(4L, 5L), ids(out));
        assertEquals(1, out.get(1).depth(), "#5 is still a child of the row that broke the cycle");
    }

    @Test
    void nothingIsLostOrDuplicated() {
        List<NaruSessionModel.TaskRow> in = new ArrayList<>();
        for (long i = 1; i <= 40; i++) {
            in.add(row(i, i % 7 == 0 ? -1 : (i / 7) * 7));
        }
        List<NaruSessionModel.TaskRow> out = TaskTree.arrange(in);
        assertEquals(in.size(), out.size());
        assertEquals(out.size(), out.stream().map(NaruSessionModel.TaskRow::id).distinct().count(),
                "each task appears exactly once, whatever the parent chain looks like");
    }

    @Test
    void anEmptyPollIsAnEmptyTree() {
        assertEquals(List.of(), TaskTree.arrange(List.of()));
    }
}
