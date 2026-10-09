package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContextImpl;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5: the {@code skill} tool, the {@code skills} tag that gates it, the catalog that only
 * appears alongside it, and the model-driven load (including propagation to live children,
 * foreign framing, and survival across compaction).
 */
@Timeout(60)
public class NaruSkillsToolTest {

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
        projectDir = NPath.ofTempFolder("naru-skills-tool-" + System.nanoTime());
        userHome = NPath.ofTempFolder("naru-skills-tool-home-" + System.nanoTime());
        System.setProperty("naru.skills.userHome", userHome.toString());
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        session = new NaruSessionImpl(agent, projectDir, null, true, NOOP_LISTENER, null, null, null);
        ext = NaruSkillsExtension.skills(session);
        publicFlat("javadoc", "use javadoc style", "always document public API");
        publicFlat("git-flow", "follow git flow");
        ext.reload();
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

    private void publicFlat(String name, String... lines) {
        write(projectDir.resolve(".naru/skills/" + name + ".md"), lines);
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

    private NaruTask task() {
        return session.newTask(NaruTaskSpec.of());
    }

    private NaruTask taskWithSkillsTag() {
        NaruTask t = session.newTask(NaruTaskSpec.of());
        t.addToolTag(NaruSkillsToolTagProvider.SKILLS_TAG);
        return t;
    }

    private boolean toolVisible(NaruTask task, String name) {
        for (NaruToolDefinition d : task.findTools()) {
            if (name.equals(d.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String executeTool(NaruTask task, String name) {
        NaruTool tool = task.session().registry().findTool("skill")
                .orElseThrow(() -> new AssertionError("the skill tool is not registered"));
        return tool.execute(new NaruToolCallContextImpl(Map.of("name", name), task));
    }

    // ── registration and the visibility gate ───────────────────────────────

    @Test
    public void theSkillToolAndItsTagAreRegistered() {
        NaruTool tool = session.registry().findTool("skill")
                .orElseThrow(() -> new AssertionError("the skill tool is not registered"));
        assertTrue(tool.tags().contains(NaruSkillsToolTagProvider.SKILLS_TAG),
                () -> "the skill tool must wear the skills tag: " + tool.tags());
        assertTrue(session.registry().findAvailableTag(NaruSkillsToolTagProvider.SKILLS_TAG).isPresent(),
                "the skills tag provider must declare the tag");
    }

    @Test
    public void withoutTheSkillsTagNeitherTheToolNorTheCatalogAppears() {
        NaruTask t = task();
        assertFalse(toolVisible(t, "skill"), "a task without the skills tag must not see the skill tool");
        List<NaruMessage> messages = ext.contribute(t);
        assertTrue(messages.isEmpty(),
                () -> "with no tagged tool there is nothing the model could act on: " + messages);
    }

    @Test
    public void grantingTheSkillsTagRevealsTheToolAndTheCatalog() {
        NaruTask t = taskWithSkillsTag();
        assertTrue(toolVisible(t, "skill"), "holding the skills tag must reveal the skill tool");
        List<NaruMessage> messages = ext.contribute(t);
        assertTrue(messages.stream().anyMatch(m -> m.getContent().contains("## AVAILABLE SKILL: GIT-FLOW")),
                () -> "the catalog must accompany the visible tool: " + messages);
        // catalog rows carry the distinct catalog source so /context can tell them apart
        assertTrue(messages.stream().anyMatch(m ->
                        m.getSourceName() != null && m.getSourceName().startsWith(NaruSkillsExtension.CATALOG_SOURCE_PREFIX)),
                () -> "catalog rows must be attributed as catalog: " + messages);
    }

    // ── execute: body, base dir, propagation ───────────────────────────────

    @Test
    public void theToolLoadsReturnsTheBodyAndTheBaseDirectory() {
        publicFolder("pdf-reader", "name: pdf-reader\ndescription: read pdfs", "step one", "step two");
        ext.reload();
        NaruTask t = taskWithSkillsTag();

        String result = executeTool(t, "pdf-reader");

        assertTrue(result.contains("Skill loaded: pdf-reader"), result);
        assertTrue(result.contains("Base directory:"), result);
        assertTrue(result.contains(".naru/skills/pdf-reader"), result);
        assertTrue(result.contains("step one"), result);
        assertTrue(result.contains("step two"), result);
        assertEquals(NaruSkillState.LOADED, ext.state(t, "pdf-reader"));
    }

    @Test
    public void aModelLoadPropagatesToTheLiveChildren() {
        publicFlat("git-flow", "follow git flow");
        ext.reload();
        NaruTask parent = taskWithSkillsTag();
        NaruTask child = session.newTask(NaruTaskSpec.of().parentId(parent.id()));

        String result = executeTool(parent, "git-flow");

        assertTrue(result.contains("Skill loaded: git-flow"), result);
        assertEquals(java.util.Set.of("git-flow"), ext.activeNames(parent));
        assertEquals(java.util.Set.of("git-flow"), ext.activeNames(child),
                () -> "a model-initiated load must reach the source task's existing children: "
                        + ext.activeNames(child));
    }

    @Test
    public void anUnknownNameYieldsAnErrorRatherThanAnEmptyBody() {
        NaruTask t = taskWithSkillsTag();
        String result = executeTool(t, "does-not-exist");
        assertTrue(result.startsWith("error: skill not found"), result);
        assertEquals(java.util.Set.of(), ext.activeNames(t));
    }

    // ── foreign framing ────────────────────────────────────────────────────

    @Test
    public void aForeignSkillIsFramedAsUntrustedInTheToolResult() {
        write(projectDir.resolve(".claude/skills/legacy.md"), "legacy instructions");
        ext.reload();
        NaruTask t = taskWithSkillsTag();
        NaruSkillRoot claude = root(ext.roots(t), "claude");
        assertNotNull(claude, "the .claude root must be listed");
        ext.trust(claude, true);
        ext.reload();

        String result = executeTool(t, "legacy");

        assertTrue(result.contains("UNTRUSTED SKILL SOURCE (claude)"), result);
    }

    @Test
    public void aForeignBodyIsFramedAsUntrustedInTheContribution() {
        write(projectDir.resolve(".claude/skills/legacy.md"), "legacy instructions");
        ext.reload();
        NaruTask t = taskWithSkillsTag();
        ext.trust(root(ext.roots(t), "claude"), true);
        ext.load(t, "legacy");

        List<NaruMessage> messages = ext.contribute(t);

        assertTrue(messages.stream().anyMatch(m ->
                        m.getContent().contains("UNTRUSTED SKILL SOURCE (claude)")),
                () -> "a foreign body must be framed, not injected as native instruction: " + messages);
        assertTrue(messages.stream().anyMatch(m -> m.getContent().contains("legacy instructions")), messages.toString());
    }

    private static NaruSkillRoot root(List<NaruSkillRoot> roots, String label) {
        for (NaruSkillRoot r : roots) {
            if (label.equals(r.label())) {
                return r;
            }
        }
        return null;
    }

    // ── survives compaction (re-injected fresh each request) ───────────────

    @Test
    public void aLoadedSkillIsReinjectedAfterTheHistoryIsCompacted() {
        NaruTask t = taskWithSkillsTag();
        ext.load(t, "git-flow");
        assertTrue(activeBodyPresent(t), "precondition: the body is injected for the first request");

        // compaction rewrites history in place; the body is not history, it is contributed
        // fresh on every request, so it must come back unchanged
        t.clearHistory();

        assertTrue(activeBodyPresent(t),
                () -> "a loaded skill must survive compaction via re-injection: " + ext.contribute(t));
    }

    private static boolean activeBodyPresent(NaruTask task) {
        NaruSkillsExtension e = NaruSkillsExtension.skills(task.session());
        return e.contribute(task).stream()
                .anyMatch(m -> m.getContent().contains("## ACTIVE SKILL DIRECTIVE: GIT-FLOW"));
    }
}
