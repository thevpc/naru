package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.spawn.NaruToolTagExpression;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The skill file front-matter ({@code requires}) and the session extension's
 * {@code onSpawned}: a resolved spawn skill is loaded onto the freshly spawned child, and a
 * skill whose required tags clash with what the child was granted is surfaced as a
 * spawn-time warning naming exactly what is missing or held.
 */
@Timeout(60)
public class NaruSkillsSpawnTest {

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
        projectDir = NPath.ofTempFolder("naru-skills-spawn-" + System.nanoTime());
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

    private void publicSkill(String name, String header, String... body) {
        StringBuilder sb = new StringBuilder();
        if (header != null) {
            sb.append("---\n").append(header).append("\n---\n");
        }
        for (String l : body) {
            sb.append(l).append('\n');
        }
        NPath file = projectDir.resolve(".naru/skills").resolve(name + ".md");
        file.mkParentDirs();
        file.writeString(sb.toString());
    }

    private void privateSkill(String name, String header, String... body) {
        StringBuilder sb = new StringBuilder();
        if (header != null) {
            sb.append("---\n").append(header).append("\n---\n");
        }
        for (String l : body) {
            sb.append(l).append('\n');
        }
        NPath file = projectDir.resolve(".naru/local/skills").resolve(name + ".md");
        file.mkParentDirs();
        file.writeString(sb.toString());
    }

    private NaruTask task() {
        return session.newTask(NaruTaskSpec.of());
    }

    private NaruTask spawn(NaruTask parent, NaruTaskSpec spec) {
        return session.newTask(spec.parentId(parent.id()));
    }

    // ── front matter parsing ────────────────────────────────────────────────

    @Test
    public void requiresFrontMatterParsesIntoARequirement() {
        publicSkill("fs-ops", "{ requires: \"fs & !write\" }", "use fs like a pro", "never write");
        NaruSkill s = ext.skills().findSkill("fs-ops");
        assertNotNull(s);

        NaruToolTagExpression requires = s.getRequires();
        assertNotNull(requires, () -> "the requires front-matter was not parsed");
        assertEquals(Set.of("fs"), s.getRequiredTags());
        assertTrue(requires.matches(Set.of("fs")), "fs alone must satisfy fs & !write");
        assertTrue(!requires.matches(Set.of("fs", "write")), "holding write must violate fs & !write");
        // the header is stripped from the body
        assertEquals(List.of("use fs like a pro", "never write"), s.getLines());
    }

    @Test
    public void aSkillWithoutFrontMatterHasNoRequirement() {
        publicSkill("plain", null, "no constraint at all");
        NaruSkill s = ext.skills().findSkill("plain");
        assertNull(s.getRequires());
        assertEquals(Set.of(), s.getRequiredTags());
        assertEquals(List.of("no constraint at all"), s.getLines());
    }

    @Test
    public void aMalformedRequiresLoadsAsAbsentRatherThanHidingTheSkill() {
        publicSkill("broken", "{ requires: \"fs & ((\" }", "still loads");
        NaruSkill s = ext.skills().findSkill("broken");
        assertNotNull(s, "a broken requirement must not hide the skill");
        assertNull(s.getRequires());
        assertEquals(List.of("still loads"), s.getLines());
    }

    @Test
    public void thePrivateCopySuppliesTheRequirementToo() {
        publicSkill("report", null, "public wording");
        privateSkill("report", "{ requires: \"fs\" }", "private wording");
        NaruSkill s = ext.skills().findSkill("report");
        assertEquals(List.of("private wording"), s.getLines());
        assertEquals(Set.of("fs"), s.getRequiredTags());
    }

    // ── onSpawned: resolved skills land on the child ───────────────────────

    @Test
    public void aResolvedSkillIsLoadedOntoTheSpawnedChild() {
        publicSkill("git-flow", null, "follow git flow");
        NaruTask parent = task();

        NaruTask child = spawn(parent, NaruTaskSpec.of().addSkills("git-flow"));

        assertEquals(Set.of("git-flow"), ext.activeNames(child),
                () -> "the resolved spawn skill was not loaded onto the child: " + ext.activeNames(child));
        assertEquals(Set.of(), ext.activeNames(parent),
                "the parent's selection must not be touched by the child spawn");
    }

    @Test
    public void noResolvedSkillsLeavesTheChildSelectionEmpty() {
        publicSkill("git-flow", null, "follow git flow");
        NaruTask parent = task();

        NaruTask child = spawn(parent, NaruTaskSpec.of());

        assertEquals(Set.of(), ext.activeNames(child));
    }

    @Test
    public void aClashingRequirementWarnsNamingWhatIsMissingOrHeld() {
        publicSkill("fs-aware", "{ requires: \"fs & !write\" }", "fs only, never write");
        NaruTask parent = task();

        // the child holds write (so the !write clause is violated) but not fs (so the
        // positive clause is violated too) — both sides of the fix hint appear
        NaruTask child = spawn(parent, NaruTaskSpec.of().addTags("write").addSkills("fs-aware"));

        assertEquals(Set.of("fs-aware"), ext.activeNames(child),
                "the skill must still load; the warning is a warning, not a refusal");
        assertTrue(outputs.stream().anyMatch(o -> o.message().toString().contains("skill 'fs-aware' requires fs")),
                () -> "expected the spawn-time inconsistency warning: " + outputs);
        assertTrue(outputs.stream().anyMatch(o -> o.message().toString().contains("lacks fs")),
                () -> "the warning must name the missing tag: " + outputs);
        assertTrue(outputs.stream().anyMatch(o -> o.message().toString().contains("holds write")),
                () -> "the warning must name the conflicting held tag: " + outputs);
        assertTrue(outputs.stream().anyMatch(o -> o.message().toString().contains("--add-tags")),
                () -> "the warning must point at the fixing flags: " + outputs);
    }

    @Test
    public void aSatisfiedRequirementLoadsSilently() {
        publicSkill("fs-aware", "{ requires: \"write\" }", "write only");
        NaruTask parent = task();

        // the requirement is checked against the CHILD's resolved tags, so the spawn must grant it
        NaruTask child = spawn(parent, NaruTaskSpec.of().addTags("write").addSkills("fs-aware"));

        assertEquals(Set.of("fs-aware"), ext.activeNames(child));
        assertTrue(outputs.stream().noneMatch(o -> o.message().toString().contains("inconsistent")),
                () -> "a satisfied requirement must not warn: " + outputs);
    }
}