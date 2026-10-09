package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code /skill} the way the REPL does, so a listing column, a doctor report, or a
 * reload that "works in the planner" but is wired up wrong at the call site is caught here.
 */
@Timeout(60)
public class NaruSkillDirectiveTest {

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void sessionStarted(NaruSession session) {
        }

        @Override
        public void sessionStopped(NaruSession session) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }
    };

    private NPath projectDir;
    private NaruSessionImpl session;
    private NaruSkillsExtension ext;
    private final List<NaruOutput> outputs = new ArrayList<>();

    @BeforeAll
    public static void setUpWorkspace() {
        try {
            NWorkspace ws = Nuts.openWorkspace("--system", "--standalone");
            if (ws != null) {
                ws.share();
            }
        } catch (Exception e) {
            try {
                NWorkspace ws = Nuts.openWorkspace();
                if (ws != null) {
                    ws.share();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @BeforeEach
    public void setUp() {
        projectDir = NPath.ofTempFolder("naru-skill-directive-" + System.nanoTime());
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        outputs.clear();
        session = new NaruSessionImpl(agent, projectDir,
                new NaruStreamInteraction(o -> outputs.add(o)), true,
                NOOP_LISTENER, null, null, null);
        ext = NaruSkillsExtension.skills(session);
    }

    @AfterEach
    public void tearDown() {
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void publicFlat(String name, String... lines) {
        write(projectDir.resolve(".naru/skills/" + name + ".md"), lines);
    }

    private void privateFlat(String name, String... lines) {
        write(projectDir.resolve(".naru/local/skills/" + name + ".md"), lines);
    }

    private void publicFolder(String name, String frontMatter, String... body) {
        StringBuilder sb = new StringBuilder();
        if (frontMatter != null) {
            sb.append("---\n").append(frontMatter).append("\n---\n");
        }
        for (String l : body) {
            sb.append(l).append('\n');
        }
        write(projectDir.resolve(".naru/skills/" + name + "/SKILL.md"), sb.toString().split("\n", -1));
    }

    private static void write(NPath file, String... lines) {
        file.mkParentDirs();
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        file.writeString(sb.toString());
    }

    private NaruTask parent() {
        return session.newTask(NaruTaskSpec.of());
    }

    private NaruStmtResult call(NaruTask task, String directive, String argument) {
        NaruDirective d = session.registry().findDirective(directive)
                .orElseThrow(() -> new AssertionError("no /" + directive + " directive registered"));
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl(directive, argument, task);
        return d.execute(ctx);
    }

    /** Outputs logged since the given index, as plain strings. */
    private List<String> logsSince(int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < outputs.size(); i++) {
            out.add(outputs.get(i).message().toString());
        }
        return out;
    }

    // ── /skill list (unified) ───────────────────────────────────────────────

    @Test
    public void listShowsStateOriginVisibilityShadowedAndRequiresStatus() {
        publicFlat("git-flow", "follow git flow");
        publicFlat("javadoc", "use javadoc style");
        privateFlat("javadoc", "PRIVATE javadoc rules");
        ext.reload();
        NaruTask t = parent();

        NaruStmtResult r = call(t, "skill", "");

        assertNull(r.errorValue(), () -> "list must not error: " + r);
        String out = String.valueOf(r.successValue());
        assertTrue(out.contains("2 skills available (0 loaded, 2 advertised)"), out);
        // the unified columns, in order
        assertTrue(out.contains("ADVERTISED"), out);
        assertTrue(out.contains(".naru/skills"), out);
        assertTrue(out.contains(".naru/local/skills"), out);
        assertTrue(out.contains("public"), out);
        assertTrue(out.contains("private"), out);
        assertTrue(out.contains("shadowed"), out);
        assertTrue(out.contains("requires=none"), out);
        assertTrue(out.contains("git-flow"), out);
        assertTrue(out.contains("javadoc"), out);
        assertFalse(out.contains("ACTIVE SKILL DIRECTIVE"), "a listing is not a prompt injection");
    }

    @Test
    public void listMarksLoadedSkillsAndAdvertisedDefaults() {
        publicFlat("git-flow", "follow git flow");
        ext.reload();
        NaruTask t = parent();
        call(t, "skill", "load git-flow");

        NaruStmtResult r = call(t, "skill", "list");
        assertTrue(String.valueOf(r.successValue()).contains("1 skills available (1 loaded, 0 advertised)"), String.valueOf(r.successValue()));
        assertTrue(String.valueOf(r.successValue()).contains("LOADED"), String.valueOf(r.successValue()));
    }

    @Test
    public void availableIsAnAliasOfList() {
        publicFlat("git-flow", "follow git flow");
        ext.reload();
        NaruTask t = parent();
        assertEquals(String.valueOf(call(t, "skill", "").successValue()),
                String.valueOf(call(t, "skill", "available").successValue()));
    }

    // ── load / unload / show ────────────────────────────────────────────────

    @Test
    public void loadAndUnloadRoundTrip() {
        publicFlat("git-flow", "follow git flow");
        ext.reload();
        NaruTask t = parent();

        call(t, "skill", "load git-flow");
        assertEquals(Set.of("git-flow"), ext.activeNames(t));
        assertEquals(Set.of("git-flow"), ext.activeNames(t));

        call(t, "skill", "unload git-flow");
        assertEquals(Set.of(), ext.activeNames(t));

        // unloading an already-unloaded skill is simply "already there"
        NaruStmtResult unloaded = call(t, "skill", "unload git-flow");
        assertNull(unloaded.errorValue(), () -> "unloading twice must not error: " + unloaded);
    }

    @Test
    public void loadOfAMissingSkillReportsNotFound() {
        NaruTask t = parent();
        NaruStmtResult r = call(t, "skill", "load nope");
        assertNotNull(r.errorValue());
        assertTrue(String.valueOf(r.errorValue()).contains("skill not found : nope"), String.valueOf(r.errorValue()));
        assertEquals(Set.of(), ext.activeNames(t));
    }

    @Test
    public void loadFindsAFreshlyWrittenFileWithoutAFullReload() {
        NaruTask t = parent();
        publicFlat("latecomer", "born while the session was open");

        NaruStmtResult r = call(t, "skill", "load latecomer");

        assertNull(r.errorValue(), () -> "a targeted refresh must find the new file: " + r);
        assertEquals(Set.of("latecomer"), ext.activeNames(t));
    }

    @Test
    public void showDisplaysTheSkillBody() {
        publicFlat("git-flow", "follow git flow");
        ext.reload();
        NaruTask t = parent();
        NaruStmtResult r = call(t, "skill", "show git-flow");
        assertNull(r.errorValue(), () -> "show must not error: " + r);
        assertTrue(logsSince(0).stream().anyMatch(l -> l.contains("follow git flow")),
                () -> "the body must be printed: " + logsSince(0));
    }

    // ── /skill reload ───────────────────────────────────────────────────────

    @Test
    public void reloadAppliesDiskChangesThatTheSnapshotOtherwiseKeeps() {
        publicFlat("draft", "version one");
        ext.reload();
        NaruTask t = parent();
        String hash1 = ext.skills().findSkill("draft").getContentHash();
        publicFlat("draft", "version two");
        // without an explicit reload the snapshot keeps serving the old content
        assertEquals(hash1, ext.skills().findSkill("draft").getContentHash());

        NaruStmtResult r = call(t, "skill", "reload draft");
        assertNull(r.errorValue(), () -> "reload of an existing skill must succeed: " + r);
        assertEquals(List.of("version two"), ext.skills().findSkill("draft").getLines());
        assertFalse(hash1.equals(ext.skills().findSkill("draft").getContentHash()));

        // the no-name form reloads the whole snapshot
        publicFlat("draft", "version three");
        call(t, "skill", "reload");
        assertEquals(List.of("version three"), ext.skills().findSkill("draft").getLines());
    }

    @Test
    public void reloadOfAnUnknownSkillReportsNotFound() {
        NaruTask t = parent();
        NaruStmtResult r = call(t, "skill", "reload nope");
        assertNotNull(r.errorValue());
        assertTrue(String.valueOf(r.errorValue()).contains("not found after reload"), String.valueOf(r.errorValue()));
    }

    // ── /skill doctor ───────────────────────────────────────────────────────

    @Test
    public void doctorReportsASilentlyChangedSkillAndClearsAfterReload() {
        publicFlat("rules", "original rules");
        ext.reload();
        NaruTask t = parent();
        call(t, "skill", "load rules");

        publicFlat("rules", "edited on disk");
        int before = outputs.size();
        NaruStmtResult r = call(t, "skill", "doctor");
        assertNull(r.errorValue(), () -> "doctor must not error: " + r);
        assertTrue(logsSince(before).stream().anyMatch(l -> l.contains("CHANGED")),
                () -> "the silent edit must be reported: " + logsSince(before));

        // the deliberate reload is the thing that clears the report
        call(t, "skill", "reload rules");
        int before2 = outputs.size();
        call(t, "skill", "doctor");
        assertTrue(logsSince(before2).stream().anyMatch(l -> l.contains("rules : ok")),
                () -> "after reload the report must be clean: " + logsSince(before2));
    }

    @Test
    public void doctorReportsAMissingFileUnderASelectedName() {
        publicFlat("rules", "original rules");
        ext.reload();
        NaruTask t = parent();
        call(t, "skill", "load rules");
        projectDir.resolve(".naru/skills/rules.md").delete();

        int before = outputs.size();
        call(t, "skill", "doctor");
        assertTrue(logsSince(before).stream().anyMatch(l -> l.contains("MISSING")),
                () -> "a deleted selected skill must be reported: " + logsSince(before));
    }

    @Test
    public void doctorReportsEmptyAndUnmappedAllowedTools() {
        publicFlat("blank", "");
        publicFolder("guarded", "name: guarded\ndescription: guarded\nallowed-tools: \"nope_tool\"", "guarded body");
        ext.reload();
        NaruTask t = parent();
        call(t, "skill", "load blank");
        call(t, "skill", "load guarded");

        int before = outputs.size();
        call(t, "skill", "doctor");
        List<String> logs = logsSince(before);
        assertTrue(logs.stream().anyMatch(l -> l.contains("blank : EMPTY")), logs.toString());
        assertTrue(logs.stream().anyMatch(l -> l.contains("allowed-tools 'nope_tool' is not mapped")), logs.toString());
    }

    @Test
    public void doctorReportsAnUnmetRequirement() {
        publicFlat("guarded", "---", "{ requires: \"write\" }", "---", "write only");
        ext.reload();
        NaruTask t = parent(); // a fresh task holds no tags, so "write" is unmet

        call(t, "skill", "load guarded");
        int before = outputs.size();
        call(t, "skill", "doctor");
        assertTrue(logsSince(before).stream().anyMatch(l -> l.contains("is not satisfied")),
                () -> "the unmet requirement must be reported: " + logsSince(before));
    }
}