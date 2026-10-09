package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.agent.NaruAgent;
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
import net.thevpc.nuts.elem.NArrayElementBuilder;
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
 * The skills feature now that it lives in an extension: v2 discovery (folder and flat
 * layouts, snapshots, reload), the flat per-task selection with ADVERTISED default and
 * LOADED on request, requires gating at request-build time, the persisted round trip, and
 * the v1→v2 migration.
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
        publicFlat("javadoc", "use javadoc style", "always document public API");
        publicFlat("git-flow", "follow git flow");
        privateFlat("javadoc", "PRIVATE javadoc rules");
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

    private void publicFlat(String name, String... lines) {
        write(projectDir.resolve(".naru/skills/" + name + ".md"), lines);
    }

    private void privateFlat(String name, String... lines) {
        write(projectDir.resolve(".naru/local/skills/" + name + ".md"), lines);
    }

    private void publicFolder(String name, Object frontMatter, String... body) {
        writeFolder(projectDir.resolve(".naru/skills/" + name), "SKILL.md", frontMatter, body);
    }

    private void privateFolder(String name, Object frontMatter, String... body) {
        writeFolder(projectDir.resolve(".naru/local/skills/" + name), "SKILL.md", frontMatter, body);
    }

    private static void writeFolder(NPath dir, String fileName, Object frontMatter, String... body) {
        StringBuilder sb = new StringBuilder();
        if (frontMatter != null) {
            sb.append("---\n").append(frontMatter).append("\n---\n");
        }
        for (String l : body) {
            sb.append(l).append('\n');
        }
        write(dir.resolve(fileName), sb.toString().split("\n", -1));
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
        // hold the skills tool tag so the advertised catalog is visible: the catalog is
        // emitted only alongside the "skill" tool, matching what a real request sees.
        NaruTask t = session.newTask(parent == null
                ? NaruTaskSpec.of()
                : NaruTaskSpec.of().parentId(parent.id()));
        t.addToolTag("skills");
        return t;
    }

    /** Rebuilds the discovery snapshot from disk (the manager reads disk only on reload). */
    private void discover() {
        ext.reload();
    }

    // ── discovery: value, layout, snapshot ─────────────────────────────────

    @Test
    public void publicFlatSkillIsResolvedByCanonicalName() {
        NaruSkill s = ext.skills().findSkill("git-flow");
        assertNotNull(s);
        assertEquals("git-flow", s.getName());
        assertEquals(NaruVisibility.PUBLIC, s.getVisibility());
        assertEquals(NaruSkillLayout.FLAT, s.getLayout());
        assertEquals(List.of("follow git flow"), s.getLines());
        // no front-matter: the description is the first paragraph of the body
        assertEquals("follow git flow", s.getDescription());
        // origin root and base dir both point at the public root for a flat file
        assertTrue(s.getOriginRoot().endsWith(".naru/skills"), s.getOriginRoot());
        assertEquals(s.getOriginRoot(), s.getBaseDir());
        // snapshot: value carries the raw-content hash
        assertFalse(s.getContentHash().isEmpty());
    }

    @Test
    public void folderSkillCarriesTheOpenStandardFrontMatter() {
        publicFolder("pdf-reader", "name: pdf-reader\ndescription: read pdf files\nallowed-tools: \"Bash(git:*)\"\nmetadata:\n  owner: docs\n", "body line");
        discover();
        NaruSkill s = ext.skills().findSkill("pdf-reader");
        assertNotNull(s);
        assertEquals(NaruSkillLayout.FOLDER, s.getLayout());
        assertEquals("read pdf files", s.getDescription());
        assertTrue(s.getBaseDir().endsWith(".naru/skills/pdf-reader"), s.getBaseDir());
        assertTrue(s.getOriginRoot().endsWith(".naru/skills"), s.getOriginRoot());
        assertEquals(Set.of("Bash(git:*)"), s.getAllowedTools());
        assertEquals("docs", s.getFrontMatter().get("metadata") instanceof java.util.Map m ? m.get("owner") : null);
        assertEquals(List.of("body line"), s.getLines());
        assertTrue(s.getWarnings().isEmpty(), () -> "a well-formed standard skill must not warn: " + s.getWarnings());
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
    }

    @Test
    public void privateSkillShadowsPublicCompletely() {
        NaruSkill s = ext.skills().findSkill("javadoc");
        assertNotNull(s);
        assertEquals(NaruVisibility.PRIVATE, s.getVisibility());
        assertTrue(s.isShadowed(), "the private copy hides the public one, so this name must be flagged shadowed");
        assertEquals(List.of("PRIVATE javadoc rules"), s.getLines());
        assertTrue(s.getSourceName().contains(".naru/local/skills/javadoc.md"), s.getSourceName());
    }

    @Test
    public void shadowingIsPerNamePrivateWins() {
        NaruSkill git = ext.skills().findSkill("git-flow");
        assertFalse(git.isShadowed(), "only names with both copies are shadowed");
        assertTrue(ext.skills().findSkill("javadoc").isShadowed());
    }

    @Test
    public void availableListsEachNameOnceSortedWithTheWinningVisibility() {
        List<NaruSkill> all = ext.skills().available();
        List<String> names = new ArrayList<>();
        for (NaruSkill s : all) {
            names.add(s.getName());
        }
        assertEquals(List.of("git-flow", "javadoc"), names);
        NaruSkill javadoc = all.stream()
                .filter(s -> s.getName().equals("javadoc")).findFirst().orElseThrow();
        assertEquals(NaruVisibility.PRIVATE, javadoc.getVisibility());
    }

    @Test
    public void theSnapshotIsServedWithoutReReadingTheDisk() {
        NaruSkill before = ext.skills().findSkill("git-flow");
        String hash = before.getContentHash();
        publicFlat("git-flow", "CHANGED ON DISK", "but the snapshot must not see it");
        // the manager keeps serving the snapshot it read at open()
        NaruSkill after = ext.skills().findSkill("git-flow");
        assertEquals(hash, after.getContentHash());
        assertEquals(List.of("follow git flow"), after.getLines());
    }

    @Test
    public void reloadRebuildsTheSnapshotFromDisk() {
        publicFlat("draft", "first version");
        discover();
        String hash1 = ext.skills().findSkill("draft").getContentHash();
        publicFlat("draft", "second version");
        assertEquals(hash1, ext.skills().findSkill("draft").getContentHash(),
                "precondition: without a reload the snapshot is stale");

        ext.reload();

        NaruSkill now = ext.skills().findSkill("draft");
        assertFalse(hash1.equals(now.getContentHash()), "the reload must pick up the new content");
        assertEquals(List.of("second version"), now.getLines());
    }

    @Test
    public void reloadOfOneNameRefreshesJustThatSkill() {
        publicFlat("fresh", "born after the session opened");
        // the snapshot held nothing at open(); the targeted refresh finds the new file
        NaruSkill found = ext.reload("fresh");
        assertNotNull(found);
        assertEquals(List.of("born after the session opened"), found.getLines());
        assertEquals("fresh", ext.skills().findSkill("fresh").getName());
        assertNull(ext.reload("still-not-there"));
    }

    @Test
    public void frontMatterParsingIsLenientAndWarnsInsteadOfRejecting() {
        publicFolder("bad-yaml", "name: pdf-reader\nbad: 'unclosed", "still loads");
        discover();
        NaruSkill s = ext.skills().findSkill("bad-yaml");
        assertNotNull(s, "a skill with malformed front-matter must still load");
        assertFalse(s.getWarnings().isEmpty(), () -> "malformed front-matter must leave a warning: " + s.getWarnings());
        assertEquals(List.of("still loads"), s.getLines());
    }

    @Test
    public void folderSkillWithoutADescriptionWarns() {
        publicFolder("tacit", "name: tacit", "some body");
        discover();
        NaruSkill s = ext.skills().findSkill("tacit");
        assertNotNull(s);
        assertTrue(s.getWarnings().stream().anyMatch(w -> w.contains("description")),
                () -> "a folder skill missing its description must warn: " + s.getWarnings());
        assertEquals("", s.getDescription());
    }

    @Test
    public void requiresFrontMatterParsesIntoARequirement() {
        publicFolder("fs-ops", "{ name: fs-ops, description: fs things, requires: \"fs & !write\" }", "use fs like a pro");
        discover();
        NaruSkill s = ext.skills().findSkill("fs-ops");
        assertNotNull(s);
        assertNotNull(s.getRequires());
        assertEquals(Set.of("fs"), s.getRequiredTags());
        assertTrue(s.getRequires().matches(Set.of("fs")));
        assertFalse(s.getRequires().matches(Set.of("fs", "write")));
    }

    // ── selection: flat per task, ADVERTISED default ───────────────────────

    @Test
    public void nothingIsLoadedByDefaultEverythingIsAdvertised() {
        NaruTask a = task();
        assertEquals(Set.of(), ext.activeNames(a));
        assertEquals(NaruSkillState.ADVERTISED, ext.state(a, "git-flow"));
        assertTrue(ext.isRelevant(a), "the default ADVERTISED state alone is reason to contribute");
    }

    @Test
    public void loadActivatesOnlyForThatTask() {
        NaruTask a = task();
        NaruTask b = task();
        assertTrue(ext.load(a, "git-flow"));
        assertEquals(Set.of("git-flow"), ext.activeNames(a));
        assertEquals(NaruSkillState.LOADED, ext.state(a, "git-flow"));
        assertEquals(Set.of(), ext.activeNames(b));
        assertEquals(NaruSkillState.ADVERTISED, ext.state(b, "git-flow"));
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
        NaruTask a = task();
        assertTrue(ext.load(a, "Git Flow"));
        assertTrue(ext.unload(a, "Git Flow"));
        assertEquals(Set.of(), ext.activeNames(a));
    }

    @Test
    public void theSelectionIsFlatThereIsNoInheritance() {
        NaruTask parent = task();
        ext.load(parent, "git-flow");
        NaruTask child = task(parent);
        // v2: a child starts with its own flat (empty) selection; loading on the parent
        // does not reach the child, and loading on the child does not touch the parent
        assertEquals(Set.of(), ext.activeNames(child));
        assertTrue(ext.load(child, "javadoc"));
        assertEquals(Set.of("git-flow"), ext.activeNames(parent));
        assertEquals(Set.of("javadoc"), ext.activeNames(child));
    }

    @Test
    public void unloadOfSomethingNeverLoadedReportsFalse() {
        NaruTask a = task();
        assertFalse(ext.unload(a, "git-flow"));
        assertFalse(ext.unload(a, "does-not-exist"));
    }

    // ── prompt contribution: progressive disclosure plus requires gate ─────

    @Test
    public void advertisedSkillsContributeNameAndDescriptionOnly() {
        NaruTask a = task();
        List<NaruMessage> messages = ext.contribute(a);
        assertEquals(2, messages.size(), () -> "both skills are advertised: " + messages);
        NaruMessage javadoc = messages.get(1);
        assertTrue(javadoc.getContent().contains("## AVAILABLE SKILL: JAVADOC"), javadoc.getContent());
        // the private copy won, so it is its description that is advertised
        assertTrue(javadoc.getContent().contains("PRIVATE javadoc rules"), javadoc.getContent());
        assertFalse(javadoc.getContent().contains("always document public API"),
                "the shadowed public body must not leak into the advertisement");
        // and the body itself must not be injected
        assertFalse(javadoc.getContent().contains("## ACTIVE SKILL DIRECTIVE"), javadoc.getContent());
    }

    @Test
    public void loadedSkillContributesItsFullBody() {
        NaruTask a = task();
        ext.load(a, "git-flow");
        List<NaruMessage> messages = ext.contribute(a);
        assertEquals(2, messages.size(), () -> "one active, one advertised: " + messages);
        NaruMessage active = messages.get(0);
        assertTrue(active.getContent().contains("## ACTIVE SKILL DIRECTIVE: GIT-FLOW"), active.getContent());
        assertTrue(active.getContent().contains("follow git flow"), active.getContent());
        assertTrue(active.getSourceName().contains(".naru/skills/git-flow.md"), active.getSourceName());
    }

    @Test
    public void loadedSkillWithAnUnmetRequirementIsGatedNotInjected() {
        publicFolder("guarded", "name: guarded\ndescription: only for non-writers\nrequires: \"!write\"", "secret body");
        discover();
        NaruTask a = task();
        a.addToolTag("write");
        ext.load(a, "guarded");
        List<NaruMessage> messages = ext.contribute(a);
        assertTrue(messages.stream().anyMatch(m -> m.getContent().contains("## SKILL REQUIRES GATE (UNSATISFIED): GUARDED")),
                () -> "the gate note must appear: " + messages);
        assertTrue(messages.stream().noneMatch(m -> m.getContent().contains("ACTIVE SKILL DIRECTIVE: GUARDED")),
                "a gated skill body must not be injected");
    }

    @Test
    public void loadedSkillWithAnUnregisteredRequirementIsUnsatifiable() {
        publicFolder("ghost", "name: ghost\ndescription: needs a tag nobody provides\nrequires: \"fs\"", "body");
        discover();
        NaruTask a = task();
        ext.load(a, "ghost");
        // 'fs' has no provider in this test classpath; the gate must say so explicitly
        assertEquals(NaruRequiresStatus.UNSATISFIABLE, ext.requiresStatus(ext.skills().findSkill("ghost"), a));
        List<NaruMessage> messages = ext.contribute(a);
        assertTrue(messages.stream().anyMatch(m -> m.getContent().contains("## SKILL REQUIRES GATE (UNSATISFIABLE): GHOST")),
                () -> "an unsatisfiable skill must be reported separately: " + messages);
    }

    @Test
    public void loadedSkillCanMoveThroughTheGateWhenTagsChange() {
        publicFolder("guarded", "name: guarded\ndescription: write-only\nrequires: \"write\"", "body");
        discover();
        NaruTask a = task();
        a.addToolTag("write");
        ext.load(a, "guarded");
        assertTrue(contributeContainsActive(ext, a, "guarded"), "write held → body injected");

        // revoking the tag while the skill stays loaded: still LOADED, now gated
        a.removeToolTag("write");
        assertEquals(NaruSkillState.LOADED, ext.state(a, "guarded"));
        assertEquals(NaruRequiresStatus.UNSATISFIED, ext.requiresStatus(ext.skills().findSkill("guarded"), a));
        assertFalse(contributeContainsActive(ext, a, "guarded"),
                "the body must be withheld while the requires gate is closed");
        assertTrue(ext.contribute(a).stream().anyMatch(m -> m.getContent().contains("SKILL REQUIRES GATE")),
                "withholding must be flagged, not silent");
    }

    @Test
    public void loadedSkillThatDisappearsFromDiskIsFlaggedNotSilentlyDropped() {
        NaruTask a = task();
        ext.load(a, "git-flow");
        publicFlat("filler", "so the extension stays relevant", "");
        projectDir.resolve(".naru/skills/git-flow.md").delete();
        ext.reload();
        List<NaruMessage> messages = ext.contribute(a);
        assertTrue(messages.stream().anyMatch(m -> m.getContent().contains("## SKILL MISSING: GIT-FLOW")),
                () -> "a selected skill that is gone must be flagged: " + messages);
    }

    private static boolean contributeContainsActive(NaruSkillsExtension ext, NaruTask task, String name) {
        return ext.contribute(task).stream()
                .anyMatch(m -> m.getContent().contains("## ACTIVE SKILL DIRECTIVE: " + name.toUpperCase()));
    }

    @Test
    public void extensionDeclaresTheSkillSource() {
        assertEquals(Set.of(NaruSource.SKILL), ext.sources());
        assertEquals(NaruSource.SKILL, ext.source());
        assertEquals("skills", ext.name());
    }

    // ── persistence: v2 flat round trip ────────────────────────────────────

    @Test
    public void selectionSurvivesASaveLoadRoundTrip() throws Exception {
        NaruTask a = task();
        ext.load(a, "git-flow");
        ext.load(a, "javadoc");

        NElement saved = ext.save(session);
        assertNotNull(saved);
        assertEquals(2, saved.asObject().get().getIntValue("schemaVersion").orElse(-1),
                "the state must be written at schemaVersion 2");

        ext.unload(a, "git-flow");
        ext.unload(a, "javadoc");
        assertEquals(Set.of(), ext.activeNames(a));

        ext.load(session, saved);
        assertEquals(Set.of("git-flow", "javadoc"), ext.activeNames(a));
    }

    @Test
    public void flatSelectionSurvivesASaveLoadRoundTripWithoutInheritance() throws Exception {
        NaruTask parent = task();
        ext.load(parent, "git-flow");
        NaruTask child = task(parent);
        NElement saved = ext.save(session);

        ext.load(session, saved);
        // the child never had its own entry: the flat model restores exactly what was flat
        assertEquals(Set.of("git-flow"), ext.activeNames(parent));
        assertEquals(Set.of(), ext.activeNames(child));
    }

    @Test
    public void loadingNoStateLeavesTheExtensionAtItsInitialState() {
        NaruTask a = task();
        ext.load(a, "git-flow");
        ext.load(session, null);
        assertEquals(Set.of(), ext.activeNames(a));
    }

    @Test
    public void savedStateIsReadableAsTson() throws Exception {
        NaruTask a = task();
        ext.load(a, "git-flow");
        NPath file = projectDir.resolve("ext/skills.tson");
        NElementWriter.ofTson().ntf(false).formatter(NElementFormatterStyle.PRETTY)
                .write(ext.save(session), file);
        String tson = new String(java.nio.file.Files.readAllBytes(file.toPath().orElseThrow(() -> new IllegalStateException("no path: " + file))));
        assertTrue(tson.contains("git-flow"), tson);
        assertTrue(tson.contains("schemaVersion"), tson);
        assertTrue(tson.contains("2"), tson);
    }

    @Test
    public void unknownSchemaVersionIsRejectedLoudly() throws Exception {
        NElement bogus = NElement.ofObjectBuilder()
                .set("schemaVersion", 99)
                .set("selection", NArrayElementBuilder.of().build())
                .build();
        try {
            ext.load(session, bogus);
            throw new AssertionError("an unsupported schema version must not load silently");
        } catch (net.thevpc.nuts.util.NIllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("99"), expected.getMessage());
        }
    }

    /**
     * The core tells extensions when a task leaves the session; an extension that keeps
     * per-task state drops that task's entry. Without this, {@code ext/skills.tson} grows
     * by one dead entry per task ever run.
     */
    @Test
    public void terminatingATaskRemovesItsSelectionFromExtensionState() {
        NaruTask keep = task();
        NaruTask victim = task();
        ext.load(keep, "git-flow");
        ext.load(victim, "javadoc");
        assertTrue(hasSelectionFor(ext.save(session), victim.id()), "precondition: victim has persisted selection");

        victim.kill();

        NElement saved = ext.save(session);
        assertFalse(hasSelectionFor(saved, victim.id()),
                () -> "a deregistered task still has persisted selection: " + saved);
        assertTrue(hasSelectionFor(saved, keep.id()),
                () -> "a live task's selection was dropped too: " + saved);
    }

    private static boolean hasSelectionFor(NElement state, long taskId) {
        if (state == null) {
            return false;
        }
        return state.asObject().get().getArray("selection")
                .map(a -> a.children().stream()
                        .anyMatch(c -> c.asObject()
                                .map(o -> o.getLongValue("id").orElse(-1L) == taskId)
                                .orElse(false)))
                .orElse(false);
    }

    // ── migration: schemaVersion 1 → 2 ─────────────────────────────────────

    @Test
    public void v1FlattensLoadedAndMaskedIntoOwnSelection() throws Exception {
        NaruTask parent = task();
        NaruTask child = task(parent);
        NElement v1 = NElement.ofObjectBuilder()
                .set("schemaVersion", 1)
                .set("selection", NArrayElementBuilder.of()
                        .add(NElement.ofObjectBuilder()
                                .set("id", parent.id())
                                .set("loaded", NArrayElementBuilder.of().add("git-flow").build())
                                .build())
                        .add(NElement.ofObjectBuilder()
                                .set("id", child.id())
                                .set("loaded", NArrayElementBuilder.of().add("javadoc").build())
                                .set("masked", NArrayElementBuilder.of().add("git-flow").build())
                                .build())
                        .build())
                .build();

        ext.load(session, v1);

        // the child used to mask the parent's git-flow: the effective set is what v2 stores
        assertEquals(Set.of("git-flow"), ext.activeNames(parent));
        assertEquals(Set.of("javadoc"), ext.activeNames(child));

        // and the migrated state re-saves in v2 shape
        NElement saved = ext.save(session);
        assertEquals(2, saved.asObject().get().getIntValue("schemaVersion").orElse(-1));
        assertFalse(saved.toString().contains("masked"));
    }

    @Test
    public void v1MaterializesInheritedOnlyChildrenSoNothingIsDropped() throws Exception {
        NaruTask parent = task();
        NaruTask child = task(parent);
        NaruTask grandchild = task(child);
        NElement v1 = NElement.ofObjectBuilder()
                .set("schemaVersion", 1)
                .set("selection", NArrayElementBuilder.of()
                        .add(NElement.ofObjectBuilder()
                                .set("id", parent.id())
                                .set("loaded", NArrayElementBuilder.of().add("git-flow").build())
                                .build())
                        .build())
                .build();

        ext.load(session, v1);

        // under the old walk both children inherited git-flow; dropping that would make
        // them fall back to ADVERTISED on upgrade, so the migration materializes them
        assertEquals(Set.of("git-flow"), ext.activeNames(parent));
        assertEquals(Set.of("git-flow"), ext.activeNames(child));
        assertEquals(Set.of("git-flow"), ext.activeNames(grandchild));
    }

    @Test
    public void v1MaskOnlyStillBlocksTheAncestorsChoice() throws Exception {
        NaruTask parent = task();
        NaruTask child = task(parent);
        NElement v1 = NElement.ofObjectBuilder()
                .set("schemaVersion", 1)
                .set("selection", NArrayElementBuilder.of()
                        .add(NElement.ofObjectBuilder()
                                .set("id", parent.id())
                                .set("loaded", NArrayElementBuilder.of().add("git-flow").build())
                                .build())
                        .add(NElement.ofObjectBuilder()
                                .set("id", child.id())
                                .set("masked", NArrayElementBuilder.of().add("git-flow").build())
                                .build())
                        .build())
                .build();

        ext.load(session, v1);

        assertEquals(Set.of("git-flow"), ext.activeNames(parent));
        assertEquals(Set.of(), ext.activeNames(child),
                "a mask that blocked the ancestor choice must keep blocking after migration");
    }

    // ── the extension is discoverable, and optional ─────────────────────────

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