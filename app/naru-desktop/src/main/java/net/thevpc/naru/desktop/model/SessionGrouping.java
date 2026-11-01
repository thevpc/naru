package net.thevpc.naru.desktop.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the two-level Project → Session contract into the rows a one-dimensional session
 * list renders: project headers interleaved with their sessions, a "No project" bucket for
 * standalone sessions and an "Unknown project" bucket for sessions whose project id names
 * no existing project (legal: a public session committed to git may carry an id from its
 * author's machine).
 *
 * <p>Pure data, no Swing: the window feeds it its catalog entries and paints the result.
 * Order rules, in one place on purpose: projects appear in the order the service page
 * gave them (the tree's sort), sessions keep their incoming display order inside every
 * group, the standalone bucket follows the last known project, and the unknown bucket
 * comes last. A group with no visible rows renders no header — except when everything
 * is empty, where the caller shows its own placeholder instead of inventing one here.
 */
public final class SessionGrouping {

    public static final String NO_PROJECT = "No project";
    public static final String UNKNOWN_PROJECT = "Unknown project";

    /** A header row: a project (id != null), the standalone bucket, or the unknown bucket. */
    public record Header(String title, String projectId, boolean standalone, boolean unknown, int count) {
        Header(String title, String projectId, boolean standalone, boolean unknown) {
            this(title, projectId, standalone, unknown, 0);
        }
    }

    /** One session line, carrying the caller's own payload (the window's row object). */
    public record Line<T>(Header header, T item) {
    }

    private SessionGrouping() {
    }

    public record Project(String id, String name) {
    }

    /**
     * Lays out {@code items} (already in display order) under their project headers.
     *
     * @param projects the tree's project rows, in order; the header title is taken from here
     * @param projectOf item-id to project-id map; null means standalone, an id absent from
     *                  {@code projects} lands in the unknown bucket
     */
    public static <T> List<Line<T>> layout(List<Project> projects, List<T> items,
                                           Map<String, String> projectOf,
                                           java.util.function.Function<T, String> itemId) {
        List<Line<T>> out = new ArrayList<>();
        Map<String, List<T>> byProject = new LinkedHashMap<>();
        List<T> standalone = new ArrayList<>();
        List<T> unknown = new ArrayList<>();
        Map<String, String> titles = new LinkedHashMap<>();
        for (Project p : projects) {
            titles.put(p.id(), p.name());
            byProject.put(p.id(), new ArrayList<>());
        }
        for (T item : items) {
            String pid = projectOf.get(itemId.apply(item));
            if (pid == null) {
                standalone.add(item);
            } else if (byProject.containsKey(pid)) {
                byProject.get(pid).add(item);
            } else {
                unknown.add(item);
            }
        }
        for (Map.Entry<String, List<T>> e : byProject.entrySet()) {
            if (e.getValue().isEmpty()) {
                // a project with no visible rows keeps its header only while no filter is
                // narrowing the list; the caller decides by passing unfiltered items
                out.add(new Line<>(new Header(titles.get(e.getKey()), e.getKey(), false, false, 0), null));
                continue;
            }
            Header header = new Header(titles.get(e.getKey()), e.getKey(), false, false, e.getValue().size());
            for (T item : e.getValue()) {
                out.add(new Line<>(header, item));
            }
        }
        if (!standalone.isEmpty()) {
            Header header = new Header(NO_PROJECT, null, true, false, standalone.size());
            for (T item : standalone) {
                out.add(new Line<>(header, item));
            }
        }
        if (!unknown.isEmpty()) {
            Header header = new Header(UNKNOWN_PROJECT, null, false, true, unknown.size());
            for (T item : unknown) {
                out.add(new Line<>(header, item));
            }
        }
        return out;
    }
}
