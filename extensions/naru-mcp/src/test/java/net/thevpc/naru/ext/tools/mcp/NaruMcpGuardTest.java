package net.thevpc.naru.ext.tools.mcp;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M6 MCP guard: a {@code mcp-tool} rule gates a bridged remote tool by name,
 * answered without touching the MCP client.
 */
public class NaruMcpGuardTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSessionImpl session() {
        NPath dir = NPath.ofTempFolder("naru-mcp-guard-" + System.nanoTime());
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(dir);
        return new NaruSessionImpl(agent, dir,
                new NaruStreamInteraction(o -> {
                }), false, null, null, null, null);
    }

    private static NaruPermissionRule denyCall(String id, String pattern) {
        return new NaruPermissionRule()
                .id(id)
                .resourceType(NaruResourceType.MCP_TOOL)
                .patterns(pattern)
                .actions(NaruAction.CALL)
                .effect(NaruAccessEffect.DENY);
    }

    @Test
    public void callRuleBlocksTheBridgedTool() {
        NaruSessionImpl s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        s.accessPolicy().putRule(denyExecute("deny-mcp"));

        String denied = McpBackedTool.denied(task, "fs_write");
        assertNotNull(denied);
        assertTrue(denied.startsWith("ERROR: permission denied"), denied);
        assertTrue(denied.contains("fs_write"), denied);
        assertTrue(denied.contains("deny-mcp"), denied);
    }

    @Test
    public void otherBridgedToolIsNotDenied() {
        NaruSessionImpl s = session();
        NaruTask task = s.newTask(NaruTaskSpec.of());
        s.accessPolicy().putRule(denyExecute("deny-mcp"));

        assertNull(McpBackedTool.denied(task, "fs_read"),
                "a tool outside the pattern is untouched");
    }

    private static NaruPermissionRule denyExecute(String id) {
        return new NaruPermissionRule()
                .id(id)
                .resourceType(NaruResourceType.MCP_TOOL)
                .patterns("fs_write")
                .actions(NaruAction.CALL)
                .effect(NaruAccessEffect.DENY);
    }
}
