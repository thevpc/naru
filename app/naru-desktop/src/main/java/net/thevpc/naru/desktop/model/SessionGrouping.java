package net.thevpc.naru.desktop.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the two-level Project → Session contract into the rows a one-dimensional session
 * list renders: project headers interleaved with their sessions, a "Default" bucket for
 * sessions that belong to no project and an "Unknown project" bucket for sessions whose
 * project id names no existing project (legal: a public session committed to git may carry
 * an id from its author's machine).
 *
 * <p>Pure data, no Swing: the window feeds it its catalog entries and paints the result.
 * Order rules, in one place on purpose: projects appear in the order the service page
 * gave them (the tree's sort), sessions keep their incoming display order inside every
 * group, the default bucket follows the last known project, and the unknown bucket
 * comes last. A group with no visible rows renders no header — except the default bucket,
 * which also renders bare when the tree holds no project at all. That is what keeps the
 * sidebar a tree rather than a flat list: a fresh workspace still has a group row to
 * right-click, rename into a project, or drop a session onto.
 */
public final class SessionGrouping {

    public static final String DEFAULT_GROUP = "Default";
    public static final String UNKNOWN_PROJECT = "Unknown project";

    /**
     * A project row as the sidebar needs it: its identity, the presentation props, and the
     * folder new sessions of that group start in. The folder is here because the group form is
     * one dialog for creating and editing — it has to open on what the row already says.
     */
    public record Project(String id, String name, net.thevpc.nuts.io.NPath folder,
                          String icon, String color, String description) {
        public Project(String id, String name) {
            this(id, name, null, null, null, null);
        }

        public Project(String id, String name, String icon, String color, String description) {
            this(id, name, null, icon, color, description);
        }
    }

    /**
     * A header row: a project ({@code project != null}), the standalone bucket, or the
     * unknown bucket. The bucket rows carry no project, so they have no props of their own —
     * the Default bucket renders with the window's default glyph and hue.
     */
    public record Header(String title, Project project, boolean standalone, boolean unknown, int count) {
        Header(String title, Project project, boolean standalone, boolean unknown) {
            this(title, project, standalone, unknown, 0);
        }

        /** The group's project id, or null for the Default and unknown buckets. */
        public String projectId() {
            return project == null ? null : project.id();
        }
    }

    /** One session line, carrying the caller's own payload (the window's row object). */
    public record Line<T>(Header header, T item) {
    }

    private SessionGrouping() {
    }

    /**
     * Lays out {@code items} (already in display order) under their project headers.
     *
     * @param projects  the tree's project rows, in order; the header title and props come from here
     * @param projectOf the project id of an item, or null when it belongs to no project. A
     *                  caller-resolved value rather than a map, because a session this window
     *                  just created has no uuid yet and only the window knows its group.
     */
    public static <T> List<Line<T>> layout(List<Project> projects, List<T> items,
                                           java.util.function.Function<T, String> projectOf) {
        List<Line<T>> out = new ArrayList<>();
        Map<String, List<T>> byProject = new LinkedHashMap<>();
        List<T> standalone = new ArrayList<>();
        List<T> unknown = new ArrayList<>();
        Map<String, Project> rows = new LinkedHashMap<>();
        for (Project p : projects) {
            rows.put(p.id(), p);
            byProject.put(p.id(), new ArrayList<>());
        }
        for (T item : items) {
            String pid = projectOf.apply(item);
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
                out.add(new Line<>(new Header(rows.get(e.getKey()).name(), rows.get(e.getKey()),
                        false, false, 0), null));
                continue;
            }
            Project row = rows.get(e.getKey());
            Header header = new Header(row.name(), row, false, false, e.getValue().size());
            for (T item : e.getValue()) {
                out.add(new Line<>(header, item));
            }
        }
        if (!standalone.isEmpty() || projects.isEmpty()) {
            Header header = new Header(DEFAULT_GROUP, null, true, false, standalone.size());
            if (standalone.isEmpty()) {
                out.add(new Line<>(header, null));
            } else {
                for (T item : standalone) {
                    out.add(new Line<>(header, item));
                }
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
