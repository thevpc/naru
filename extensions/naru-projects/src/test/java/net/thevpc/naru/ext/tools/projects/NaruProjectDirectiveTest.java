package net.thevpc.naru.ext.tools.projects;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.project.NaruProject;
import net.thevpc.naru.api.project.NaruProjectService;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.store.NaruSessionData;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElementReader;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code /project} directive: argument parsing, each subcommand's happy path and its
 * error paths. Everything goes through the real workspace service and the real file
 * stores, so what the directive prints is what the stores hold.
 */
@Timeout(60)
public class NaruProjectDirectiveTest {

    private NPath projectDir;
    private NaruWorkspace agent;
    private NaruSession session;

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @BeforeEach
    public void setUp() {
        projectDir = NPath.ofTempFolder("naru-project-directive-" + System.nanoTime());
        agent = NaruWorkspace.of();
        agent.projectDirectory(projectDir);
        // configureDefaults=false: no SPI discovery, no network, no real models. The test
        // registers exactly the directive provider it exercises.
        session = agent.newSession()
                .sessionDir(projectDir)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        session.registry().registerDirectiveProvider(new NaruProjectsDirectiveProvider());
    }

    private NaruProjectService service() {
        return agent.projects();
    }

    private NaruTask task() {
        return session.newTask(NaruTaskSpec.of());
    }

    private NaruStmtResult call(String argument) {
        NaruTask t = task();
        NaruDirective directive = session.registry().findDirective("project")
                .orElseThrow(() -> new AssertionError("no /project directive"));
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("project", argument, t);
        return directive.execute(ctx);
    }

    /** A saved standalone session with the given id, for move tests. */
    private NaruSession savedSession(String id) {
        NaruSession s = agent.newSession()
                .sessionDir(projectDir)
                .id(id)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .build();
        s.save();
        return s;
    }

    private NaruSessionData header(String uuid) {
        NPath file = projectDir.resolve(".naru/local/sessions").resolve(uuid).resolve("session.tson");
        return NaruSessionData.of(NElementReader.ofTson().ntf(false).read(file));
    }

    // ---------------------------------------------------------------- add

    @Test
    public void addCreatesTheProjectAndAcceptsASpacedName() {
        NaruStmtResult r = call("add Docs Project --folder=" + projectDir.resolve("docs"));
        assertEquals(0, r.exitCode(), "/project add must not fail: " + r.errorValue());
        NaruProject p = service().find("Docs Project").get();
        assertEquals(projectDir.resolve("docs").toAbsolute().normalize(), p.folder());
        assertNull(p.user());
    }

    @Test
    public void addWithoutFolderFailsClearly() {
        NaruStmtResult r = call("add Orphan");
        assertError(r, "folder required");
    }

    @Test
    public void addWarnsAboutAMissingFolderButSucceeds() {
        NaruStmtResult r = call("add Ghost --folder=" + projectDir.resolve("nowhere"));
        assertEquals(0, r.exitCode(), "a missing folder is a warning, not an error");
        assertTrue(service().find("Ghost").isPresent());
    }

    @Test
    public void duplicateNameIsRefused() {
        call("add Dup --folder=" + projectDir.resolve("a"));
        NaruStmtResult r = call("add dup --folder=" + projectDir.resolve("b"));
        assertError(r, "already taken");
    }

    // ---------------------------------------------------------------- list / show

    @Test
    public void bareDirectiveListsAndBareListAgrees() {
        call("add One --folder=" + projectDir.resolve("one"));
        call("add Two --folder=" + projectDir.resolve("two") + " --user=alice");
        NaruStmtResult bare = call("");
        assertEquals(0, bare.exitCode());
        assertEquals("2", String.valueOf(bare.successValue().asIntValue().orNull()));
        NaruStmtResult filtered = call("list --user=alice");
        assertEquals("1", String.valueOf(filtered.successValue().asIntValue().orNull()));
        NaruStmtResult noUser = call("list --no-user");
        assertEquals("1", String.valueOf(noUser.successValue().asIntValue().orNull()));
        NaruStmtResult contains = call("list --filter=on");
        assertEquals("1", String.valueOf(contains.successValue().asIntValue().orNull()));
    }

    @Test
    public void showPrintsTheRowAndRejectsUnknownRefs() {
        call("add Showme --folder=" + projectDir.resolve("s"));
        NaruStmtResult ok = call("show Showme");
        assertEquals(0, ok.exitCode());
        assertNotNull(ok.successValue().asObject().get().getStringValue("id").orNull());
        NaruStmtResult missing = call("show nothing");
        assertError(missing, "project not found");
    }

    // ---------------------------------------------------------------- edit

    @Test
    public void editAppliesOptionsAndLeavesUnsetFieldsAlone() {
        call("add Ed --folder=" + projectDir.resolve("e1") + " --user=alice");
        NaruProject before = service().find("Ed").get();
        NaruStmtResult r = call("edit Ed --name=Renamed --no-user");
        assertEquals(0, r.exitCode(), String.valueOf(r.errorValue()));
        NaruProject after = service().find("Renamed").get();
        assertEquals(before.folder(), after.folder(), "unset folder untouched");
        assertNull(after.user());
        assertEquals(before.version() + 1, after.version());
    }

    @Test
    public void editWithoutChangesOrWithAmbiguousNameFails() {
        call("add Solo --folder=" + projectDir.resolve("a"));
        assertError(call("edit Solo"), "nothing to change");
        call("add Dup --folder=" + projectDir.resolve("b") + " --user=alice");
        call("add Dup --folder=" + projectDir.resolve("c") + " --user=bob");
        assertError(call("edit dup --name=Whatever"), "ambiguous");
    }

    // ---------------------------------------------------------------- remove

    @Test
    public void removeRejectsByDefaultAndDetachesOnDemand() {
        call("add Busy --folder=" + projectDir.resolve("busy"));
        savedSession("busy-1");
        call("move --to=Busy busy-1");
        assertError(call("remove Busy"), "still has");
        NaruStmtResult detach = call("remove Busy --sessions=detach");
        assertEquals(0, detach.exitCode(), String.valueOf(detach.errorValue()));
        assertTrue(service().find("Busy").isEmpty());
        assertNull(header("busy-1").projectId());
    }

    @Test
    public void removeCascadeDeletesSessionsThenProject() {
        call("add Gone --folder=" + projectDir.resolve("gone"));
        savedSession("gone-1");
        call("move --to=Gone gone-1");
        NaruStmtResult r = call("remove Gone --sessions=cascade");
        assertEquals(0, r.exitCode(), String.valueOf(r.errorValue()));
        assertTrue(service().find("Gone").isEmpty());
        assertFalse(session.sessionStoreManager().list().stream()
                .anyMatch(x -> "gone-1".equals(x.getUuid())), "the session went with it");
    }

    @Test
    public void removeRejectsABogusSessionsValue() {
        call("add Ok --folder=" + projectDir.resolve("ok"));
        assertError(call("remove Ok --sessions=incinerate"), "unknown --sessions value");
    }

    // ---------------------------------------------------------------- move

    @Test
    public void moveAttachesDetachesAndReportsPerSession() {
        call("add Target --folder=" + projectDir.resolve("t"));
        savedSession("mv-1");
        savedSession("mv-2");
        NaruStmtResult attach = call("move --to=Target mv-1 mv-2");
        assertEquals(0, attach.exitCode(), String.valueOf(attach.errorValue()));
        assertEquals(service().find("Target").get().id(), header("mv-1").projectId());
        NaruStmtResult detach = call("move mv-1");
        assertEquals(0, detach.exitCode(), String.valueOf(detach.errorValue()));
        assertNull(header("mv-1").projectId());
        NaruStmtResult mixed = call("move --to=Target mv-2 ghost-session");
        assertTrue(mixed.exitCode() != 0, "a failed row must fail the statement");
        assertErrorContains(mixed, "failed");
    }

    @Test
    public void moveWithUnknownTargetFails() {
        savedSession("x");
        assertError(call("move --to=no-such x"), "not found");
    }

    @Test
    public void moveWithoutSessionsIsAUsageError() {
        assertError(call("move --to=whatever"), "usage:");
    }

    // ---------------------------------------------------------------- helpers

    private static void assertError(NaruStmtResult r, String needle) {
        assertNotNull(r, "directive returned null");
        assertFalse(r.exitCode() == 0, "expected a failure but got success");
        assertErrorContains(r, needle);
    }

    private static void assertErrorContains(NaruStmtResult r, String needle) {
        String haystack = String.valueOf(r.errorValue()) + " " + r.successValue();
        assertTrue(haystack.toLowerCase().contains(needle.toLowerCase()),
                "expected '" + needle + "' in: " + haystack);
    }
}
