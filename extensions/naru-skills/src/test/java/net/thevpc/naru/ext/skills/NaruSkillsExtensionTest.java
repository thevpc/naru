package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.naru.api.registry.NaruSessionExtension;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.elem.NElementFormatterStyle;
import net.thevpc.nuts.elem.NElementWriter;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the skills feature now that it lives in an extension: resolution and precedence on
 * disk, the per-task selection rules that replaced the core's spawn-time copy, prompt
 * contribution, and the persisted round trip.
 *
 * <p>No scheduler, no model, no network — the session is built directly and the extension is
 * driven through its own API.
 */
public class NaruSkillsExtensionTest {

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
    private NaruSession session;
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
        projectDir = NPath.ofTempFolder("naru-skills-" + System.nanoTime());
        publicSkill("javadoc", "use javadoc style", "always document public API");
        publicSkill("git-flow", "follow git flow");
        privateSkill("javadoc", "PRIVATE javadoc rules");
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(projectDir);
        session = new NaruSessionImpl(agent, projectDir, null, true, NOOP_LISTENER, null, null, null);
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

    private void publicSkill(String name, String... lines) {
        write(projectDir.resolve(".naru/skills/" + name + ".md"), lines);
    }

    private void privateSkill(String name, String... lines) {
        write(projectDir.resolve(".naru/local/skills/" + name + ".md"), lines);
    }

    private static void write(NPath file, String... lines) {
        file.mkParentDirs();
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        try {
            file.writeString(sb.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private NaruTask task() {
        return task(null);
    }

    private NaruTask task(NaruTask parent) {
        return session.newTask(parent == null
                ? NaruTaskSpec.of()
                : NaruTaskSpec.of().parentId(parent.id()));
    }

    // ── resolution ──────────────────────────────────────────────────────────

    @Test
    public void publicSkillIsResolvedByCanonicalName() {
        NaruSkill s = ext.skills().findSkill("git-flow");
        assertNotNull(s);
        assertEquals("git-flow", s.getName());
        assertEquals(NAruVisibility.PUBLIC, s.getVisibility());
        assertEquals(List.of("follow git flow"), s.getLines());
    }

    @Test
    public void nameMatchingIgnoresCaseAndSeparators() {
        assertNotNull(ext.skills().findSkill("GitFlow"));
        assertNotNull(ext.skills().findSkill("git flow"));
        assertNotNull(ext.skills().findSkill("  GIT-FLOW  "));
    }

    @Test
    public void unknownAndBlankNamesResolveToNull() {
        assertNull(ext.skills().findSkill("nope"));
        assertNull(ext.skills().findSkill(""));
        assertNull(ext.skills().findSkill("   "));
        assertNull(ext.skills().findSkill(null));
        assertNull(ext.skills().findSkillInfo("nope"));
    }

    @Test
    public void privateSkillShadowsPublicCompletely() {
        // the project ships a public javadoc.md, this developer has a local one
        NaruSkill s = ext.skills().findSkill("javadoc");
        assertNotNull(s);
        assertEquals(NAruVisibility.PRIVATE, s.getVisibility());
        // the public lines must not leak in: a local file replaces, it does not append
        assertEquals(List.of("PRIVATE javadoc rules"), s.getLines());
    }

    @Test
    public void availableListsEachNameOnceSortedByName() {
        List<NaruResourceInfo> all = ext.skills().available();
        List<String> names = new ArrayList<>();
        for (NaruResourceInfo i : all) {
            names.add(i.getName());
        }
        assertEquals(List.of("git-flow", "javadoc"), names);
        // javadoc appears once, and as private because that copy won
        NaruResourceInfo javadoc = all.stream()
                .filter(i -> i.getName().equals("javadoc")).findFirst().orElseThrow();
        assertEquals(NAruVisibility.PRIVATE, javadoc.getMode());
    }

    @Test
    public void findSkillInfoReportsTheCanonicalName() {
        NaruResourceInfo info = ext.skills().findSkillInfo("GitFlow");
        assertNotNull(info);
        // the canonical name must round-trip back through findSkill
        assertEquals("git-flow", info.getName());
        assertNotNull(ext.skills().findSkill(info.getName()));
    }

    // ── per-task selection ──────────────────────────────────────────────────

    @Test
    public void nothingIsActiveByDefault() {
        assertEquals(Set.of(), ext.activeNames(task()));
        assertTrue(ext.contribute(task()).isEmpty());
        assertFalse(ext.isRelevant(task()));
    }

    @Test
    public void loadActivatesOnlyForThatTask() {
        NaruTask a = task();
        NaruTask b = task();
        assertTrue(ext.load(a, "git-flow"));
        assertEquals(Set.of("git-flow"), ext.activeNames(a));
        assertEquals(Set.of(), ext.activeNames(b));
    }

    @Test
    public void loadIsIdempotentAndRejectsUnknownSkills() {
        NaruTask a = task();
        assertTrue(ext.load(a, "git-flow"));
        assertFalse(ext.load(a, "git-flow"));
        assertFalse(ext.load(a, "does-not-exist"));
        assertFalse(ext.load(a, ""));
        assertEquals(Set.of("git-flow"), ext.activeNames(a));
    }

    @Test
    public void loadNormalizesSoUnloadMatches() {
        // the core normalized on unload but not on load, so "Git Flow" could be loaded
        // and never unloaded
        NaruTask a = task();
        assertTrue(ext.load(a, "Git Flow"));
        assertTrue(ext.unload(a, "Git Flow"));
        assertEquals(Set.of(), ext.activeNames(a));
    }

    @Test
    public void childInheritsParentSelection() {
        NaruTask parent = task();
        ext.load(parent, "git-flow");
        NaruTask child = task(parent);
        assertEquals(Set.of("git-flow"), ext.activeNames(child));
    }

    @Test
    public void childSelectionAddsToInherited() {
        NaruTask parent = task();
        ext.load(parent, "git-flow");
        NaruTask child = task(parent);
        ext.load(child, "javadoc");
        assertEquals(Set.of("git-flow"), ext.activeNames(parent));
        assertEquals(Set.of("git-flow", "javadoc"), ext.activeNames(child));
    }

    @Test
    public void unloadOnChildMasksInheritedWithoutTouchingParent() {
        NaruTask parent = task();
        ext.load(parent, "git-flow");
        NaruTask child = task(parent);
        assertTrue(ext.unload(child, "git-flow"));
        assertEquals(Set.of(), ext.activeNames(child));
        // the parent keeps it, and so do the parent's other children
        assertEquals(Set.of("git-flow"), ext.activeNames(parent));
        assertEquals(Set.of("git-flow"), ext.activeNames(task(parent)));
    }

    @Test
    public void loadOnChildShadowsParentChoiceForTheSameName() {
        NaruTask parent = task();
        ext.load(parent, "javadoc");
        NaruTask child = task(parent);
        // the child's own copy wins; reloading the shadowed name is a real change
        ext.unload(child, "javadoc");
        assertTrue(ext.load(child, "javadoc"));
        assertEquals(Set.of("javadoc"), ext.activeNames(child));
        assertEquals(Set.of("javadoc"), ext.activeNames(parent));
    }

    @Test
    public void resolutionIsByDepthNotBySpawnTime() {
        // the core snapshotted the parent set onto the child at spawn; resolving at read
        // time means a skill loaded on the parent afterwards still reaches the child
        NaruTask parent = task();
        NaruTask child = task(parent);
        assertEquals(Set.of(), ext.activeNames(child));
        ext.load(parent, "git-flow");
        assertEquals(Set.of("git-flow"), ext.activeNames(child));
    }

    @Test
    public void unloadOfSomethingNeverLoadedReportsFalse() {
        NaruTask a = task();
        assertFalse(ext.unload(a, "git-flow"));
        // a masking unload of a not-inherited skill still counts as no-op
        assertFalse(ext.unload(a, "does-not-exist"));
    }

    // ── prompt contribution ─────────────────────────────────────────────────

    @Test
    public void contributeRendersTheActiveSkillUnderTheSkillSource() {
        NaruTask a = task();
        ext.load(a, "git-flow");
        List<NaruMessage> messages = ext.contribute(a);
        assertEquals(1, messages.size());
        String text = messages.get(0).getContent();
        assertTrue(text.contains("## ACTIVE SKILL DIRECTIVE: GIT-FLOW"), text);
        assertTrue(text.contains("follow git flow"), text);
    }

    @Test
    public void contributeCarriesTheFileAsProvenance() {
        NaruTask a = task();
        ext.load(a, "javadoc");
        NaruMessage m = ext.contribute(a).get(0);
        // the local copy won, so the path must be the local one
        assertTrue(m.getSourceName().contains(".naru/local/skills/javadoc.md"), m.getSourceName());
    }

    @Test
    public void contributeSkipsASelectedSkillThatNoLongerExists() {
        NaruTask a = task();
        ext.load(a, "git-flow");
        // the file is deleted after loading
        projectDir.resolve(".naru/skills/git-flow.md").delete();
        assertTrue(ext.contribute(a).isEmpty());
    }

    @Test
    public void isRelevantBecomesTrueOnceSomethingIsLoaded() {
        NaruTask a = task();
        assertFalse(ext.isRelevant(a));
        ext.load(a, "git-flow");
        assertTrue(ext.isRelevant(a));
    }

    @Test
    public void extensionDeclaresTheSkillSource() {
        assertEquals(Set.of(NaruSource.SKILL), ext.sources());
        assertEquals(NaruSource.SKILL, ext.source());
        assertEquals("skills", ext.name());
    }

    // ── persistence ─────────────────────────────────────────────────────────

    @Test
    public void selectionSurvivesASaveLoadRoundTrip() throws Exception {
        NaruTask a = task();
        ext.load(a, "git-flow");
        ext.load(a, "javadoc");

        NPath file = projectDir.resolve("ext/skills.tson");
        NElement saved = ext.save(session);
        assertNotNull(saved);
        writeTson(saved, file);

        ext.unload(a, "git-flow");
        ext.unload(a, "javadoc");
        assertEquals(Set.of(), ext.activeNames(a));

        ext.load(session, file);
        assertEquals(Set.of("git-flow", "javadoc"), ext.activeNames(a));
    }

    @Test
    public void masksSurviveASaveLoadRoundTrip() throws Exception {
        NaruTask parent = task();
        ext.load(parent, "git-flow");
        NaruTask child = task(parent);
        ext.unload(child, "git-flow");

        NPath file = projectDir.resolve("ext/skills.tson");
        writeTson(ext.save(session), file);

        ext.load(session, file);
        // the child still masks the parent's skill after a reload
        assertEquals(Set.of(), ext.activeNames(child));
        assertEquals(Set.of("git-flow"), ext.activeNames(parent));
    }

    @Test
    public void loadingAMissingStateFileLeavesTheExtensionAtItsInitialState() {
        NaruTask a = task();
        ext.load(a, "git-flow");
        ext.load(session, projectDir.resolve("ext/does-not-exist.tson"));
        assertEquals(Set.of(), ext.activeNames(a));
    }

    @Test
    public void savedStateIsReadableAsTson() throws Exception {
        NaruTask a = task();
        ext.load(a, "git-flow");
        NPath file = projectDir.resolve("ext/skills.tson");
        writeTson(ext.save(session), file);
        String tson = new String(java.nio.file.Files.readAllBytes(file.toPath().orElseThrow(() -> new IllegalStateException("no path: " + file))));
        assertTrue(tson.contains("git-flow"), tson);
        assertTrue(tson.contains("schemaVersion"), tson);
    }

    private static void writeTson(NElement e, NPath file) {
        NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                .write(e, file);
    }

    // ── the extension is discoverable, and optional ──────────────────────────

    @Test
    public void theExtensionIsRegisteredAsASessionExtension() {
        List<String> names = new ArrayList<>();
        for (NaruSessionExtension e : session.registry().sessionExtensions()) {
            names.add(e.name());
        }
        assertTrue(names.contains("skills"), names.toString());
    }

    @Test
    public void theDirectiveIsRegistered() {
        // "skill" is an alias, and aliases live in their own index rather than in
        // directives(), so both spellings have to go through the real lookup
        assertEquals("skills", session.registry().findDirective("skills").orElseThrow(() -> new AssertionError("no /skills directive")).name());
        assertEquals("skills", session.registry().findDirective("skill").orElseThrow(() -> new AssertionError("no /skill alias")).name());
    }

    @Test
    public void theCoreNoLongerCarriesTheSkillTypes() throws Exception {
        // the whole point of the extraction: nothing in naru-api/naru-impl names the
        // feature any more, so with naru-skills off the classpath the command simply does
        // not exist and the core still starts
        java.nio.file.Path core = java.nio.file.Path.of(System.getProperty("user.dir"));
        List<String> offenders = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                     java.nio.file.Files.walk(core, 6)) {
            walk.filter(p -> p.toString().contains("/core/naru-"))
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .forEach(p -> {
                        try {
                            String text = new String(java.nio.file.Files.readAllBytes(p));
                            if (text.contains("NaruSkill") || text.contains("skillManager")) {
                                offenders.add(p.getFileName().toString());
                            }
                        } catch (Exception ignored) {
                        }
                    });
        }
        assertEquals(List.of(), offenders,
                "core still references the skills feature: " + offenders);
    }

}
