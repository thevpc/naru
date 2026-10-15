package net.thevpc.naru.ext.tools.sh;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolCallContextImpl;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M6 shell guard: {@code run_shell} and the {@code /sh} directive both ask
 * the policy for {@code execute} on the command before anything is run.
 */
public class NaruShellGuardTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSessionImpl session() {
        NPath dir = NPath.ofTempFolder("naru-shell-guard-" + System.nanoTime());
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(dir);
        return new NaruSessionImpl(agent, dir,
                new NaruStreamInteraction(o -> {
                }), false, null, null, null, null);
    }

    private static NaruPermissionRule denyExecute(String id, String pattern) {
        return new NaruPermissionRule()
                .id(id)
                .resourceType(NaruResourceType.SHELL)
                .patterns(pattern)
                .actions(NaruAction.EXECUTE)
                .effect(NaruAccessEffect.DENY);
    }

    @Test
    public void executeRuleBlocksTheShellTool() {
        NaruSessionImpl s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        s.accessPolicy().putRule(denyExecute("deny-rm", "rm **"));

        NaruToolCallContext ctx = new NaruToolCallContextImpl(Map.of("command", "rm -rf /tmp/x"), task);
        String denied = new RunShellTool().execute(ctx);
        assertTrue(denied.startsWith("ERROR: permission denied"), denied);
        assertTrue(denied.contains("rm -rf /tmp/x"), denied);
        assertTrue(denied.contains("deny-rm"), denied);
    }

    @Test
    public void executeRuleBlocksTheShDirective() {
        NaruSessionImpl s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        s.accessPolicy().putRule(denyExecute("deny-rm", "rm **"));

        NaruDirectiveCallContext ctx = new NaruDirectiveCallContextImpl("sh", "rm -rf /tmp/x", task);
        NaruStmtResult result = new NaruShDirective().execute(ctx);

        assertNotNull(result.errorValue(), "a denied /sh yields a failed statement");
        assertTrue(String.valueOf(result.errorValue()).startsWith("ERROR: permission denied"),
                String.valueOf(result.errorValue()));
    }

    @Test
    public void emptyPolicyRunsTheCommand() {
        NaruSessionImpl s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());

        NaruToolCallContext ctx = new NaruToolCallContextImpl(Map.of("command", "echo naru-guard-ok"), task);
        String output = new RunShellTool().execute(ctx);
        assertFalse(output.startsWith("ERROR: permission denied"), output);
        assertTrue(output.contains("naru-guard-ok"), output);
    }
}
