package net.thevpc.naru.agent.spawn;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.spawn.NaruSpawnContract;
import net.thevpc.naru.api.spawn.NaruSpawnInherit;
import net.thevpc.naru.api.spawn.NaruSpawnPolicy;
import net.thevpc.naru.api.spawn.NaruSpawnResolution;
import net.thevpc.naru.api.spawn.NaruSpawnSeed;
import net.thevpc.naru.api.spawn.NaruSpawnSource;
import net.thevpc.naru.api.spawn.NaruSpawnStrategy;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.engine.scheduler.NaruTaskImpl;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The WP3 spawn resolver, driven through the core session API: strategy (none/fork/window/
 * summary), the inherit kinds (tags/env snapshots), the add/revoke combination rules
 * (add-wins-over-revoke), named policies, contract validation with a fix hint, and the
 * {@code TaskSpawned} event carrying provenance only — never a grant channel.
 *
 * <p>No scheduler, no model, no network: tasks are created held and the assertions read the
 * resolved sets off the child task and off {@code resolveSpawn}, so an unheld child cannot
 * drift the outcome.
 */
@Timeout(60)
public class NaruSpawnTest {

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

    private NaruAgent agent;
    private NaruSessionImpl session;

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
        agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-spawn"));
        session = new NaruSessionImpl(agent, agent.projectDirectory(), null, true,
                NOOP_LISTENER, null, null, null);
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

    // ── helpers ─────────────────────────────────────────────────────────────

    private NaruTask newTask() {
        return session.newTask(NaruTaskSpec.of());
    }

    private NaruTask spawn(NaruTask parent) {
        return session.newTask(NaruTaskSpec.of().parentId(parent.id()));
    }

    private NaruTask spawn(NaruTask parent, NaruTaskSpec spec) {
        return session.newTask(spec.parentId(parent.id()));
    }

    private NaruSpawnResolution resolve(NaruTask parent, NaruTaskSpec spec) {
        return session.resolveSpawn(spec.parentId(parent.id()));
    }

    private Set<String> names(List<NaruSpawnSeed<String>> items) {
        return items.stream().map(NaruSpawnSeed::value).collect(Collectors.toSet());
    }

    // ── context strategy ────────────────────────────────────────────────────

    @Test
    public void plainSpawnIsNONEAndGrantsNothing() {
        NaruTask parent = newTask();
        parent.addToolTag("fs").addToolTag("exec");
        parent.setTaskEnv("k", "v");

        NaruTask child = spawn(parent);
        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of());

        assertEquals(NaruSpawnStrategy.NONE, r.strategy());
        assertEquals(NaruSpawnSource.DEFAULT, r.strategySource());
        assertTrue(r.inherited().isEmpty(), () -> "a bare spawn inherits nothing: " + r.inherited());
        assertTrue(r.tagNames().isEmpty());
        assertTrue(child.findToolTagNames().isEmpty(),
                () -> "a child of a tagged parent starts untagged: " + child.findToolTagNames());
    }

    @Test
    public void forkImpliesTagInheritanceByDefault() {
        NaruTask parent = newTask();
        parent.addToolTag("fs").addToolTag("exec");

        NaruTask child = spawn(parent, NaruTaskSpec.of().strategy(NaruSpawnStrategy.FORK));
        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of().strategy(NaruSpawnStrategy.FORK));

        assertEquals(NaruSpawnStrategy.FORK, r.strategy());
        assertEquals(NaruSpawnSource.FLAG, r.strategySource());
        assertEquals(Map.of(NaruSpawnInherit.TAGS, NaruSpawnSource.DEFAULT), r.inherited(),
                () -> "a fork snapshots the parent tags by default (source default): " + r.inherited());
        assertEquals(Set.of("fs", "exec"), child.findToolTagNames());
        assertEquals(Set.of("fs", "exec"), r.tagNames());
    }

    @Test
    public void windowImpliesTagInheritanceByDefault() {
        NaruTask parent = newTask();
        parent.addToolTag("fs");

        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of()
                .strategy(NaruSpawnStrategy.WINDOW).windowTurns(3));

        assertEquals(NaruSpawnStrategy.WINDOW, r.strategy());
        assertEquals(3, r.windowTurns());
        assertEquals(Map.of(NaruSpawnInherit.TAGS, NaruSpawnSource.DEFAULT), r.inherited());
    }

    @Test
    public void explicitInheritEnvDoesNotSilentlyCancelAForkSTags() {
        NaruTask parent = newTask();
        parent.addToolTag("fs");

        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of()
                .strategy(NaruSpawnStrategy.FORK).inherit(NaruSpawnInherit.ENV));

        assertEquals(Set.of(NaruSpawnInherit.TAGS, NaruSpawnInherit.ENV), r.inherited().keySet(),
                () -> "enumerating env must not cancel the fork's implied tag snapshot: " + r.inherited());
        assertEquals(Set.of("fs"), r.tagNames());
    }

    @Test
    public void contextStrategyOnARootTaskWarnsButDoesNotFail() {
        NaruSpawnResolution r = session.resolveSpawn(NaruTaskSpec.of().strategy(NaruSpawnStrategy.FORK));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("no parent to inherit conversation from")),
                () -> "expected the no-parent warning: " + r.warnings());
    }

    // ── inherit kinds ───────────────────────────────────────────────────────

    @Test
    public void inheritTagsCopiesTheTagSet() {
        NaruTask parent = newTask();
        parent.addToolTag("write").addToolTag("exec");

        NaruTask child = spawn(parent, NaruTaskSpec.of().inherit(NaruSpawnInherit.TAGS));
        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of().inherit(NaruSpawnInherit.TAGS));

        assertEquals(Map.of(NaruSpawnInherit.TAGS, NaruSpawnSource.FLAG), r.inherited());
        assertEquals(Set.of("write", "exec"), child.findToolTagNames());
    }

    @Test
    public void inheritIsASnapshotLaterParentChangesNeverReachTheChild() {
        NaruTask parent = newTask();
        parent.addToolTag("write");

        NaruTask child = spawn(parent, NaruTaskSpec.of().inherit(NaruSpawnInherit.TAGS));
        assertEquals(Set.of("write"), child.findToolTagNames());

        parent.addToolTag("exec");
        assertEquals(Set.of("write"), child.findToolTagNames(),
                "a later parent tag must never reach an already spawned child");

        NaruTask sibling = spawn(parent, NaruTaskSpec.of().inherit(NaruSpawnInherit.TAGS));
        assertEquals(Set.of("write", "exec"), sibling.findToolTagNames(),
                "a sibling spawned after the change must see it");
    }

    @Test
    public void inheritEnvIsASnapshotOfTheParentEnv() {
        NaruTask parent = newTask();
        parent.setTaskEnv("k", "1");

        NaruTask child = spawn(parent, NaruTaskSpec.of().inherit(NaruSpawnInherit.ENV));
        assertEquals("1", child.getTaskEnv().get("k"));

        parent.setTaskEnv("k", "2");
        assertEquals("1", child.getTaskEnv().get("k"),
                "an env change on the parent must not reach a child that snapshotted the env");
    }

    @Test
    public void inheritedUnknownTagNamesSurvive() {
        // an unknown name has no NaruToolTag definition, so findToolTags() would drop it;
        // inheritance must copy the raw granted-name set or the unknown tag silently re-grants.
        // the parent gets the unknown name through the lenient spawn seeding path, which is
        // the only one that accepts names without a registered provider
        NaruTask parent = newTask();
        ((NaruTaskImpl) parent)._seedToolTagLenient("ghost-tag");

        NaruTask child = spawn(parent, NaruTaskSpec.of().inherit(NaruSpawnInherit.TAGS));
        assertTrue(child.findToolTagNames().contains("ghost-tag"),
                () -> "inherited unknown tag name was dropped: " + child.findToolTagNames());
    }

    // ── add / revoke combinations ───────────────────────────────────────────

    @Test
    public void addTagsWithoutInheritGrantsExactlyTheAdds() {
        NaruTask parent = newTask();
        parent.addToolTag("fs");

        NaruTask child = spawn(parent, NaruTaskSpec.of().addTags("exec", "write"));
        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of().addTags("exec", "write"));

        assertEquals(Set.of("exec", "write"), child.findToolTagNames(),
                "the parent's fs must not leak into a spawn that adds rather than inherits");
        assertEquals(Set.of("exec", "write"), r.tagNames());
        for (NaruSpawnSeed<String> t : r.tags()) {
            assertEquals(NaruSpawnSource.FLAG, t.source());
        }
    }

    @Test
    public void addWinsOverRevokeAtEqualPrecedence() {
        NaruTask parent = newTask();

        NaruTask child = spawn(parent, NaruTaskSpec.of().addTags("write").revokeTags("write"));
        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of().addTags("write").revokeTags("write"));

        assertEquals(Set.of("write"), child.findToolTagNames(),
                "an add of a tag at call-site precedence wins over its call-site revoke");
        assertEquals(Set.of("write"), r.tagNames());
        assertTrue(r.revokedTags().isEmpty());
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("revoke-without-hold")),
                () -> "an add that wins over its own revoke must not warn: " + r.warnings());
    }

    @Test
    public void revokeOfAnInheritedTagRemovesIt() {
        NaruTask parent = newTask();
        parent.addToolTag("write").addToolTag("exec");

        NaruTask child = spawn(parent, NaruTaskSpec.of().inherit(NaruSpawnInherit.TAGS).revokeTags("exec"));
        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of().inherit(NaruSpawnInherit.TAGS).revokeTags("exec"));

        assertEquals(Set.of("write"), child.findToolTagNames());
        assertEquals(Set.of("exec"), names(r.revokedTags()));
        assertTrue(r.warnings().isEmpty(), () -> "a held-and-revoked tag must not warn: " + r.warnings());
    }

    @Test
    public void revokeWithoutHoldWarns() {
        NaruTask parent = newTask();

        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of().revokeTags("write"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("revoke-without-hold") && w.contains("write")),
                () -> "expected the revoke-without-hold warning: " + r.warnings());
        assertTrue(r.tagNames().isEmpty());
    }

    @Test
    public void flagRevokeBeatsPolicyAddForTheSameTag() {
        NaruTask parent = newTask();
        session.defineSpawnPolicy(new NaruSpawnPolicy("p").addTags("shared"));

        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of().policy("p").revokeTags("shared"));
        assertTrue(r.tagNames().isEmpty(),
                () -> "a call-site revoke must beat a policy add for the same tag: " + r.tagNames());
        assertEquals(Set.of("shared"), names(r.revokedTags()));
    }

    @Test
    public void flagAddBeatsPolicyRevokeForTheSameTag() {
        NaruTask parent = newTask();
        session.defineSpawnPolicy(new NaruSpawnPolicy("p").revokeTags("shared"));

        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of().policy("p").addTags("shared"));
        assertEquals(Set.of("shared"), r.tagNames(),
                "a call-site add must beat a policy revoke for the same tag (add wins over revoke on top)");
        assertTrue(r.revokedTags().isEmpty());
    }

    @Test
    public void policyAppliesBeforeTheFlagsAndAfterTheDefaults() {
        NaruTask parent = newTask();
        parent.addToolTag("write");
        session.defineSpawnPolicy(new NaruSpawnPolicy("review-safe")
                .inherit(NaruSpawnInherit.TAGS)
                .revokeTags("write")
                .addTags("exec")
                .excludeTools("run_shell")
                .addSkills("code-review"));

        NaruSpawnResolution r = resolve(parent, NaruTaskSpec.of().policy("review-safe"));

        assertEquals("review-safe", r.policyName());
        assertEquals(Set.of("exec"), r.tagNames(), () -> "policy inherit+revoke broke: " + r.tagNames());
        assertEquals(Set.of("run_shell"), names(r.exclusions()));
        assertEquals(Set.of("code-review"), names(r.skills()));
        for (NaruSpawnSeed<String> s : r.exclusions()) {
            assertEquals(NaruSpawnSource.POLICY, s.source());
        }
        for (NaruSpawnSeed<String> s : r.skills()) {
            assertEquals(NaruSpawnSource.POLICY, s.source());
        }
    }

    @Test
    public void unknownPolicyThrowsWithADefineHint() {
        NaruTask parent = newTask();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> resolve(parent, NaruTaskSpec.of().policy("no-such-policy")));
        assertTrue(e.getMessage().contains("/spawn-policy no-such-policy"),
                () -> "the error must hint at defining the policy: " + e.getMessage());
    }

    // ── contract validation ─────────────────────────────────────────────────

    @Test
    public void unsatisfiedContractFailsAndNamesTheFixingFlag() {
        // the parent holds write, which the contract requires it NOT to hold. The contract
        // grants nothing itself, so the spawn must inherit the parent tags to bring write in —
        // then there is both a thing to grant (fs) and a thing to revoke (write)
        NaruTask parent = newTask();
        parent.addToolTag("write");
        NaruSpawnContract contract = NaruSpawnContract.parse("{ requires: \"fs & !write\", skills: [] }");
        assertNotNull(contract);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> resolve(parent, NaruTaskSpec.of().inherit(NaruSpawnInherit.TAGS).contract(contract)));
        assertTrue(e.getMessage().contains("--add-tags=fs"),
                () -> "the failure must hint at granting the missing tag: " + e.getMessage());
        assertTrue(e.getMessage().contains("--revoke-tags=write"),
                () -> "the failure must hint at revoking the conflicting tag: " + e.getMessage());
    }

    @Test
    public void satisfiedContractPasses() {
        NaruTask parent = newTask();
        parent.addToolTag("fs");
        NaruSpawnContract contract = NaruSpawnContract.parse("{ requires: \"fs\" }");

        // a contract constrains, it does not grant: the tags must still be inherited or added
        NaruTask child = spawn(parent, NaruTaskSpec.of().inherit(NaruSpawnInherit.TAGS).contract(contract));
        assertEquals(Set.of("fs"), child.findToolTagNames());
    }

    @Test
    public void contractMayNotAddTags() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> NaruSpawnContract.parse("{ requires: \"fs\", tags: [\"fs\"] }"));
        assertTrue(e.getMessage().contains("tags"),
                () -> "a contract that adds tags must be rejected at parse time: " + e.getMessage());
    }

    @Test
    public void contractToolsRoundTripThroughParse() {
        NaruSpawnContract contract = NaruSpawnContract.parse("{ tools: [\"file_read\", \"git_status\"] }");
        assertNotNull(contract);
        assertEquals(List.of("file_read", "git_status"), contract.tools());
    }

    @Test
    public void contractToolThatTheResolutionExcludesFailsTheSpawn() {
        NaruTask parent = newTask();
        parent.addToolTag("fs");
        NaruSpawnContract contract = NaruSpawnContract.parse("{ tools: [\"run_shell\"] }");
        assertNotNull(contract);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> resolve(parent, NaruTaskSpec.of()
                        .inherit(NaruSpawnInherit.TAGS)
                        .excludeTools("run_shell")
                        .contract(contract)));
        assertTrue(e.getMessage().contains("run_shell"),
                () -> "the failure must name the tool the resolution excludes: " + e.getMessage());
    }

    @Test
    public void contractToolThatSurvivesTheResolutionPasses() {
        NaruTask parent = newTask();
        parent.addToolTag("fs");
        NaruSpawnContract contract = NaruSpawnContract.parse("{ tools: [\"file_read\"] }");
        assertNotNull(contract);

        // naming a tool is a constraint, not a grant: the spawn succeeds and grants nothing
        NaruTask child = spawn(parent, NaruTaskSpec.of()
                .inherit(NaruSpawnInherit.TAGS)
                .contract(contract));
        assertEquals(Set.of("fs"), child.findToolTagNames());
    }

    // ── the TaskSpawned event ───────────────────────────────────────────────

    @Test
    public void spawnEventCarriesProvenanceButNoGrantChannel() {
        NaruTask parent = newTask();
        session.defineSpawnPolicy(new NaruSpawnPolicy("review-safe").addSkills("code-review"));
        parent.addToolTag("fs").addToolTag("exec");

        NaruTask child = spawn(parent, NaruTaskSpec.of()
                .strategy(NaruSpawnStrategy.FORK)
                .revokeTags("exec")
                .excludeTools("run_shell")
                .addSkills("code-review")
                .policy("review-safe"));

        NaruEvent spawned = session.eventLog().scan(0, e -> NaruEvent.TASK_SPAWNED.equals(e.name()))
                .stream().reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no TaskSpawned event"));
        Map<String, Object> payload = spawned.payload();

        assertEquals(child.id(), payload.get("child"));
        assertEquals(parent.id(), payload.get("parent"));
        assertEquals("task", payload.get("kind"));
        assertEquals("fork (flag)", payload.get("strategy"));
        assertTrue(payload.get("tags").toString().contains("fs"),
                () -> "the resolved tags must be visible in the provenance: " + payload);
        assertTrue(payload.get("skills").toString().contains("code-review"), payload.toString());
        assertTrue(payload.get("exclusions").toString().contains("run_shell"), payload.toString());
        assertEquals("review-safe", payload.get("policy"));

        Set<String> allowed = Set.of("child", "parent", "kind", "strategy", "inherit",
                "tags", "skills", "exclusions", "env", "policy", "contract");
        assertTrue(allowed.containsAll(payload.keySet()),
                () -> "unexpected payload keys: " + payload.keySet());
        for (String key : payload.keySet()) {
            assertFalse(key.toLowerCase().contains("grant"),
                    () -> "the event carries a grant-shaped channel '" + key + "': " + payload);
        }
    }

    @Test
    public void rootTaskSpawnAlsoFiresTheEventWithNoParent() {
        NaruTask root = newTask();
        NaruEvent spawned = session.eventLog().scan(0, e -> NaruEvent.TASK_SPAWNED.equals(e.name()))
                .stream().reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no TaskSpawned event"));
        assertEquals(root.id(), spawned.payload().get("child"));
        assertEquals(-1L, spawned.payload().get("parent"));
    }

    @Test
    public void exclusionsAndSkillsAlsoFlowIntoTheChild() {
        NaruTask parent = newTask();
        NaruTask child = spawn(parent, NaruTaskSpec.of()
                .addTags("exec")
                .excludeTools("run_shell")
                .addSkills("code-review"));

        assertEquals(Set.of("run_shell"), child.findToolExclusions());
        assertEquals(Set.of("exec"), child.findToolTagNames());
    }
}