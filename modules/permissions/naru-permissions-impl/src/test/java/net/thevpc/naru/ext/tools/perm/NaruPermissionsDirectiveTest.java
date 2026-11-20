package net.thevpc.naru.ext.tools.perm;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAccessPolicy;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /permission} — the human surface for editing the policy.
 */
public class NaruPermissionsDirectiveTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruStmtResult run(NaruTask task, String argument) {
        NaruDirective dir = new NaruPermissionsDirectiveProvider.NaruPermissionDirective();
        return task.invokeDirective(dir, new NaruDirectiveCallContextImpl("permission", argument, task));
    }

    private static NaruAccessPolicy policy(NaruSessionImpl s) {
        return s.accessPolicy();
    }

    @Test
    public void addUpdateAndRemove() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruTask task = NaruPermissionsTestSupport.task(s);
        NaruAccessPolicy policy = policy(s);

        run(task, "add tool-deny-echo --type=tool --pattern=echo_tool --actions=call --effect=deny");
        NaruPermissionRule r = policy.rule("tool-deny-echo").orNull();
        assertNotNull(r, "the rule was added");
        assertEquals(NaruResourceType.TOOL, r.resourceType());
        assertEquals(List.of("echo_tool"), r.patterns());
        assertEquals(NaruAccessEffect.DENY, r.effect());

        run(task, "update tool-deny-echo --effect=allow");
        assertEquals(NaruAccessEffect.ALLOW, policy.rule("tool-deny-echo").get().effect());

        run(task, "remove tool-deny-echo");
        assertTrue(policy.rule("tool-deny-echo").isEmpty());
    }

    @Test
    public void updateLeavesUnmentionedFieldsAlone() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruTask task = NaruPermissionsTestSupport.task(s);
        NaruAccessPolicy policy = policy(s);

        run(task, "add r1 --type=file --pattern=src/** --actions=r,w --effect=deny");
        run(task, "update r1 --effect=allow");

        NaruPermissionRule r = policy.rule("r1").get();
        assertEquals(NaruAccessEffect.ALLOW, r.effect());
        assertEquals(List.of("src/**"), r.patterns(), "the pattern survives an update that did not mention it");
        assertTrue(r.actions().stream().anyMatch(a -> a.id().equals("read")));
        assertTrue(r.actions().stream().anyMatch(a -> a.id().equals("write")));
    }

    @Test
    public void addHonoursAnExplicitIndex() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruTask task = NaruPermissionsTestSupport.task(s);
        NaruAccessPolicy policy = policy(s);

        run(task, "add a --type=tool --pattern=a --effect=allow");
        run(task, "add b --type=tool --pattern=b --effect=allow");
        run(task, "add c --type=tool --pattern=c --effect=allow --index=0");

        assertEquals("c", policy.rules().get(0).id());
    }

    @Test
    public void moveReordersRules() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruTask task = NaruPermissionsTestSupport.task(s);
        NaruAccessPolicy policy = policy(s);

        run(task, "add a --type=tool --pattern=a --effect=allow");
        run(task, "add b --type=tool --pattern=b --effect=allow");
        run(task, "add c --type=tool --pattern=c --effect=allow");

        run(task, "move c --before=a");
        assertEquals(List.of("c", "a", "b"), ids(policy));

        run(task, "move c --index=2");
        assertEquals(List.of("a", "b", "c"), ids(policy));
    }

    @Test
    public void defaultChangesTheFallbackEffect() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruTask task = NaruPermissionsTestSupport.task(s);

        assertEquals(NaruAccessEffect.ALLOW, policy(s).defaultEffect());
        run(task, "default deny");
        assertEquals(NaruAccessEffect.DENY, policy(s).defaultEffect());
    }

    @Test
    public void checkAnswersAndFailsOnDenial() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruTask task = NaruPermissionsTestSupport.task(s);

        run(task, "add tool-deny --type=tool --pattern=echo --actions=call --effect=deny");

        NaruStmtResult denied = run(task, "check --type=tool --resource=echo --action=call");
        assertNotNull(denied.errorValue(), "a denied check is a failed statement a script can branch on");

        NaruStmtResult allowed = run(task, "check --type=tool --resource=other --action=call");
        assertEquals(0, allowed.exitCode());

        NaruStmtResult explained = run(task, "explain --type=tool --resource=echo --action=call");
        assertEquals(0, explained.exitCode(), "explain is a report and always succeeds");
    }

    @Test
    public void listAndRemoveReportMissingRules() {
        NaruSessionImpl s = NaruPermissionsTestSupport.session();
        NaruTask task = NaruPermissionsTestSupport.task(s);

        assertEquals(0, run(task, "list").exitCode());
        NaruStmtResult missing = run(task, "remove nope");
        assertNotNull(missing.errorValue());
    }

    private static List<String> ids(NaruAccessPolicy policy) {
        return policy.rules().stream().map(NaruPermissionRule::id).toList();
    }
}
