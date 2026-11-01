package net.thevpc.naru.desktop.model;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The sidebar's grouping rules, without a window: projects first in service order, the
 * standalone bucket after them, dangling ids last under "Unknown project", an empty
 * project still visible as a header, and each group's rows in display order with the
 * header instance repeated per row so the renderer can paint it once.
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
                List.of(project("p1", "Backend")), List.of("a", "b", "c"), projectOf, s -> s);
        Assertions.assertEquals(3, lines.size());
        Assertions.assertEquals("Backend", lines.get(0).header().title());
        Assertions.assertSame(lines.get(0).header(), lines.get(1).header(),
                "the same project's rows carry the header instance once, not a copy per row");
        Assertions.assertEquals("a", lines.get(0).item());
        Assertions.assertEquals("c", lines.get(1).item(),
                "the group keeps its members together, in display order within the group");
        Assertions.assertEquals(SessionGrouping.NO_PROJECT, lines.get(2).header().title());
        Assertions.assertEquals("b", lines.get(2).item());
        Assertions.assertEquals(1, lines.get(2).header().count());
    }

    @Test
    public void danglingProjectIdsLandInTheUnknownBucketLast() {
        Map<String, String> projectOf = Map.of("x", "gone", "y", "p1");
        List<SessionGrouping.Line<String>> lines = SessionGrouping.layout(
                List.of(project("p1", "One")), List.of("x", "y"), projectOf, s -> s);
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
                List.of("s1"), Map.of("s1", "p2"), s -> s);
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
    public void noGroupingAtAllProducesNoHeaders() {
        List<SessionGrouping.Line<String>> lines = SessionGrouping.layout(
                List.of(), List.of(), Map.of(), s -> s);
        Assertions.assertTrue(lines.isEmpty(),
                "an empty catalog is the caller's placeholder, not a header");
    }
}
