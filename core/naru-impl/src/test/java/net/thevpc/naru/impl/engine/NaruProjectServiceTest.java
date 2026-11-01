package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.project.NaruMoveReport;
import net.thevpc.naru.api.project.NaruProject;
import net.thevpc.naru.api.project.NaruProjectAmbiguousException;
import net.thevpc.naru.api.project.NaruProjectDeletePolicy;
import net.thevpc.naru.api.project.NaruProjectNotEmptyException;
import net.thevpc.naru.api.project.NaruProjectNotFoundException;
import net.thevpc.naru.api.project.NaruProjectPatch;
import net.thevpc.naru.api.project.NaruProjectQuery;
import net.thevpc.naru.api.project.NaruProjectResult;
import net.thevpc.naru.api.project.NaruProjectService;
import net.thevpc.naru.api.project.NaruRemoveReport;
import net.thevpc.naru.api.store.NaruPage;
import net.thevpc.naru.api.store.NaruProjectFilter;
import net.thevpc.naru.api.store.NaruSessionData;
import net.thevpc.naru.api.store.NaruSessionQuery;
import net.thevpc.naru.api.store.NaruSessionScope;
import net.thevpc.naru.api.store.NaruSessionStore;
import net.thevpc.naru.api.store.NaruSessionSummary;
import net.thevpc.naru.api.store.NaruUserFilter;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NIllegalArgumentException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The project service: CRUD with warnings, the tree the desktop renders, moves that touch
 * only {@code projectId} (live sessions included, through their own in-memory state), the
 * delete policies with their deliberate orderings, and the creation-time copy rule that
 * makes a project semantic rather than restrictive.
 */
@Timeout(90)
public class NaruProjectServiceTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private NPath workspaceDir;
    private NaruWorkspaceImpl agent;
    private NaruProjectService projects;
    private NaruSessionStore sessions;

    @BeforeEach
    public void newWorkspace() {
        workspaceDir = NPath.ofTempFolder("naru-projects-" + System.nanoTime());
        agent = (NaruWorkspaceImpl) NaruWorkspace.of();
        agent.projectDirectory(workspaceDir);
        projects = agent.projects();
        // the same handle the service shares: one folder, one store
        sessions = ((NaruWorkspaceSessionCatalog) agent.sessionCatalog()).rawStore();
    }

    // ---------------------------------------------------------------- fixtures

    private NaruProject createProject(String name, String folder, String user) {
        return projects.add(name, NPath.of(folder), user).value();
    }

    private NaruSession newSession(String name) {
        return agent.newSession()
                .sessionDir(workspaceDir)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .id(name)
                .build();
    }

    private NaruSession savedSession(String uuid, NaruProject project) {
        NaruSession session = project == null
                ? newSession(uuid)
                : agent.newSession()
                .sessionDir(workspaceDir)
                .project(project.name())
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .id(uuid)
                .build();
        session.save();
        return session;
    }

    private NaruSessionSummary header(String uuid) {
        return sessions.findByQuery(new NaruSessionQuery(null, null,
                NaruProjectFilter.any(), NaruUserFilter.any(), 100)).items()
                .stream().filter(s -> s.uuid().equals(uuid)).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- add / edit / warnings

    @Test
    public void addCarriesTheFolderMissingWarningWithoutFailing() {
        NPath missing = workspaceDir.resolve("not-yet-mounted");
        NaruProjectResult<NaruProject> result = projects.add("Ghostly", missing, null);
        Assertions.assertTrue(result.hasWarnings(), "a missing folder must warn");
        Assertions.assertTrue(result.warnings().get(0).contains("does not exist"),
                String.valueOf(result.warnings()));
        Assertions.assertTrue(projects.find("Ghostly").isPresent(),
                "the project itself was still created");
    }

    @Test
    public void addWithoutFolderIsAValidationFailureNotADefault() {
        Assertions.assertThrows(NIllegalArgumentException.class,
                () -> projects.add("NoFolder", null, null));
    }

    @Test
    public void editingAFolderNeverTouchesExistingSessions() {
        NaruProject p = createProject("Anchor", workspaceDir.resolve("first").toString(), "alice");
        NaruSession s = savedSession("d4-session", p);
        String sessionDirBefore = s.sessionDir().toString();
        String userBefore = s.userName();

        projects.edit("Anchor", p.version(), NaruProjectPatch.of()
                .folder(workspaceDir.resolve("second").toAbsolute())
                .clearUser());

        NaruSessionData after = sessions.loadData("d4-session", NaruSessionScope.PRIVATE).get();
        Assertions.assertEquals(sessionDirBefore, after.sessionDir(), "D4: no propagation");
        Assertions.assertEquals(userBefore, after.user(), "D4: no propagation");
        Assertions.assertEquals(p.id(), after.projectId());
    }

    // ---------------------------------------------------------------- creation precedence

    @Test
    public void explicitValuesBeatTheProjectAndTheProjectBeatsTheDefault() {
        NaruProject p = createProject("Seed", workspaceDir.resolve("seed-folder").toString(), "proj-user");

        // project only: folder and user are copied at creation
        NaruSession copied = agent.newSession().project("Seed").id("copy")
                .interaction(new NaruStreamInteraction(o -> {
                })).configureDefaults(false).build();
        Assertions.assertEquals(workspaceDir.resolve("seed-folder").toAbsolute().normalize(),
                copied.sessionDir().toAbsolute().normalize());
        Assertions.assertEquals("proj-user", copied.userName());
        Assertions.assertEquals(p.id(), copied.projectId());

        // explicit on both sides wins over the project
        NaruSession explicit = agent.newSession()
                .sessionDir(workspaceDir)
                .project("Seed")
                .user("chosen-user")
                .id("explicit")
                .interaction(new NaruStreamInteraction(o -> {
                })).configureDefaults(false).build();
        Assertions.assertEquals(workspaceDir.toAbsolute().normalize(),
                explicit.sessionDir().toAbsolute().normalize());
        Assertions.assertEquals("chosen-user", explicit.userName());

        // no project: the existing default (the agent's workspace directory), user stays null
        NaruSession plain = newSession("plain");
        Assertions.assertEquals(workspaceDir.toAbsolute().normalize(),
                plain.sessionDir().toAbsolute().normalize());
        Assertions.assertNull(plain.userName());
        Assertions.assertNull(plain.projectId());
    }

    @Test
    public void buildingWithAMissingProjectFails() {
        NIllegalArgumentException err = Assertions.assertThrows(NIllegalArgumentException.class,
                () -> agent.newSession().project("no-such-project").id("doomed")
                        .interaction(new NaruStreamInteraction(o -> {
                        })).configureDefaults(false).build());
        Assertions.assertTrue(String.valueOf(err.getMessage()).contains("no-such-project"),
                String.valueOf(err.getMessage()));
    }

    @Test
    public void aResumedSessionKeepsItsStoredProjectAgainstAProjectOption() {
        NaruProject p = createProject("Later", workspaceDir.resolve("l").toString(), null);
        savedSession("keep-me", null); // standalone, saved
        NaruSession reopened = agent.newSession().project("Later").id("keep-me")
                .interaction(new NaruStreamInteraction(o -> {
                })).configureDefaults(false).build();
        Assertions.assertNull(reopened.projectId(),
                "a stored session is not re-created: its projectId stays as it was saved");
    }

    // ---------------------------------------------------------------- reads tolerate dangling ids

    @Test
    public void aSessionPointingAtAnUnknownProjectReadsWithProjectMissing() {
        savedSession("orphan", null);
        NaruSessionData data = sessions.loadData("orphan", NaruSessionScope.PRIVATE).get();
        sessions.reassignProject("orphan", data.version(), "project-that-does-not-exist");

        NaruSessionSummary summary = header("orphan");
        Assertions.assertNotNull(summary);
        Assertions.assertEquals("project-that-does-not-exist", summary.projectId());
        Assertions.assertFalse(summary.projectMissing(), "the store claims nothing about projects");

        List<NaruSessionSummary> stamped = projects.sessions(new NaruSessionQuery(null, null,
                NaruProjectFilter.ofId("project-that-does-not-exist"), NaruUserFilter.any(), 50)).items();
        Assertions.assertEquals(1, stamped.size());
        Assertions.assertTrue(stamped.get(0).projectMissing(),
                "the service computes projectMissing, and a dangling read is not an error");

        NaruPage<NaruSessionSummary> orphans = projects.findOrphanedSessions(null, 10);
        Assertions.assertEquals(List.of("orphan"),
                orphans.items().stream().map(NaruSessionSummary::uuid).toList());

        // it is not standalone either: its own bucket, not the no-project node
        Assertions.assertEquals(0, projects.countStandaloneSessions());
    }

    // ---------------------------------------------------------------- tree

    @Test
    public void treeCountsMatchTheActualSessionLists() {
        NaruProject a = createProject("Tree-A", workspaceDir.resolve("a").toString(), null);
        NaruProject b = createProject("Tree-B", workspaceDir.resolve("b").toString(), "bob");
        savedSession("t-a1", a);
        savedSession("t-a2", a);
        savedSession("t-b1", b);
        savedSession("t-none", null);

        List<NaruProject> tree = projects.tree(NaruProjectQuery.first()).items()
                .stream().map(n -> n.project()).toList();
        Assertions.assertEquals(2, tree.size());
        for (var node : projects.tree(NaruProjectQuery.first()).items()) {
            long actual = projects.sessions(new NaruSessionQuery(null, null,
                    NaruProjectFilter.ofId(node.project().id()), NaruUserFilter.any(), 50))
                    .items().size();
            Assertions.assertEquals(actual, node.sessionCount(),
                    "tree count must equal the expanded list for " + node.project().name());
        }
        Assertions.assertEquals(1, projects.countStandaloneSessions());
    }

    // ---------------------------------------------------------------- remove policies

    @Test
    public void rejectRefusesWhileMembersExistAndNamesThem() {
        NaruProject p = createProject("Busy", workspaceDir.resolve("x").toString(), null);
        for (int i = 0; i < 3; i++) {
            savedSession("rej-" + i, p);
        }
        NaruProjectNotEmptyException err = Assertions.assertThrows(NaruProjectNotEmptyException.class,
                () -> projects.remove("Busy", p.version(), NaruProjectDeletePolicy.REJECT));
        Assertions.assertEquals(3, err.sessionCount());
        Assertions.assertEquals(3, err.sampleSessions().size());
        // nothing changed
        Assertions.assertTrue(projects.find("Busy").isPresent());        Assertions.assertEquals(3, projects.sessions(new NaruSessionQuery(null, null,
                NaruProjectFilter.ofId(p.id()), NaruUserFilter.any(), 50)).items().size());
    }

    @Test
    public void detachClearsEveryProjectIdThenDeletesTheProject() {
        NaruProject p = createProject("Detach", workspaceDir.resolve("x").toString(), null);
        savedSession("det-1", p);
        savedSession("det-2", p);
        NaruRemoveReport report = projects.remove("Detach", p.version(), NaruProjectDeletePolicy.DETACH);
        Assertions.assertTrue(report.projectDeleted());
        Assertions.assertEquals(List.of("det-1", "det-2"),
                report.sessionsDetached().stream().sorted().toList());
        Assertions.assertTrue(report.failedSessions().isEmpty());
        Assertions.assertTrue(projects.find("Detach").isEmpty());
        Assertions.assertNull(sessions.loadData("det-1", NaruSessionScope.PRIVATE).get().projectId());
        Assertions.assertEquals(2, projects.countStandaloneSessions());
    }

    @Test
    public void cascadeRefusesWhenAnyMemberIsLive() {
        NaruProject p = createProject("Hot", workspaceDir.resolve("x").toString(), null);
        savedSession("cas-dead", p);
        NarSessionGuard guard = startLive("cas-live", p);
        try {
            net.thevpc.nuts.util.NIllegalStateException err =
                    Assertions.assertThrows(net.thevpc.nuts.util.NIllegalStateException.class,
                    () -> projects.remove("Hot", p.version(), NaruProjectDeletePolicy.CASCADE));
            Assertions.assertTrue(String.valueOf(err.getMessage()).contains("live"),
                    String.valueOf(err.getMessage()));
            // nothing deleted
            Assertions.assertTrue(sessions.exists("cas-dead", NaruSessionScope.PRIVATE));
            Assertions.assertTrue(projects.find("Hot").isPresent());
        } finally {
            guard.close();
        }
    }

    @Test
    public void cascadeDeletesSessionsThenTheProject() {
        NaruProject p = createProject("Cold", workspaceDir.resolve("x").toString(), null);
        savedSession("c1", p);
        savedSession("c2", p);
        NaruRemoveReport report = projects.remove("Cold", p.version(), NaruProjectDeletePolicy.CASCADE);
        Assertions.assertTrue(report.projectDeleted());
        Assertions.assertEquals(2, report.sessionsDeleted().size());
        Assertions.assertFalse(sessions.exists("c1", NaruSessionScope.PRIVATE));
        Assertions.assertFalse(sessions.exists("c2", NaruSessionScope.PRIVATE));
        Assertions.assertTrue(projects.find("Cold").isEmpty());
    }

    @Test
    public void cascadePartialFailureKeepsTheProjectAndReportsPrecisely() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"),
                "permission simulation is POSIX");
        NaruProject p = createProject("Half", workspaceDir.resolve("x").toString(), null);
        savedSession("h-ok", p);
        savedSession("h-stuck", p);
        // make the second session's folder undeletable by protecting it: a failing delete
        // must not stop the first, and must keep the project
        NPath stuckDir = workspaceDir.resolve(".naru/local/sessions/h-stuck");
        java.nio.file.Files.setPosixFilePermissions(java.nio.file.Paths.get(stuckDir.toString()),
                java.util.EnumSet.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        try {
            NaruRemoveReport report = projects.remove("Half", p.version(), NaruProjectDeletePolicy.CASCADE);
            Assertions.assertFalse(report.projectDeleted(), "a partial cascade keeps the project");
            Assertions.assertEquals(List.of("h-ok"), report.sessionsDeleted());
            Assertions.assertTrue(report.failedSessions().containsKey("h-stuck"));
            Assertions.assertEquals(List.of("h-stuck"), report.remainingSessions());
            Assertions.assertTrue(projects.find("Half").isPresent());
        } finally {
            java.nio.file.Files.setPosixFilePermissions(java.nio.file.Paths.get(stuckDir.toString()),
                    java.util.EnumSet.allOf(java.nio.file.attribute.PosixFilePermission.class));
        }
    }

    // ---------------------------------------------------------------- moves

    @Test
    public void movesRoundTripStandaloneAndBetweenProjectsTouchingNothingElse() {
        NaruProject a = createProject("Move-A", workspaceDir.resolve("ma").toString(), null);
        NaruProject b = createProject("Move-B", workspaceDir.resolve("mb").toString(), null);
        NaruSession s = savedSession("mv", null);
        NaruSessionData before = sessions.loadData("mv", NaruSessionScope.PRIVATE).get();

        Assertions.assertEquals(1, projects.moveSession("mv", "Move-A").moved().size());
        NaruSessionData atA = sessions.loadData("mv", NaruSessionScope.PRIVATE).get();
        Assertions.assertEquals(a.id(), atA.projectId());
        Assertions.assertEquals(before.sessionDir(), atA.sessionDir(), "a move is projectId-only");
        Assertions.assertEquals(before.user(), atA.user());
        Assertions.assertEquals(before.workingDir(), atA.workingDir());
        Assertions.assertEquals(before.env(), atA.env());
        Assertions.assertEquals(before.version() + 1, atA.version());

        // idempotent: already there
        NaruMoveReport again = projects.moveSession("mv", "Move-A");
        Assertions.assertTrue(again.moved().isEmpty());
        Assertions.assertEquals(List.of("mv"), again.unchanged());

        Assertions.assertEquals(1, projects.moveSession("mv", "Move-B").moved().size());
        Assertions.assertEquals(b.id(), sessions.loadData("mv", NaruSessionScope.PRIVATE).get().projectId());

        // back to standalone via the literal
        projects.moveSession("mv", NaruProjectService.NO_PROJECT);
        Assertions.assertNull(sessions.loadData("mv", NaruSessionScope.PRIVATE).get().projectId());
        Assertions.assertEquals(1, projects.countStandaloneSessions());
    }

    @Test
    public void aLiveSessionMoveGoesThroughItsInMemoryState() {
        NaruProject p = createProject("Live", workspaceDir.resolve("lv").toString(), null);
        savedSession("pre-live", null);
        NaruSession live = newSession("live-session");
        live.start();
        try {
            Assertions.assertEquals(1, projects.moveSession("live-session", "Live").moved().size());
            Assertions.assertEquals(p.id(), live.projectId(),
                    "the running session's own state carries the move");
            Assertions.assertEquals(p.id(), sessions.loadData("live-session", NaruSessionScope.PRIVATE).get().projectId(),
                    "and it reached disk through the session's normal save path");
        } finally {
            try {
                live.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    public void bulkMovesAreAtomicPerSessionNotAcrossSessions() {
        NaruProject p = createProject("Bulk", workspaceDir.resolve("bulk").toString(), null);
        savedSession("bm-1", null);
        savedSession("bm-2", null);
        NaruMoveReport report = projects.moveSessions(List.of("bm-1", "bm-2", "bm-missing"), "Bulk");
        Assertions.assertEquals(2, report.moved().size());
        Assertions.assertTrue(report.failed().containsKey("bm-missing"));
        Assertions.assertTrue(String.valueOf(report.failed().get("bm-missing")).contains("not found"),
                String.valueOf(report.failed()));
        Assertions.assertEquals(p.id(), sessions.loadData("bm-1", NaruSessionScope.PRIVATE).get().projectId());
        Assertions.assertEquals(p.id(), sessions.loadData("bm-2", NaruSessionScope.PRIVATE).get().projectId());
    }

    @Test
    public void movingToANonexistentProjectTargetIsAnError() {
        savedSession("tgt", null);
        Assertions.assertThrows(NaruProjectNotFoundException.class,
                () -> projects.moveSession("tgt", "no-such-target"));
    }

    // ---------------------------------------------------------------- idOrName resolution

    @Test
    public void idOrNameResolvesIdsFirstThenNamesAndReportsAmbiguity() {
        NaruProject alice = createProject("Dup", workspaceDir.resolve("d1").toString(), "alice");
        createProject("dup", workspaceDir.resolve("d2").toString(), "bob"); // same name, other user
        // exact id beats any name
        Assertions.assertEquals(alice.id(), projects.find(alice.id()).get().id());
        // two users, one name: ambiguous, and the error lists the candidates
        NaruProjectAmbiguousException err = Assertions.assertThrows(NaruProjectAmbiguousException.class,
                () -> projects.edit("Dup", alice.version(), NaruProjectPatch.of().name("Renamed")));
        Assertions.assertEquals(2, err.candidateIds().size());
        // unique names resolve for any user
        NaruProject solo = createProject("Only", workspaceDir.resolve("d3").toString(), null);
        Assertions.assertEquals(solo.id(), projects.find("oNlY").get().id());
    }

    @Test
    public void unknownReferenceIsNotFound() {        // the non-throwing lookup says "nothing"…
        Assertions.assertTrue(projects.find("nothing-here").isEmpty());
        // …and an operation that must have a target says so
        Assertions.assertThrows(NaruProjectNotFoundException.class,
                () -> projects.edit("nothing-here", 1, NaruProjectPatch.of().name("x")));
        Assertions.assertThrows(NaruProjectNotFoundException.class,
                () -> projects.remove("nothing-here", 1, NaruProjectDeletePolicy.REJECT));
    }

    // ---------------------------------------------------------------- notifications

    @Test
    public void removeListenerStopsFurtherDelivery() {
        AtomicInteger seen = new AtomicInteger();
        net.thevpc.naru.api.project.NaruProjectListener l =
                new net.thevpc.naru.api.project.NaruProjectListener() {
                    @Override
                    public void onProjectCreated(NaruProject project) {
                        seen.incrementAndGet();
                    }
                };
        projects.addListener(l);
        createProject("Listed-1", workspaceDir.resolve("x1").toString(), null);
        Assertions.assertEquals(1, seen.get());
        projects.removeListener(l);
        createProject("Listed-2", workspaceDir.resolve("x2").toString(), null);
        Assertions.assertEquals(1, seen.get(), "a removed listener is silent");
    }

    @Test
    public void listenersSeeCreateUpdateMoveAndRemoveAndSurviveTheirOwnFailures() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger moved = new AtomicInteger();
        AtomicInteger removed = new AtomicInteger();
        AtomicReference<NaruProject> updated = new AtomicReference<>();
        NaruProjectListenerAdapter good = new NaruProjectListenerAdapter(created, updated, moved, removed);
        projects.addListener(new net.thevpc.naru.api.project.NaruProjectListener() {
            @Override
            public void onProjectCreated(NaruProject project) {
                throw new RuntimeException("a listener that always breaks");
            }
        });
        projects.addListener(good);
        NaruProject p = createProject("Events", workspaceDir.resolve("e").toString(), null);
        Assertions.assertEquals(1, created.get());
        NaruProjectResult<NaruProject> edit = projects.edit("Events", p.version(),
                NaruProjectPatch.of().name("Events2"));
        Assertions.assertNotNull(updated.get());
        Assertions.assertEquals("Events2", edit.value().name());
        savedSession("ev", projects.find("Events2").get());
        projects.moveSession("ev", NaruProjectService.NO_PROJECT);
        Assertions.assertEquals(1, moved.get());
        projects.remove("Events2", projects.find("Events2").get().version(), NaruProjectDeletePolicy.DETACH);
        Assertions.assertEquals(1, removed.get());
        // the broken listener proved nothing by throwing: the operations all succeeded above
    }

    private static class NaruProjectListenerAdapter
            implements net.thevpc.naru.api.project.NaruProjectListener {
        final AtomicInteger created;
        final AtomicReference<NaruProject> updated;
        final AtomicInteger moved;
        final AtomicInteger removed;

        NaruProjectListenerAdapter(AtomicInteger created, AtomicReference<NaruProject> updated,
                                   AtomicInteger moved, AtomicInteger removed) {
            this.created = created;
            this.updated = updated;
            this.moved = moved;
            this.removed = removed;
        }

        public void onProjectCreated(NaruProject project) {
            created.incrementAndGet();
        }

        public void onProjectUpdated(NaruProject before, NaruProject after) {
            updated.set(after);
        }

        public void onProjectRemoved(NaruProject project) {
            removed.incrementAndGet();
        }

        public void onSessionMoved(String sessionId, String fromProjectId, String toProjectId) {
            moved.incrementAndGet();
        }
    }

    /** A started session, stopped again on close. */
    private NarSessionGuard startLive(String uuid, NaruProject project) {
        NaruSession session = agent.newSession()
                .sessionDir(workspaceDir)
                .project(project.name())
                .id(uuid)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .build();
        session.start();
        return new NarSessionGuard(session);
    }

    private static class NarSessionGuard implements AutoCloseable {
        private final NaruSession session;

        NarSessionGuard(NaruSession session) {
            this.session = session;
        }

        @Override
        public void close() {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }
}
