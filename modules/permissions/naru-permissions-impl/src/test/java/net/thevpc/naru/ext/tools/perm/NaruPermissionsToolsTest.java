package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.registry.DefaultNaruToolset;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.registry.NaruRegistryImpl;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model-facing tools, dispatched through the real registry so the central
 * tool gate is exercised too.
 */
public class NaruPermissionsToolsTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSessionImpl sessionWithTools() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        ((NaruRegistryImpl) s.registry()).registerToolset(new DefaultNaruToolset("permissions", List.of(
                new PermissionListTool(),
                new PermissionCheckTool(),
                new PermissionAddTool(),
                new PermissionRemoveTool()
        )));
        return s;
    }

    @Test
    public void listRendersTheRules() {
        NaruSessionImpl s = sessionWithTools();
        NaruTask task = NaruPermissionsTestSupport.task(s);
        s.accessPolicy().putRule(new NaruPermissionRule().id("deny-echo")
                .resourceType(NaruResourceType.TOOL).patterns("echo_tool")
                .actions(NaruAction.CALL).effect(NaruAccessEffect.DENY));

        String out = s.registry().dispatch("permission_list", Map.of(), task);

        assertTrue(out.contains("tool-deny-echo".replace("tool-", "")), "rule id is listed: " + out);
        assertTrue(out.contains("type=tool"), out);
    }

    @Test
    public void checkAnswersWithTheMatchedRule() {
        NaruSessionImpl s = sessionWithTools();
        NaruTask task = NaruPermissionsTestSupport.task(s);
        s.accessPolicy().putRule(new NaruPermissionRule()
                .id("deny-echo").resourceType(NaruResourceType.TOOL).patterns("echo_tool")
                .actions(NaruAction.CALL).effect(NaruAccessEffect.DENY));

        String denied = s.registry().dispatch("permission_check",
                Map.of("type", "tool", "resource", "echo_tool", "action", "call"), task);
        assertTrue(denied.startsWith("DENY"), denied);
        assertTrue(denied.contains("deny-echo"), denied);

        String allowed = s.registry().dispatch("permission_check",
                Map.of("type", "tool", "resource", "other_tool", "action", "call"), task);
        assertTrue(allowed.startsWith("ALLOW"), allowed);
    }

    @Test
    public void addWritesASessionRuleAndRemoveDeletesIt() {
        NaruSessionImpl s = sessionWithTools();
        NaruTask task = NaruPermissionsTestSupport.task(s);

        String added = s.registry().dispatch("permission_add", Map.of(
                "id", "model-added",
                "type", "tool",
                "pattern", "echo_tool",
                "actions", "call",
                "effect", "deny"), task);
        assertTrue(added.startsWith("rule added"), added);

        NaruAccessPolicy policy = s.accessPolicy();
        NaruPermissionRule r = policy.rule("model-added").orNull();
        assertTrue(r != null);
        assertEquals(NaruPermissionsExtension.SOURCE_SESSION, r.source(),
                "a model-added rule rides the session state, never the project file");
        assertEquals(NaruAccessEffect.DENY, r.effect());

        String removed = s.registry().dispatch("permission_remove", Map.of("id", "model-added"), task);
        assertTrue(removed.startsWith("rule removed"), removed);
        assertFalse(policy.rule("model-added").isPresent());
    }

    @Test
    public void onlyTheMutatorsCarryThePermTag() {
        assertEquals(Set.of(PermissionAddTool.TAG), new PermissionAddTool().tags());
        assertEquals(Set.of(PermissionAddTool.TAG), new PermissionRemoveTool().tags());

        assertTrue(new PermissionListTool().tags().isEmpty());
        assertTrue(new PermissionCheckTool().tags().isEmpty());
        assertTrue(new PermissionListTool().isEssential(), "listing is offered without a tag");
        assertTrue(new PermissionCheckTool().isEssential(), "checking is offered without a tag");
    }

    @Test
    public void aDeniedMutatorStillReportsTheRule() {
        // The tools are gated by the same policy they edit: a deny rule on
        // permission_add is enough to keep a tagged model from widening its own limits.
        NaruSessionImpl s = sessionWithTools();
        NaruTask task = NaruPermissionsTestSupport.task(s);
        s.accessPolicy().putRule(new NaruPermissionRule()
                .id("no-self-grant").resourceType(NaruResourceType.TOOL).patterns("permission_add")
                .actions(NaruAction.CALL).effect(NaruAccessEffect.DENY));

        String denied = s.registry().dispatch("permission_add", Map.of(
                "id", "x", "type", "tool", "pattern", "y"), task);

        assertTrue(denied.startsWith("ERROR: permission denied"), denied);
        assertTrue(s.accessPolicy().rule("x").isEmpty(), "a denied mutation writes nothing");
    }
}
