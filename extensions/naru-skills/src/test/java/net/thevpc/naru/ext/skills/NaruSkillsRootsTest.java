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
 * WP6: the ordered root model, the foreign-root trust gate and its persistence, the
 * folder-scoped walk, and the shadowed entries that keep losing copies visible.
 */
@Timeout(60)
public class NaruSkillsRootsTest {

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
    private NPath userHome;
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
        projectDir = NPath.ofTempFolder("naru-skills-roots-" + System.nanoTime());
        userHome = NPath.ofTempFolder("naru-skills-roots-home-" + System.nanoTime());
        System.setProperty("naru.skills.userHome", userHome.toString());
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
        System.clearProperty("naru.skills.userHome");
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void write(NPath file, String... lines) {
        file.mkParentDirs();
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        file.writeString(sb.toString());
    }

    private void publicSkill(String name, String... body) {
        folderSkill(projectDir.resolve(".naru/skills"), name, body);
    }

    private void privateSkill(String name, String... body) {
        folderSkill(projectDir.resolve(".naru/local/skills"), name, body);
    }

    private void foreignSkill(String label, String name, String... body) {
        folderSkill(projectDir.resolve(foreignProjectDir(label)), name, body);
    }

    private void foreignUserSkill(String label, String name, String... body) {
        folderSkill(userHome.resolve(foreignUserDir(label)), name, body);
    }

    private void folderScope(String relativeDir, String name, String... body) {
        folderSkill(projectDir.resolve(relativeDir + "/.naru/skills"), name, body);
    }

    private static String foreignProjectDir(String label) {
        return switch (label) {
            case "claude" -> ".claude/skills";
            case "agents" -> ".agents/skills";
            case "opencode" -> ".opencode/skills";
            default -> throw new IllegalArgumentException(label);
        };
    }

    private static String foreignUserDir(String label) {
        return switch (label) {
            case "claude" -> ".claude/skills";
            case "agents" -> ".agents/skills";
            case "opencode" -> ".config/opencode/skills";
            default -> throw new IllegalArgumentException(label);
        };
    }

    /** Writes a standard folder skill: {@code <root>/<name>/SKILL.md} with a front-matter. */
    private void folderSkill(NPath root, String name, String... body) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\nname: ").append(name)
                .append("\ndescription: ").append(name).append(" description\n---\n");
        for (String l : body) {
            sb.append(l).append('\n');
        }
        write(root.resolve(name + "/SKILL.md"), sb.toString().split("\n", -1));
    }

    private NaruTask task() {
        return session.newTask(NaruTaskSpec.of());
    }

    private NaruTask taskAt(NPath workingDir) {
        return session.newTask(NaruTaskSpec.of().workingDirectory(workingDir));
    }

    private static NaruSkillRoot root(List<NaruSkillRoot> roots, String label) {
        for (NaruSkillRoot r : roots) {
            if (label.equals(r.label())) {
                return r;
            }
        }
        return null;
    }

    private NaruStmtResult call(NaruTask task, String argument) {
        NaruDirective d = session.registry().findDirective("skill")
                .orElseThrow(() -> new AssertionError("no /skill directive registered"));
        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("skill", argument, task);
        return d.execute(ctx);
    }

    // ── ordered roots ──────────────────────────────────────────────────────

    @Test
    public void rootsAreOrderedStrongestFirstWithEveryNativeAheadOfForeign() {
        foreignSkill("claude", "legacy", "legacy body");
        ext.reload();
        List<NaruSkillRoot> roots = ext.roots(task());

        assertFalse(roots.isEmpty());
        assertEquals(NaruSkillRootKind.PROJECT_PRIVATE, roots.get(0).kind(),
                () -> "the private project root must be strongest: " + roots);
        assertTrue(roots.get(0).path().toString().endsWith(".naru/local/skills"), roots.get(0).path().toString());

        int lastNative = -1;
        int firstForeign = Integer.MAX_VALUE;
        for (int i = 0; i < roots.size(); i++) {
            if (roots.get(i).kind().foreign()) {
                firstForeign = Math.min(firstForeign, i);
            } else {
                lastNative = Math.max(lastNative, i);
            }
        }
        assertTrue(lastNative < firstForeign,
                () -> "every NARU-native root must precede every foreign root: " + roots);

        NaruSkillRoot claude = root(roots, "claude");
        assertNotNull(claude, () -> "the .claude root must be enumerated even before trust: " + roots);
        assertTrue(claude.requiresTrust());
        assertFalse(claude.trusted(), "a foreign root starts untrusted");
    }

    // ── foreign roots are opt-in and persisted ─────────────────────────────

    @Test
    public void aForeignRootContributesNothingUntilTrusted() {
        foreignSkill("claude", "legacy", "legacy body");
        ext.reload();

        assertNull(ext.skills().findSkill("legacy"),
                "an untrusted foreign skill must not be resolvable");
        assertTrue(ext.skills().available().isEmpty(),
                () -> "an untrusted foreign root must not leak skills: " + ext.skills().available());

        NaruTask t = task();
        assertTrue(ext.trust(root(ext.roots(t), "claude"), true));

        NaruSkill skill = ext.skills().findSkill("legacy");
        assertNotNull(skill, "after trust the foreign skill resolves");
        assertTrue(skill.isForeign());
        assertTrue(skill.isTrusted());
    }

    @Test
    public void naruNativeWinsAndTheForeignCopyStaysVisibleAsShadowed() {
        publicSkill("shared", "native body");
        foreignSkill("claude", "shared", "foreign body");
        ext.reload();
        NaruTask t = task();
        ext.trust(root(ext.roots(t), "claude"), true);

        NaruSkill winner = ext.skills().findSkill(t, "shared");
        assertNotNull(winner);
        assertEquals(List.of("native body"), winner.getLines(),
                () -> "the NARU-native copy must win: " + winner.getSourceName());
        assertTrue(winner.getSourceName().contains(".naru/skills/shared/SKILL.md"), winner.getSourceName());
        assertTrue(winner.isShadowed(), "two copies of the name means the winner is shadowed");

        List<NaruSkillEntry> entries = ext.entries(t);
        long copies = entries.stream().filter(e -> e.skill().getName().equals("shared")).count();
        assertEquals(2, copies, () -> "both copies must stay visible: " + entries);
        assertTrue(entries.stream().anyMatch(e -> e.shadowed() && e.skill().isForeign()),
                () -> "the losing foreign copy must be marked shadowed: " + entries);
    }

    @Test
    public void trustIsPersistedPerProjectRootAndReadBack() {
        foreignSkill("claude", "legacy", "legacy body");
        ext.reload();
        NaruTask t = task();
        NaruSkillRoot claude = root(ext.roots(t), "claude");
        ext.trust(claude, true);

        NPath file = projectDir.resolve(".naru/local/skills-trust.tson");
        assertTrue(file.isRegularFile(), "trust must be persisted: " + file);
        String tson = file.readString();
        assertTrue(tson.contains("claude"), tson);
        assertTrue(tson.contains(".claude/skills"),
                () -> "the key must be the root path relative to the project, so the project can move: " + tson);

        // a fresh store over the same scope reads the decision back
        NaruSkillRoot concrete = new NaruSkillRoot(NaruSkillRootKind.FOREIGN_PROJECT,
                projectDir.resolve(".claude/skills"), "claude", 6000, false);
        NaruSkillTrustStore store = new NaruSkillTrustStore(projectDir, userHome);
        assertTrue(store.isTrusted(concrete), "the persisted decision must be read back");

        // and untrusting removes it again
        ext.trust(claude, false);
        assertNull(ext.skills().findSkill("legacy"));
        assertFalse(file.isRegularFile(), "an empty trust store is removed");
    }

    @Test
    public void userLevelForeignTrustIsPersistedUnderTheUserHome() {
        foreignUserSkill("claude", "personal", "personal body");
        ext.reload();
        NaruTask t = task();
        NaruSkillRoot claudeUser = null;
        for (NaruSkillRoot r : ext.roots(t)) {
            if (r.kind() == NaruSkillRootKind.FOREIGN_USER && "claude".equals(r.label())) {
                claudeUser = r;
            }
        }
        assertNotNull(claudeUser, "the user-level foreign root must be listed");

        ext.trust(claudeUser, true);

        NPath file = userHome.resolve(".naru/skills-trust.tson");
        assertTrue(file.isRegularFile(), "user-level trust must land under the user home: " + file);
        assertNotNull(ext.skills().findSkill("personal"));
    }

    // ── folder-scoped walk ─────────────────────────────────────────────────

    @Test
    public void folderScopedSkillsAreAdvertisedNeverAutoLoadedAndLeaveWithTheDirectory() {
        folderScope("sub", "folder-skill", "scoped to sub");
        ext.reload();

        NaruTask atProject = task();
        assertNull(ext.skills().findSkill(atProject, "folder-skill"),
                "a folder-scoped skill must not be visible from the project root");

        NPath sub = projectDir.resolve("sub");
        NaruTask atSub = taskAt(sub);
        NaruSkill skill = ext.skills().findSkill(atSub, "folder-skill");
        assertNotNull(skill, "a task working inside the folder must see its skill");
        assertEquals(NaruSkillState.ADVERTISED, ext.state(atSub, "folder-skill"));
        assertEquals(Set.of(), ext.activeNames(atSub), "folder skills are advertised, never auto-loaded");

        // leaving the folder removes them with no hook and no undo: the next resolution walks
        // from the task's new working directory and simply does not see them
        atSub.setWorkingDir(projectDir);
        assertNull(ext.skills().findSkill(atSub, "folder-skill"),
                "leaving the folder must drop the folder-scoped skill");
    }

    @Test
    public void theClosestFolderScopeWinsForTheSameName() {
        publicSkill("scope", "project body");
        folderScope("sub", "scope", "sub body");
        ext.reload();

        NPath sub = projectDir.resolve("sub");
        NaruTask atSub = taskAt(sub);
        NaruSkill atSubSkill = ext.skills().findSkill(atSub, "scope");
        assertNotNull(atSubSkill);
        assertEquals(List.of("sub body"), atSubSkill.getLines(),
                () -> "the closest folder must win: " + atSubSkill.getSourceName());
        assertTrue(atSubSkill.getOriginRoot().contains("/sub/.naru/skills"), atSubSkill.getOriginRoot());

        NaruSkill atProjectSkill = ext.skills().findSkill(task(), "scope");
        assertEquals(List.of("project body"), atProjectSkill.getLines());
    }

    @Test
    public void aFolderSkillIsContributedToATaskWorkingInsideTheFolder() {
        folderScope("sub", "folder-skill", "scoped body");
        ext.reload();
        NaruTask atSub = taskAt(projectDir.resolve("sub"));
        atSub.addToolTag(NaruSkillsToolTagProvider.SKILLS_TAG);

        assertTrue(ext.contribute(atSub).stream()
                        .anyMatch(m -> m.getContent().contains("## AVAILABLE SKILL: FOLDER-SKILL")),
                () -> "a folder skill must be advertised to the task inside the folder: " + ext.contribute(atSub));
    }

    // ── /skill trust + list ────────────────────────────────────────────────

    @Test
    public void theTrustCommandTogglesAForeignRootAndListShowsItsState() {
        foreignSkill("claude", "legacy", "legacy body");
        ext.reload();
        NaruTask t = task();

        int before = outputs.size();
        NaruStmtResult r = call(t, "trust claude");
        assertNull(r.errorValue(), () -> "trust must not error: " + r);
        assertTrue(outputs.stream().skip(before).anyMatch(o -> o.message().toString().contains("Trusted")),
                () -> outputs.toString());
        assertNotNull(ext.skills().findSkill("legacy"), "after /skill trust the foreign skill resolves");

        NaruStmtResult list = call(t, "list");
        String out = String.valueOf(list.successValue());
        assertTrue(out.contains("roots (strongest first):"), out);
        assertTrue(out.contains("claude"), out);
        assertTrue(out.contains("trusted"), out);
        // the foreign copy of a name is a real row, not silently swallowed
        assertTrue(out.contains("legacy"), out);

        NaruStmtResult untrust = call(t, "untrust claude");
        assertNull(untrust.errorValue(), () -> "untrust must not error: " + untrust);
        assertNull(ext.skills().findSkill("legacy"), "after /skill untrust the foreign skill is gone again");

        // a NARU-native root is never trustable
        NaruStmtResult nativeRoot = call(t, "trust .naru/local/skills");
        assertNull(nativeRoot.errorValue());
        assertTrue(String.valueOf(nativeRoot.successValue()).contains("NARU-native"),
                String.valueOf(nativeRoot.successValue()));
    }
}
