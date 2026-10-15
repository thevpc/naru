package net.thevpc.naru.impl.access;

import net.thevpc.naru.api.access.NaruAccessDecision;
import net.thevpc.naru.api.access.NaruAccessDeniedException;
import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicyListener;
import net.thevpc.naru.api.access.NaruAccessRequest;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruActorKind;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruPrincipal;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.access.NaruSubject;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NIllegalArgumentException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The policy engine: first match wins, a deny ahead of a broad allow narrows it,
 * editing a rule keeps its slot, reordering is validated, and project-relative
 * patterns resolve against the requesting task's project.
 */
public class NaruAccessPolicyTest {

    private static final NaruResourceType FILE = NaruResourceType.FILE;
    private static final NaruActor ALICE = NaruActor.user("alice");
    private static final NaruActor AGENT = NaruActor.agent("reviewer", NaruPrincipal.user("alice"));

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruResource file(String locator) {
        return NaruResource.of(FILE, locator);
    }

    @Test
    public void anEmptyPolicyAllowsEverything() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        assertEquals(NaruAccessEffect.ALLOW, policy.defaultEffect());
        assertTrue(policy.isAllowed(file("/a/b"), NaruAction.READ, ALICE));
    }

    @Test
    public void aDenyAheadOfABroadAllowNarrowsIt() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        policy.putRule(new NaruPermissionRule().id("deny-agents-src").order(10)
                .resourceType(FILE).patterns("/proj/src/**").actions(NaruAction.WRITE)
                .actorKind(NaruActorKind.AGENT).effect(NaruAccessEffect.DENY));
        policy.putRule(new NaruPermissionRule().id("devs-write-src").order(100)
                .resourceType(FILE).patterns("/proj/src/**").actions(NaruAction.READ, NaruAction.WRITE)
                .subjects(NaruSubject.group("devs")).effect(NaruAccessEffect.ALLOW));

        // alice needs the group to match the allow; keep the test focused on order
        NaruActor dev = NaruActor.user(NaruPrincipal.user("alice").withGroup("devs"));
        assertTrue(policy.isAllowed(file("/proj/src/A.java"), NaruAction.WRITE, dev),
                "the user directly is not the agent the deny names");
        assertFalse(policy.isAllowed(file("/proj/src/A.java"), NaruAction.WRITE, AGENT),
                "the deny comes first and shadows the allow for agents");
        assertFalse(policy.isAllowed(file("/proj/src/A.java"), NaruAction.WRITE,
                        NaruActor.agent("r2", NaruPrincipal.user("alice").withGroup("devs"))),
                "an agent in the group still matches the deny first — that is what first-match means");
    }

    @Test
    public void defaultEffectDenyIsFailClosed() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        policy.defaultEffect(NaruAccessEffect.DENY);
        assertFalse(policy.isAllowed(file("/x"), NaruAction.READ, ALICE));
        policy.putRule(new NaruPermissionRule().id("read-all").resourceType(FILE)
                .patterns("/**").actions(NaruAction.READ).effect(NaruAccessEffect.ALLOW));
        assertTrue(policy.isAllowed(file("/x"), NaruAction.READ, ALICE));
        assertFalse(policy.isAllowed(file("/x"), NaruAction.WRITE, ALICE),
                "only the granted action is open");
    }

    @Test
    public void editingARuleKeepsItsSlot() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        policy.putRule(new NaruPermissionRule().id("a").effect(NaruAccessEffect.ALLOW));
        policy.putRule(new NaruPermissionRule().id("b").effect(NaruAccessEffect.DENY));
        policy.putRule(new NaruPermissionRule().id("c").effect(NaruAccessEffect.ALLOW));

        policy.putRule(new NaruPermissionRule().id("b").effect(NaruAccessEffect.ALLOW).description("edited"));

        assertEquals(List.of("a", "b", "c"), policy.rules().stream().map(NaruPermissionRule::id).toList());
        assertEquals("edited", policy.rule("b").get().description());
    }

    @Test
    public void reorderRequiresAPermutation() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        policy.putRule(new NaruPermissionRule().id("a"));
        policy.putRule(new NaruPermissionRule().id("b"));
        assertThrows(NIllegalArgumentException.class, () -> policy.reorder(List.of("a")));
        assertThrows(NIllegalArgumentException.class, () -> policy.reorder(List.of("a", "a")));
        assertThrows(NIllegalArgumentException.class, () -> policy.reorder(List.of("a", "z")));
        policy.reorder(List.of("b", "a"));
        assertEquals(List.of("b", "a"), policy.rules().stream().map(NaruPermissionRule::id).toList());
    }

    @Test
    public void moveBeforeAndAfterPlaceTheRuleExactly() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        policy.putRule(new NaruPermissionRule().id("a"));
        policy.putRule(new NaruPermissionRule().id("b"));
        policy.putRule(new NaruPermissionRule().id("c"));

        policy.moveRuleBefore("c", "a");
        assertEquals(List.of("c", "a", "b"), ids(policy));

        policy.moveRuleAfter("c", "b");
        assertEquals(List.of("a", "b", "c"), ids(policy));

        policy.moveRuleAfter("a", "c");
        assertEquals(List.of("b", "c", "a"), ids(policy));
    }

    @Test
    public void removeRulesBySourceDropsOnlyThatSource() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        policy.putRule(new NaruPermissionRule().id("a").source("perm:project"));
        policy.putRule(new NaruPermissionRule().id("b").source("perm:spawn:7"));
        policy.putRule(new NaruPermissionRule().id("c").source("perm:spawn:7"));

        assertEquals(2, policy.removeRules("perm:spawn:7"));
        assertEquals(List.of("a"), ids(policy));
    }

    @Test
    public void explainRecordsEveryRuleWeighed() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        policy.putRule(new NaruPermissionRule().id("only-read").resourceType(FILE)
                .patterns("/x").actions(NaruAction.READ).effect(NaruAccessEffect.ALLOW));
        policy.putRule(new NaruPermissionRule().id("deny-write").resourceType(FILE)
                .patterns("/x").actions(NaruAction.WRITE).effect(NaruAccessEffect.DENY));

        NaruAccessDecision d = policy.explain(NaruAccessRequest.of(file("/x"), NaruAction.WRITE, ALICE));
        assertFalse(d.allowed());
        assertEquals("deny-write", d.matchedRule().get().id());
        assertEquals(2, d.trace().size(), "both rules were weighed before the match");
        assertFalse(d.trace().get(0).matched());
        assertTrue(d.trace().get(1).matched());
    }

    @Test
    public void requireAccessThrowsWithTheDecision() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        policy.putRule(new NaruPermissionRule().id("no").effect(NaruAccessEffect.DENY));
        NaruAccessDeniedException ex = assertThrows(NaruAccessDeniedException.class,
                () -> policy.requireAccess(file("/x"), NaruAction.READ, ALICE));
        assertEquals("no", ex.decision().matchedRule().get().id());
    }

    @Test
    public void listenersSeeEditsAndDecisions() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        AtomicInteger added = new AtomicInteger();
        AtomicInteger decisions = new AtomicInteger();
        policy.addListener(new NaruAccessPolicyListener() {
            @Override
            public void onRuleAdded(NaruPermissionRule rule) {
                added.incrementAndGet();
            }

            @Override
            public void onDecision(NaruAccessRequest request, NaruAccessDecision decision) {
                decisions.incrementAndGet();
            }
        });
        policy.putRule(new NaruPermissionRule().id("x"));
        policy.check(file("/x"), NaruAction.READ, ALICE);
        assertEquals(1, added.get());
        assertEquals(1, decisions.get());
    }

    // ── pattern base ────────────────────────────────────────────────────────

    @Test
    public void resolvePatternRules() {
        assertEquals("/home/me/x", NaruAccessPolicyImpl.resolvePattern("~/x", "/proj")
                .replace(System.getProperty("user.home"), "/home/me"));
        assertEquals("/abs/x", NaruAccessPolicyImpl.resolvePattern("/abs/x", "/proj"));
        assertEquals("**/.env", NaruAccessPolicyImpl.resolvePattern("**/.env", "/proj"),
                "a match-anywhere pattern keeps its meaning");
        assertEquals("/proj/src/**", NaruAccessPolicyImpl.resolvePattern("src/**", "/proj"));
        assertEquals("/proj/src/**", NaruAccessPolicyImpl.resolvePattern("src/**", "/proj/"),
                "a trailing slash on the project dir does not double up");
    }

    @Test
    public void projectRelativePatternsResolveAgainstTheTask() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        policy.putRule(new NaruPermissionRule().id("deny-src").resourceType(FILE)
                .patterns("src/**").actions(NaruAction.WRITE).effect(NaruAccessEffect.DENY));

        NaruTask task = taskWithProject("/proj");
        // absolute locator, project-relative rule
        assertFalse(policy.check(NaruAccessRequest.of(file("/proj/src/A.java"), NaruAction.WRITE, ALICE).task(task)).allowed());
        // outside the project: the rule does not reach
        assertTrue(policy.check(NaruAccessRequest.of(file("/elsewhere/src/A.java"), NaruAction.WRITE, ALICE).task(task)).allowed());
    }

    @Test
    public void builtinsPrecedeRulesAddedLaterAndSurviveAClear() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        policy.putRule(new NaruPermissionRule().id("opened-up").resourceType(FILE)
                .patterns("/secret/**").effect(NaruAccessEffect.ALLOW));
        policy.addBuiltin(new NaruPermissionRule().id("core:deny").resourceType(FILE)
                .patterns("/secret/**").actions(NaruAction.READ).effect(NaruAccessEffect.DENY));

        // the built-in was inserted at the front, so it shadows the broad allow
        assertEquals("core:deny", policy.rules().get(0).id());
        assertFalse(policy.isAllowed(file("/secret/x"), NaruAction.READ, ALICE));

        // and a wipe restores it rather than losing the safe default
        policy.clearRules();
        assertEquals(List.of("core:deny"), ids(policy));
        assertFalse(policy.isAllowed(file("/secret/x"), NaruAction.READ, ALICE));
    }

    @Test
    public void installingABuiltinTwiceReplacesItInPlace() {
        NaruAccessPolicyImpl policy = new NaruAccessPolicyImpl();
        policy.addBuiltin(new NaruPermissionRule().id("core:a").resourceType(FILE)
                .patterns("/a/**").effect(NaruAccessEffect.DENY));
        policy.addBuiltin(new NaruPermissionRule().id("core:b").resourceType(FILE)
                .patterns("/b/**").effect(NaruAccessEffect.DENY));
        policy.addBuiltin(new NaruPermissionRule().id("core:a").resourceType(FILE)
                .patterns("/a2/**").effect(NaruAccessEffect.DENY));

        assertEquals(List.of("core:a", "core:b"), ids(policy));
        assertEquals(List.of("/a2/**"), policy.rule("core:a").get().patterns(),
                "the second declaration replaced the first");
    }

    private static List<String> ids(NaruAccessPolicyImpl policy) {
        return policy.rules().stream().map(NaruPermissionRule::id).toList();
    }

    private static NaruTask taskWithProject(String dir) {
        return (NaruTask) Proxy.newProxyInstance(
                NaruTask.class.getClassLoader(),
                new Class[]{NaruTask.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "projectDir":
                            return NPath.of(dir);
                        case "toString":
                            return "task(" + dir + ")";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            throw new UnsupportedOperationException(method.getName());
                    }
                });
    }
}
