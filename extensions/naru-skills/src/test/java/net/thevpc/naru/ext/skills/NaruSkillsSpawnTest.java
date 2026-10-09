package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.spawn.NaruSpawnContract;
import net.thevpc.naru.api.spawn.NaruSpawnPolicy;
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
 * The skill front-matter ({@code requires}) and the session extension's {@code onSpawned}:
 * a resolved spawn skill (policy, contract, or {@code --add-skills}) is loaded onto the
 * freshly spawned child. Spawning never evaluates {@code requires} — decision 4 moved the
 * gate to request-build time, where the task's current tags are the ones that matter.
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

    private NaruTask task() {
        return session.newTask(NaruTaskSpec.of());
    }

    /** Rebuilds the discovery snapshot from disk (the manager reads disk only on reload). */
    private void discover() {
        ext.reload();
    }

    private NaruTask spawn(NaruTask parent, NaruTaskSpec spec) {
        return session.newTask(spec.parentId(parent.id()));
    }

    // ── front matter parsing ────────────────────────────────────────────────

    @Test
    public void requiresFrontMatterParsesIntoARequirement() {
        publicSkill("fs-ops", "{ requires: \"fs & !write\" }", "use fs like a pro", "never write");
        discover();
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
        discover();
        NaruSkill s = ext.skills().findSkill("plain");
        assertNull(s.getRequires());
        assertEquals(Set.of(), s.getRequiredTags());
        assertEquals(List.of("no constraint at all"), s.getLines());
    }

    @Test
    public void aMalformedRequiresLoadsAsAbsentRatherThanHidingTheSkill() {
        publicSkill("broken", "{ requires: \"fs & ((\" }", "still loads");
        discover();
        NaruSkill s = ext.skills().findSkill("broken");
        assertNotNull(s, "a broken requirement must not hide the skill");
        assertNull(s.getRequires());
        assertEquals(List.of("still loads"), s.getLines());
    }

    @Test
    public void thePrivateCopySuppliesTheRequirementToo() {
        publicSkill("report", null, "public wording");
        privateSkill("report", "{ requires: \"fs\" }", "private wording");
        discover();
        NaruSkill s = ext.skills().findSkill("report");
        assertEquals(List.of("private wording"), s.getLines());
        assertEquals(Set.of("fs"), s.getRequiredTags());
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

    // ── onSpawned: resolved skills land on the child ───────────────────────

    @Test
    public void aResolvedSkillIsLoadedOntoTheSpawnedChild() {
        publicSkill("git-flow", null, "follow git flow");
        NaruTask parent = task();

        NaruTask child = spawn(parent, NaruTaskSpec.of().addSkills("git-flow"));

        assertEquals(Set.of("git-flow"), ext.activeNames(child),
                () -> "the --add-skills resolution was not loaded onto the child: " + ext.activeNames(child));
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
    public void aSpawnPolicySeedsItsSkillsOntoTheChild() {
        publicSkill("code-review", null, "review with care");
        NaruTask parent = task();
        session.defineSpawnPolicy(new NaruSpawnPolicy("review-safe").addSkills("code-review"));

        NaruTask child = spawn(parent, NaruTaskSpec.of().policy("review-safe"));

        assertEquals(Set.of("code-review"), ext.activeNames(child),
                () -> "the policy's skills must seed the child: " + ext.activeNames(child));
    }

    @Test
    public void aContractSeedsItsSkillsOntoTheChild() {
        publicSkill("code-review", null, "review with care");
        NaruTask parent = task();
        NaruSpawnContract contract = NaruSpawnContract.parse("{ skills: [\"code-review\"] }");

        NaruTask child = spawn(parent, NaruTaskSpec.of().contract(contract));

        assertEquals(Set.of("code-review"), ext.activeNames(child),
                () -> "the contract's skills must seed the child: " + ext.activeNames(child));
    }

    // ── requires is evaluated at request-build time, never at spawn ───────

    @Test
    public void spawnNeverWarnsAboutRequiresEvenWhenItClashes() {
        publicSkill("fs-aware", "{ requires: \"!write\" }", "fs only, never write");
        NaruTask parent = task();
        // the default task holds write, which the skill forbids — yet spawning must not
        // complain: the gate is a request-time concern, not a spawn-time refusal
        NaruTask child = spawn(parent, NaruTaskSpec.of().addSkills("fs-aware"));

        assertEquals(Set.of("fs-aware"), ext.activeNames(child),
                "the skill must still load; requires is not a spawn-time rejection");
        assertTrue(outputs.stream().noneMatch(o -> o.message().toString().contains("requires")),
                () -> "spawn-time must not mention requires at all: " + outputs);
        assertTrue(outputs.stream().noneMatch(o -> o.message().toString().contains("--add-tags")),
                () -> "the spawn-time fixing hint is gone: " + outputs);
    }

    @Test
    public void theRequestBuildInjectTheGatedBodyOnlyWhenSatisfied() {
        publicSkill("fs-aware", "{ requires: \"write\" }", "write only");
        NaruTask parent = task();

        // the child holds write, so the request-build gate opens and the body is injected
        NaruTask child = spawn(parent, NaruTaskSpec.of().addTags("write").addSkills("fs-aware"));
        assertTrue(ext.contribute(child).stream()
                        .anyMatch(m -> m.getContent().contains("## ACTIVE SKILL DIRECTIVE: FS-AWARE")),
                () -> "a satisfied requirement must inject the body: " + ext.contribute(child));
    }

    @Test
    public void revokingATagWhileTheSkillIsLoadedLeavesItLoadedButGated() {
        publicSkill("fs-aware", "{ requires: \"write\" }", "write only");
        NaruTask parent = task();
        NaruTask child = spawn(parent, NaruTaskSpec.of().addTags("write").addSkills("fs-aware"));
        assertTrue(ext.contribute(child).stream()
                .anyMatch(m -> m.getContent().contains("ACTIVE SKILL DIRECTIVE")),
                "precondition: before the revoke the body is injected");

        child.removeToolTag("write");

        // decision 4: still LOADED — the tag revocation does not unload the skill
        assertEquals(NaruSkillState.LOADED, ext.state(child, "fs-aware"));
        assertTrue(ext.contribute(child).stream()
                        .anyMatch(m -> m.getContent().contains("## SKILL REQUIRES GATE (UNSATISFIED): FS-AWARE")),
                () -> "the revoked tag must gate the body at request-build: " + ext.contribute(child));
        assertTrue(ext.contribute(child).stream()
                        .noneMatch(m -> m.getContent().contains("## ACTIVE SKILL DIRECTIVE: FS-AWARE")),
                "the gated body must not be injected");
    }
}