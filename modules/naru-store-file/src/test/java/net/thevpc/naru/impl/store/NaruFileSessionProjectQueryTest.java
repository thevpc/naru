package net.thevpc.naru.impl.store;

import net.thevpc.naru.api.store.NaruPage;
import net.thevpc.naru.api.store.NaruProjectFilter;
import net.thevpc.naru.api.store.NaruProjectSessionCounts;
import net.thevpc.naru.api.store.NaruSessionData;
import net.thevpc.naru.api.store.NaruSessionQuery;
import net.thevpc.naru.api.store.NaruSessionScope;
import net.thevpc.naru.api.store.NaruSessionStore;
import net.thevpc.naru.api.store.NaruSessionSummary;
import net.thevpc.naru.api.store.NaruStoreConfig;
import net.thevpc.naru.api.store.NaruUserFilter;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.elem.NObjectElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The project-aware session queries: header filtering by project and user, per-project
 * counts including the standalone bucket, and {@code reassignProject} -- the metadata
 * move that touches nothing but {@code projectId} and the version token, under the same
 * compare-and-set the engine checkpoints through.
 */
@Timeout(60)
public class NaruFileSessionProjectQueryTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private NPath projectDir;
    private NaruSessionStore store;

    @BeforeEach
    public void newProject() {
        projectDir = NPath.ofTempFolder("naru-session-query-" + System.nanoTime());
        store = new NaruFileSessionStore(new NaruStoreConfig(projectDir,
                projectDir.resolve(".naru"), NaruSessionScope.PRIVATE));
    }

    // ---------------------------------------------------------------- fixtures

    private NaruSessionData data(String uuid, String name, String projectId, String user) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("mode", "test");
        return new NaruSessionData()
                .uuid(uuid)
                .name(name)
                .creationInstant(Instant.parse("2024-01-01T00:00:00Z"))
                .modificationInstant(Instant.parse("2024-01-02T00:00:0" + (name.hashCode() & 7) + "Z"))
                .sessionDir("/tmp/p-" + uuid)
                .workingDir("/tmp/w-" + uuid)
                .projectId(projectId)
                .user(user)
                .env(env);
    }

    private NaruSessionData create(String uuid, String name, String projectId, String user) {
        NaruSessionData d = data(uuid, name, projectId, user);
        store.create(d, NaruSessionScope.PRIVATE);
        return d;
    }

    // ---------------------------------------------------------------- filters

    @Test
    public void projectFilterSelectsAnyNoneAndOneId() {
        create("s-none", "standalone", null, null);
        create("s-p1", "first", "p1", "alice");
        create("s-p2", "second", "p2", null);

        Assertions.assertEquals(3, store.findByQuery(NaruSessionQuery.first()).items().size());
        List<String> none = store.findByQuery(new NaruSessionQuery(null, null,
                NaruProjectFilter.none(), NaruUserFilter.any(), 50)).items()
                .stream().map(NaruSessionSummary::uuid).toList();
        Assertions.assertEquals(List.of("s-none"), none);
        List<String> p1 = store.findByQuery(new NaruSessionQuery(null, null,
                NaruProjectFilter.ofId("p1"), NaruUserFilter.any(), 50)).items()
                .stream().map(NaruSessionSummary::uuid).toList();
        Assertions.assertEquals(List.of("s-p1"), p1);
    }

    @Test
    public void userFilterCombinesWithProjectFilter() {
        create("s-a", "a", "p1", "alice");
        create("s-b", "b", "p1", null);
        create("s-c", "c", null, "alice");
        Assertions.assertEquals(List.of("s-a"), store.findByQuery(new NaruSessionQuery(null, null,
                NaruProjectFilter.ofId("p1"), NaruUserFilter.ofName("alice"), 50))
                .items().stream().map(NaruSessionSummary::uuid).toList());
        Assertions.assertEquals(2, store.findByQuery(new NaruSessionQuery(null, null,
                NaruProjectFilter.any(), NaruUserFilter.ofName("alice"), 50)).items().size());
        Assertions.assertEquals(List.of("s-b"), store.findByQuery(new NaruSessionQuery(null, null,
                NaruProjectFilter.any(), NaruUserFilter.none(), 50))
                .items().stream().map(NaruSessionSummary::uuid).toList());
    }

    @Test
    public void summariesCarryTheHeaderFieldsTheTreeNeeds() {
        create("s-x", "exy", "p9", "zed");
        NaruSessionSummary s = store.findByQuery(new NaruSessionQuery(null, null,
                NaruProjectFilter.ofId("p9"), NaruUserFilter.any(), 50)).items().get(0);
        Assertions.assertEquals("s-x", s.uuid());
        Assertions.assertEquals("exy", s.name());
        Assertions.assertEquals(NaruSessionScope.PRIVATE, s.scope());
        Assertions.assertEquals("/tmp/p-s-x", s.sessionDir());
        Assertions.assertEquals("/tmp/w-s-x", s.workingDir());
        Assertions.assertEquals("p9", s.projectId());
        Assertions.assertEquals("zed", s.user());
        // a row the store created verbatim carries the engine's starting token: the
        // first checkpoint bumps it, creation itself does not
        Assertions.assertEquals(0, s.version());
        Assertions.assertFalse(s.projectMissing(), "the store never claims a project missing");
    }

    // ---------------------------------------------------------------- counts

    @Test
    public void countByProjectCoversEveryBucketIncludingStandalone() {
        create("a1", "a1", "p1", null);
        create("a2", "a2", "p1", null);
        create("b1", "b1", "p2", null);
        create("n1", "n1", null, null);
        create("n2", "n2", null, "alice");
        NaruProjectSessionCounts counts = store.countByProject();
        Assertions.assertEquals(2, counts.of("p1"));
        Assertions.assertEquals(1, counts.of("p2"));
        Assertions.assertEquals(2, counts.noProject());
        Assertions.assertEquals(5, counts.total());
        Assertions.assertEquals(0, counts.of("p-absent"));
    }

    // ---------------------------------------------------------------- reassign

    @Test
    public void reassignBumpsVersionAndPersistsWithNothingElseMoved() {
        NaruSessionData d = create("r1", "round", null, "alice");
        long v = d.version();
        NaruSessionData after = store.reassignProject("r1", v, "p1");
        Assertions.assertEquals(v + 1, after.version());
        Assertions.assertEquals("p1", after.projectId());
        NaruSessionData back = store.loadData("r1", NaruSessionScope.PRIVATE).get();
        Assertions.assertEquals("p1", back.projectId());
        Assertions.assertEquals("alice", back.user(), "user is not a move casualty");
        Assertions.assertEquals(d.name(), back.name());
        Assertions.assertEquals(d.sessionDir(), back.sessionDir());
        Assertions.assertEquals(d.workingDir(), back.workingDir());
        Assertions.assertEquals(d.env(), back.env());
        Assertions.assertEquals(d.creationInstant(), back.creationInstant());
        Assertions.assertEquals(d.modificationInstant(), back.modificationInstant(),
                "a move is a metadata change: even the modification time stays as it was");
        // detach again: the key is dropped, not nulled
        NaruSessionData detached = store.reassignProject("r1", back.version(), null);
        Assertions.assertNull(detached.projectId());
        Assertions.assertFalse(rawHeader("r1").get("projectId").isPresent(),
                "a detached session omits the projectId key");
    }

    @Test
    public void reassignPreservesKeysTheStoreDoesNotKnow() {
        NaruSessionData d = create("f1", "future", "p1", null);
        // simulate a row written by a future reader: an extra key this store cannot know
        NPath file = sessionFile("f1");
        String withExtra = file.readString().replaceFirst("\\{", "{ futureKey : \"keep-me\",");
        file.writeString(withExtra);
        NaruSessionData after = store.reassignProject("f1", d.version(), "p2");
        Assertions.assertEquals("p2", after.projectId());
        NObjectElement o = rawHeader("f1");
        Assertions.assertEquals("keep-me", o.getStringValue("futureKey").orNull(),
                "a header-only rewrite must not drop keys it does not understand");
    }

    @Test
    public void reassignWithAStaleVersionLosesTheRaceAndChangesNothing() {
        NaruSessionData d = create("c1", "cas", "p1", null);
        store.reassignProject("c1", d.version(), "p2"); // now version 2
        Assertions.assertThrows(net.thevpc.naru.api.store.NaruSessionConflictException.class,
                () -> store.reassignProject("c1", d.version(), "p3"));
        Assertions.assertEquals("p2", store.loadData("c1", NaruSessionScope.PRIVATE).get().projectId());
    }

    @Test
    public void reassignOnAMissingSessionFailsClearly() {
        Assertions.assertThrows(net.thevpc.nuts.util.NIllegalArgumentException.class,
                () -> store.reassignProject("ghost", 1, "p1"));
    }

    @Test
    public void findSessionIdsByProjectFollowsTheHeader() {
        create("x1", "x1", "pa", null);
        create("x2", "x2", "pa", null);
        create("x3", "x3", "pb", null);
        create("x4", "x4", null, null);
        Assertions.assertEquals(Set.of("x1", "x2"), new HashSet<>(store.findSessionIdsByProject("pa")));
        Assertions.assertEquals(List.of("x4"), store.findSessionIdsByProject(null));
        Assertions.assertTrue(store.findSessionIdsByProject("missing").isEmpty());
    }

    // ---------------------------------------------------------------- paging

    @Test
    public void pagedTraversalWithFiltersVisitsEachRowExactlyOnce() {
        for (int i = 0; i < 7; i++) {
            NaruSessionData d = data("s" + i, "n" + i, i % 2 == 0 ? "even" : null, null);
            // distinct modification instants so the order is total
            d.modificationInstant(Instant.parse("2024-01-02T00:00:0" + i + "Z"));
            store.create(d, NaruSessionScope.PRIVATE);
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        while (true) {
            NaruPage<NaruSessionSummary> page = store.findByQuery(new NaruSessionQuery(
                    cursor, null, NaruProjectFilter.ofId("even"), NaruUserFilter.any(), 2));
            for (NaruSessionSummary s : page.items()) {
                Assertions.assertTrue(seen.add(s.uuid()), "repeat: " + s.uuid());
            }
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }
        Assertions.assertEquals(Set.of("s0", "s2", "s4", "s6"), new HashSet<>(seen));
    }

    @Test
    public void sessionTsonRoundTripsTheNewKeysAndOmitsThemWhenNull() {
        create("k1", "keys", "project-x", "owner-y");
        create("k2", "bare", null, null);
        NObjectElement withKeys = rawHeader("k1");
        Assertions.assertEquals("project-x", withKeys.getStringValue("projectId").orNull());
        Assertions.assertEquals("owner-y", withKeys.getStringValue("user").orNull());
        NObjectElement without = rawHeader("k2");
        Assertions.assertTrue(without.get("projectId").isEmpty());
        Assertions.assertTrue(without.get("user").isEmpty());
    }

    @Test
    public void limitsAndCursorsFollowTheProjectStoreContract() {
        create("l1", "one", "p", null);
        create("l2", "two", "p", null);
        NaruPage<NaruSessionSummary> page = store.findByQuery(new NaruSessionQuery(
                null, null, NaruProjectFilter.ofId("p"), NaruUserFilter.any(), 1));
        Assertions.assertEquals(1, page.items().size());
        Assertions.assertNotNull(page.nextCursor());
        // an invented cursor is an error, not a silent restart
        Assertions.assertThrows(net.thevpc.nuts.util.NIllegalArgumentException.class,
                () -> store.findByQuery(new NaruSessionQuery(
                        "!!!not-base64!!!", null, null, null, 2)));
    }

    // ---------------------------------------------------------------- helpers

    private NPath sessionFile(String uuid) {
        return projectDir.resolve(".naru/local/sessions").resolve(uuid).resolve("session.tson");
    }

    private NObjectElement rawHeader(String uuid) {
        return NElementReader.ofTson().read(sessionFile(uuid)).asObject().get();
    }
}
