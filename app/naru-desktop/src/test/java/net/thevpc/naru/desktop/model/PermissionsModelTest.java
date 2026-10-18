package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.access.NaruAccessDecision;
import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruActorKind;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruPermissionStore;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.access.NaruSubject;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.naru.impl.access.NaruAccessPolicyImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Permissions tab's model, headless, against the real policy implementation rather than a stub:
 * the whole point of this panel is that it edits the object the engine consults, so a fake would
 * prove the wiring and nothing else.
 * <p>
 * The three things the plan asks of it (§3.3, §5, §6) are that a change is in force on the next
 * check, that order is editable because first-match-wins makes it the behaviour, and that the
 * project file is only touched when asked.
 */
class PermissionsModelTest {

    private static final NaruActor USER = NaruActor.user("alice");
    private static final NaruActor AGENT = NaruActor.agent("worker",
            net.thevpc.naru.api.access.NaruPrincipal.user("alice"));

    private PermissionsModel model;
    private NaruAccessPolicyImpl policy;

    @BeforeAll
    static void requireNuts() {
        // NPath.of resolves through a Nuts workspace, and these tests build real temp paths
        Nuts.require();
    }

    @BeforeEach
    void strict() {
        EDT.setStrict(true);
        model = new PermissionsModel();
        policy = new NaruAccessPolicyImpl();
    }

    @AfterEach
    void relaxed() throws Exception {
        // detach is an EDT operation — the window closes on the EDT — so the cleanup follows the
        // same rule as the code under test
        onEdt(model::detach);
        EDT.setStrict(false);
    }

    private static void onEdt(ThrowingRunnable body) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static NaruResource file(String locator) {
        return NaruResource.of(NaruResourceType.FILE, locator);
    }

    private static NaruPermissionRule denyAgentsWrite() {
        return new NaruPermissionRule().id("deny-agent-write")
                .resourceType(NaruResourceType.FILE).patterns("/proj/src/**")
                .actions(NaruAction.WRITE).actorKind(NaruActorKind.AGENT)
                .effect(NaruAccessEffect.DENY).source(PermissionsModel.SOURCE_SESSION);
    }

    private static NaruPermissionRule allowDevs() {
        return new NaruPermissionRule().id("devs-write")
                .resourceType(NaruResourceType.FILE).patterns("/proj/**")
                .actions(NaruAction.WRITE).subjects(NaruSubject.group("devs"))
                .effect(NaruAccessEffect.ALLOW).source(PermissionsModel.SOURCE_SESSION);
    }

    @Test
    void attachProjectsThePolicyAndEditsLandOnIt() throws Exception {
        onEdt(() -> {
            model.attach(policy);
            assertTrue(model.isAttached());
            assertEquals(0, model.rows().size());
            assertEquals(NaruAccessEffect.ALLOW, model.defaultEffect());

            model.putRule(denyAgentsWrite());
            model.putRule(allowDevs());
        });
        // the engine's own view, not the model's — this is what "in force now, not on restart" means
        assertEquals(2, policy.rules().size());
        onEdt(() -> {
            assertFalse(policy.isAllowed(file("/proj/src/A.java"), NaruAction.WRITE, AGENT),
                    "the deny the panel added is the one the policy applies");
            assertTrue(model.row("deny-agent-write").rule().enabled());

            model.setEnabled("deny-agent-write", false);
            model.setDefaultEffect(NaruAccessEffect.DENY);
        });
        assertEquals(NaruAccessEffect.DENY, policy.defaultEffect());
        onEdt(() -> assertEquals(1, model.row("deny-agent-write").hits(),
                "the one check made before the rule was switched off is the only hit it has"));
        NaruAccessDecision off = policy.check(NaruResource.of(NaruResourceType.URL, "http://x"),
                NaruAction.READ, USER);
        assertFalse(off.allowed(), "the fallback flip reached the policy too");
    }

    @Test
    void moveChangesWhichRuleFires() throws Exception {
        onEdt(() -> {
            model.attach(policy);
            // allow first: an agent in the devs group is allowed because the allow matches before the deny
            model.putRule(allowDevs());
            model.putRule(denyAgentsWrite());
        });
        NaruActor devAgent = NaruActor.agent("w", net.thevpc.naru.api.access.NaruPrincipal.user("alice")
                .withGroup("devs"));
        assertTrue(policy.isAllowed(file("/proj/src/A.java"), NaruAction.WRITE, devAgent));
        onEdt(() -> {
            model.move("deny-agent-write", -1);
            assertEquals("deny-agent-write", model.rows().get(0).id(),
                    "the row order the panel shows is the evaluation order");
        });
        assertFalse(policy.isAllowed(file("/proj/src/A.java"), NaruAction.WRITE, devAgent),
                "moving a rule is the edit that changes behaviour, so it must be the visible one");
    }

    @Test
    void decisionsAreCountedAndReachTheRows() throws Exception {
        onEdt(() -> {
            model.attach(policy);
            model.putRule(denyAgentsWrite());
        });
        // a check from a worker thread must not touch EDT state, so the model counts and waits
        for (int i = 0; i < 3; i++) {
            policy.check(NaruResource.of(NaruResourceType.FILE, "/proj/src/A.java"), NaruAction.WRITE, AGENT);
        }
        policy.check(NaruResource.of(NaruResourceType.FILE, "/proj/other.txt"), NaruAction.READ, USER);
        assertTrue(model.countsDirty(), "the poll uses this to repaint only when there is a new number");
        onEdt(model::refresh);
        PermissionsModel.Row denied = model.row("deny-agent-write");
        assertEquals(3, denied.hits());
        assertEquals(0, denied.grants());
        assertEquals(3, denied.blocks());
        assertFalse(model.countsDirty());
        assertEquals(4, model.totalDecisions(), "the allowed check counted too, through the fallback");
    }

    @Test
    void theCommittedFileIsOnlyTouchedWhenAsked() throws Exception {
        FakeStore store = new FakeStore(policy);
        store.file.add(denyAgentsWrite().source(PermissionsModel.SOURCE_PROJECT));
        onEdt(() -> {
            model.attach(policy, store);
            model.putRule(denyAgentsWrite().source(PermissionsModel.SOURCE_PROJECT));
            model.putRule(allowDevs());
        });
        assertEquals(0, store.writes, "editing the live policy must not write a committed file");
        onEdt(() -> assertTrue(model.saveToProject().contains("written")));
        assertEquals(1, store.writes);
        // a session rule is runtime state and never belongs in the project file
        assertEquals(1, store.file.size());
        assertEquals("deny-agent-write", store.file.get(0).id());

        // reload replaces exactly the project rules, leaving the session ones alone
        onEdt(() -> {
            model.removeRule("deny-agent-write");
            assertEquals(1, model.rows().size());
            assertTrue(model.reloadFromProject().contains("reloaded"));
        });
        assertEquals(2, policy.rules().size());
        assertEquals(PermissionsModel.SOURCE_PROJECT, policy.rule("deny-agent-write").orNull().source());
    }

    @Test
    void reloadOfAnAbsentFileClearsProjectRulesOnly() throws Exception {
        FakeStore store = new FakeStore(policy);
        onEdt(() -> {
            model.attach(policy, store);
            model.putRule(allowDevs());
            model.putRule(denyAgentsWrite().source(PermissionsModel.SOURCE_PROJECT));
            assertNotNull(model.reloadFromProject());
        });
        assertEquals(1, policy.rules().size());
        assertEquals("devs-write", policy.rules().get(0).id());
    }

    /**
     * The fallback the interface exists to make honest. A profile without {@code naru-permissions}
     * has no store, and the model must say so — the live rules are still the engine's and still
     * editable — rather than write a policy file with a parser of its own.
     */
    @Test
    void withoutTheExtensionTheFileIsNamedUnavailableAndNotWritten() throws Exception {
        onEdt(() -> {
            model.attach(policy);
            assertNull(model.projectStore());
            assertNull(model.projectFile());
            model.putRule(denyAgentsWrite().source(PermissionsModel.SOURCE_PROJECT));
            String save = model.saveToProject();
            assertTrue(save.contains("naru-permissions"), save);
            assertTrue(save.contains("will not write"), save);
            String reload = model.reloadFromProject();
            assertTrue(reload.contains("naru-permissions"), reload);
            assertEquals(1, model.rows().size(), "the live policy is still projected and editable");
        });
    }

    @Test
    void detachedModelShowsNothingAndRefusesEdits() throws Exception {
        onEdt(() -> {
            model.attach(policy, new FakeStore(policy));
            model.putRule(allowDevs());
            assertEquals(1, model.rows().size());
            model.detach();
            assertFalse(model.isAttached());
            assertEquals(0, model.rows().size());
            assertNull(model.row("devs-write"));
            // an edit after detach targets nothing; it must not throw and must not resurrect rows
            model.setDefaultEffect(NaruAccessEffect.DENY);
            model.move("devs-write", -1);
            model.setEnabled("devs-write", false);
            assertEquals(NaruAccessEffect.ALLOW, policy.defaultEffect());
            assertEquals(1, policy.rules().size());
            assertEquals("no session", model.saveToProject());
            assertNull(model.projectStore(), "detach must drop the store as well as the policy");
        });
    }

    /**
     * A stand-in for the extension's store: the same interface the desktop binds to through
     * {@code session.registry().extension("permissions", NaruPermissionStore.class)}, with the
     * "file" held in memory. The format itself is tested where it is implemented
     * ({@code NaruPermissionsRuleStoreTest} in {@code extensions/naru-permissions}); what this
     * module owns is that it asks, counts and tags correctly — and that it asks at all only when the
     * user pressed the button.
     */
    private static final class FakeStore implements NaruPermissionStore {
        final List<NaruPermissionRule> file = new ArrayList<>();
        final NaruAccessPolicy policy;
        NaruAccessEffect defaultEffect = NaruAccessEffect.ALLOW;
        int writes;
        int reloads;

        FakeStore(NaruAccessPolicy policy) {
            this.policy = policy;
        }

        @Override
        public String name() {
            return EXTENSION;
        }

        @Override
        public NPath projectFile() {
            return NPath.of("/tmp/naru-fake-project/" + PROJECT_FILE);
        }

        @Override
        public List<NaruPermissionRule> readProjectRules() {
            return List.copyOf(file);
        }

        @Override
        public NaruAccessEffect readProjectDefaultEffect(NaruAccessEffect fallback) {
            return defaultEffect == null ? fallback : defaultEffect;
        }

        @Override
        public String reloadProjectRules() {
            reloads++;
            policy.removeRules(SOURCE_PROJECT);
            for (NaruPermissionRule r : file) {
                policy.putRule(r.source(SOURCE_PROJECT));
            }
            return "project rules reloaded";
        }

        @Override
        public String writeProjectRules(List<NaruPermissionRule> rules, NaruAccessEffect effect) {
            writes++;
            file.clear();
            file.addAll(rules);
            defaultEffect = effect == null ? NaruAccessEffect.ALLOW : effect;
            return null;
        }
    }
}
