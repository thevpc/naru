package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruAccessRequest;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResource;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The built-in safe defaults: installed at the front, idempotent, re-seeded by a
 * clear, and narrow enough that a human acting directly is never surprised.
 */
public class NaruPermissionsBuiltinsTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    @Test
    public void installsTheSafeDefaultsAtTheFront() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        s.accessPolicy().putRule(new NaruPermissionRule().id("user-rule")
                .resourceType(NaruResourceType.TOOL).patterns("x").effect(NaruAccessEffect.ALLOW));

        assertEquals(3, NaruPermissionsBuiltins.install(s.accessPolicy()));

        List<NaruPermissionRule> rules = s.accessPolicy().rules();
        assertEquals(NaruPermissionsBuiltins.NO_AGENT_POLICY_DIRECTIVE, rules.get(0).id(),
                "a built-in is a baseline guarantee and precedes rules added later");
        assertEquals(NaruPermissionsBuiltins.NO_AGENT_SECRET_FILES, rules.get(1).id());
        assertEquals(NaruPermissionsBuiltins.NO_AGENT_BUDGET_WRITE, rules.get(2).id());
        assertEquals(NaruAccessPolicy.SOURCE_CORE, rules.get(0).source());
        assertEquals("user-rule", rules.get(rules.size() - 1).id());
    }

    @Test
    public void installingTwiceDoesNotDuplicate() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruPermissionsBuiltins.install(s.accessPolicy());
        NaruPermissionsBuiltins.install(s.accessPolicy());

        long core = s.accessPolicy().rules().stream()
                .filter(r -> NaruAccessPolicy.SOURCE_CORE.equals(r.source())).count();
        assertEquals(3, core);
    }

    @Test
    public void clearRulesReseedsTheBuiltins() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruPermissionsBuiltins.install(s.accessPolicy());
        s.accessPolicy().putRule(new NaruPermissionRule().id("temp").resourceType(NaruResourceType.TOOL));

        s.accessPolicy().clearRules();

        assertEquals(List.of(
                        NaruPermissionsBuiltins.NO_AGENT_POLICY_DIRECTIVE,
                        NaruPermissionsBuiltins.NO_AGENT_SECRET_FILES,
                        NaruPermissionsBuiltins.NO_AGENT_BUDGET_WRITE),
                s.accessPolicy().rules().stream().map(NaruPermissionRule::id).toList());
    }

    @Test
    public void anAgentMayNotCallThePermissionDirectiveButAUserMay() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruPermissionsBuiltins.install(s.accessPolicy());

        NaruResource directive = NaruResource.directive("permission");
        assertFalse(s.accessPolicy().check(directive, NaruAction.CALL,
                NaruActor.agent("a1", null)).allowed(), "an agent must not edit policy through the directive");
        assertTrue(s.accessPolicy().check(directive, NaruAction.CALL,
                NaruActor.user("alice")).allowed(), "the human path stays open");
    }

    @Test
    public void secretsAreDeniedToAgentsOnly() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruPermissionsBuiltins.install(s.accessPolicy());
        String home = System.getProperty("user.home", "/home");
        NaruResource secret = NaruResource.of(NaruResourceType.FILE, home + "/.ssh/id_rsa");

        assertFalse(s.accessPolicy().check(NaruAccessRequest.of(secret, NaruAction.READ,
                NaruActor.agent("a1", null))).allowed(), "an agent must not read a private key");
        assertTrue(s.accessPolicy().check(NaruAccessRequest.of(secret, NaruAction.READ,
                NaruActor.user("alice"))).allowed(), "a user reads their own key");

        assertTrue(s.accessPolicy().check(NaruAccessRequest.of(
                        NaruResource.of(NaruResourceType.FILE, home + "/notes.txt"),
                        NaruAction.READ, NaruActor.agent("a1", null))).allowed(),
                "an ordinary file is unaffected");
    }

    /**
     * An agent that can set prices or labels can rewrite its own bill, so the
     * mutation path is denied while the report stays readable: a model still needs to
     * know what it has spent.
     */
    @Test
    public void anAgentMayNotMutateBudgetButAUserMay() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruPermissionsBuiltins.install(s.accessPolicy());
        NaruResource price = NaruResource.of(NaruPermissionsBuiltins.BUDGET_RESOURCE, "price");
        NaruResource label = NaruResource.of(NaruPermissionsBuiltins.BUDGET_RESOURCE, "label");
        NaruAction update = NaruPermissionsBuiltins.BUDGET_UPDATE;

        assertFalse(s.accessPolicy().check(price, update, NaruActor.agent("a1", null)).allowed(),
                "an agent must not set its own prices");
        assertFalse(s.accessPolicy().check(label, update, NaruActor.agent("a1", null)).allowed(),
                "an agent must not relabel its own calls");
        assertTrue(s.accessPolicy().check(price, update, NaruActor.user("alice")).allowed(),
                "a human may set prices");
        assertTrue(s.accessPolicy().check(price, NaruAction.READ, NaruActor.agent("a1", null)).allowed(),
                "an agent may still read the report");
    }
}
