package net.thevpc.naru.desktop.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sidebar's grouping rules, without a window: projects first in service order, the
 * Default bucket after them, dangling ids last under "Unknown project", an empty
 * project still visible as a header, and — because a workspace with no project at all
 * must still offer a group row to act on — a bare Default header instead of a flat list.
 */
public class SessionGroupingTest {

    private static SessionGrouping.Project project(String id, String name) {
        return new SessionGrouping.Project(id, name);
    }

    @Test
    public void sessionsGroupUnderTheirProjectAndHeadersRepeatPerRow() {
        Map<String, String> projectOf = new LinkedHashMap<>();
        projectOf.put("a", "p1");
        projectOf.put("b", null);
        projectOf.put("c", "p1");
        List<SessionGrouping.Line<String>> lines = SessionGrouping.layout(
                List.of(project("p1", "Backend")), List.of("a", "b", "c"), projectOf::get);
        Assertions.assertEquals(3, lines.size());
        Assertions.assertEquals("Backend", lines.get(0).header().title());
        Assertions.assertSame(lines.get(0).header(), lines.get(1).header(),
                "the same project's rows carry the header instance once, not a copy per row");
        Assertions.assertEquals("a", lines.get(0).item());
        Assertions.assertEquals("c", lines.get(1).item(),
                "the group keeps its members together, in display order within the group");
        Assertions.assertEquals(SessionGrouping.DEFAULT_GROUP, lines.get(2).header().title());
        Assertions.assertEquals("b", lines.get(2).item());
        Assertions.assertEquals(1, lines.get(2).header().count());
    }

    @Test
    public void danglingProjectIdsLandInTheUnknownBucketLast() {
        Map<String, String> projectOf = Map.of("x", "gone", "y", "p1");
        List<SessionGrouping.Line<String>> lines = SessionGrouping.layout(
                List.of(project("p1", "One")), List.of("x", "y"), projectOf::get);
        Assertions.assertEquals("One", lines.get(0).header().title());
        Assertions.assertEquals("y", lines.get(0).item());
        Assertions.assertEquals(SessionGrouping.UNKNOWN_PROJECT, lines.get(1).header().title());
        Assertions.assertTrue(lines.get(1).header().unknown());
        Assertions.assertEquals("x", lines.get(1).item());
    }

    @Test
    public void emptyProjectsRemainVisibleAsBareHeaders() {
        List<SessionGrouping.Line<String>> lines = SessionGrouping.layout(
                List.of(project("p1", "Empty"), project("p2", "Busy")),
                List.of("s1"), Map.of("s1", "p2")::get);
        // one bare header row for the empty project, one header-carrying item for the busy one
        Assertions.assertEquals(2, lines.size());
        Assertions.assertEquals("Empty", lines.get(0).header().title());
        Assertions.assertNull(lines.get(0).item(), "an empty project renders its header alone");
        Assertions.assertEquals(0, lines.get(0).header().count());
        Assertions.assertEquals("Busy", lines.get(1).header().title());
        Assertions.assertEquals("s1", lines.get(1).item());
        Assertions.assertEquals(1, lines.get(1).header().count());
    }

    @Test
    public void aWorkspaceWithNoProjectsStillRendersTheDefaultGroup() {
        List<SessionGrouping.Line<String>> bare = SessionGrouping.layout(
                List.of(), List.of(), s -> null);
        Assertions.assertEquals(1, bare.size(),
                "a fresh workspace shows one Default header instead of a flat nothing");
        Assertions.assertEquals(SessionGrouping.DEFAULT_GROUP, bare.get(0).header().title());
        Assertions.assertTrue(bare.get(0).header().standalone());
        Assertions.assertNull(bare.get(0).item(), "the header row carries no session");

        List<SessionGrouping.Line<String>> filled = SessionGrouping.layout(
                List.of(), List.of("s1", "s2"), s -> null);
        Assertions.assertEquals(2, filled.size());
        Assertions.assertSame(filled.get(0).header(), filled.get(1).header());
        Assertions.assertEquals(SessionGrouping.DEFAULT_GROUP, filled.get(0).header().title());
        Assertions.assertEquals(2, filled.get(0).header().count());
        Assertions.assertNull(filled.get(0).header().project(),
                "the Default bucket is not a project, so it owns no props to paint");
    }

    @Test
    public void headerRowsCarryTheirProjectAndItsPresentationProps() {
        // the renderer paints the group's glyph and hue off the header alone: it never goes
        // back to the service, which is read off the event thread
        SessionGrouping.Project backend = new SessionGrouping.Project(
                "p1", "Backend", "terminal", "#3B82F6", "the api work");
        List<SessionGrouping.Line<String>> lines = SessionGrouping.layout(
                List.of(backend), List.of("s1"), Map.of("s1", "p1")::get);
        SessionGrouping.Header header = lines.get(0).header();
        Assertions.assertSame(backend, header.project());
        Assertions.assertEquals("p1", header.projectId());
        Assertions.assertEquals("terminal", header.project().icon());
        Assertions.assertEquals("#3B82F6", header.project().color());
        Assertions.assertEquals("the api work", header.project().description());

        List<SessionGrouping.Line<String>> orphaned = SessionGrouping.layout(
                List.of(backend), List.of("s2"), Map.of("s2", "gone")::get);
        // the empty project still keeps its bare header row, so the unknown bucket is the second
        Assertions.assertEquals(SessionGrouping.UNKNOWN_PROJECT, orphaned.get(1).header().title());
        Assertions.assertNull(orphaned.get(1).header().project(), "the unknown bucket has no props either");
        Assertions.assertNull(orphaned.get(1).header().projectId());
    }

    @Test
    public void aPopulatedTreeDoesNotAppendAnEmptyDefaultBucket() {
        List<SessionGrouping.Line<String>> lines = SessionGrouping.layout(
                List.of(project("p1", "One")), List.of("s1"), Map.of("s1", "p1")::get);
        Assertions.assertEquals(1, lines.size(),
                "every session has a project, so there is no Default row to paint");
    }
}
