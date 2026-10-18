package net.thevpc.naru.desktop.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the flat list {@code session.tasks()} returns into the tree the monitor draws.
 *
 * <p>Deliberately a pure function over rows rather than something that walks the live session: the
 * poll already read the engine once per tick, the pane must not reach for {@code NaruTask} objects
 * at all, and a pure arrange is the one piece of this feature that can be tested without booting a
 * session — which matters because a stranded child or a parent cycle is exactly what a live session
 * will not hand you on demand.
 *
 * <p>Two things the engine allows and a naive recursion gets wrong: a task whose parent has already
 * deregistered (a spawn routinely outlives nothing, but a child outlives its parent all the time),
 * and a parent chain that loops back on itself. Both come out as roots here rather than as a lost
 * row or a {@code StackOverflowError}, because a monitor that hides a live task is worse than one
 * that draws it at the wrong indent.
 */
public final class TaskTree {

    private TaskTree() {
    }

    /**
     * Order rows depth-first — each parent immediately followed by its children, siblings by
     * ascending id — and stamp each with its depth (roots at 0).
     *
     * @param flat the poll's rows, in any order
     * @return the same rows, rearranged and re-indented; no row lost, none duplicated
     */
    public static List<NaruSessionModel.TaskRow> arrange(List<NaruSessionModel.TaskRow> flat) {
        List<NaruSessionModel.TaskRow> in = new ArrayList<>(flat);
        in.sort(Comparator.comparingLong(NaruSessionModel.TaskRow::id));

        Map<Long, NaruSessionModel.TaskRow> byId = new HashMap<>();
        for (NaruSessionModel.TaskRow r : in) {
            byId.put(r.id(), r);
        }
        Map<Long, List<NaruSessionModel.TaskRow>> childrenOf = new HashMap<>();
        List<NaruSessionModel.TaskRow> roots = new ArrayList<>();
        for (NaruSessionModel.TaskRow r : in) {
            // -1/0 parent ids and a parent that already deregistered both land on null here, and
            // both mean the same thing to a monitor: this row has nobody above it on screen
            NaruSessionModel.TaskRow parent = byId.get(r.parentId());
            if (parent == null || parent == r) {
                roots.add(r);
            } else {
                childrenOf.computeIfAbsent(parent.id(), k -> new ArrayList<>()).add(r);
            }
        }

        List<NaruSessionModel.TaskRow> out = new ArrayList<>(in.size());
        Set<Long> emitted = new HashSet<>();
        // iterative on purpose: a cycle in the parent chain costs a skipped row, never the stack
        List<long[]> stack = new ArrayList<>();      // {id}, paired with depths
        List<Integer> depths = new ArrayList<>();
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.add(new long[]{roots.get(i).id()});
            depths.add(0);
        }
        while (!stack.isEmpty()) {
            int top = stack.size() - 1;
            long id = stack.remove(top)[0];
            int depth = depths.remove(top);
            NaruSessionModel.TaskRow row = byId.get(id);
            if (row == null || !emitted.add(id)) {
                continue;   // already drawn — this is where a cycle stops
            }
            out.add(row.withDepth(depth));
            List<NaruSessionModel.TaskRow> kids = childrenOf.get(id);
            if (kids != null) {
                for (int i = kids.size() - 1; i >= 0; i--) {
                    stack.add(new long[]{kids.get(i).id()});
                    depths.add(depth + 1);
                }
            }
        }
        // anything unreached sits inside a cycle: show it as a root rather than hide it
        for (NaruSessionModel.TaskRow r : in) {
            if (emitted.add(r.id())) {
                out.add(r.withDepth(0));
            }
        }
        return Collections.unmodifiableList(out);
    }
}
