package net.thevpc.naru.impl.engine;

import net.thevpc.naru.api.access.NaruAccessEffect;
import net.thevpc.naru.api.access.NaruAction;
import net.thevpc.naru.api.access.NaruActor;
import net.thevpc.naru.api.access.NaruActorKind;
import net.thevpc.naru.api.access.NaruPermissionRule;
import net.thevpc.naru.api.access.NaruResourceType;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.registry.NaruDirective;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveProviderBase;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolset;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.naru.impl.registry.NaruDirectiveCallContextImpl;
import net.thevpc.naru.impl.registry.NaruRegistryImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NText;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The central M3 gates: a denied tool call, a denied directive and the tool
 * list sent to the model all consult the session policy against the task's
 * current actor — and, with an empty policy, none of it changes behaviour.
 */
public class NaruAccessEnforcementTest {

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static NaruSessionImpl session() {
        NPath dir = NPath.ofTempFolder("naru-enforce-" + System.nanoTime());
        NaruAgentImpl agent = new NaruAgentImpl();
        agent.projectDirectory(dir);
        return new NaruSessionImpl(agent, dir,
                new NaruStreamInteraction(o -> {
                }), false, null, null, null, null);
    }

    private static NaruPermissionRule deny(String id, NaruResourceType type, String pattern) {
        return new NaruPermissionRule()
                .id(id)
                .resourceType(type)
                .patterns(pattern)
                .actions(NaruAction.CALL)
                .effect(NaruAccessEffect.DENY);
    }

    private static NaruRegistryImpl registry(NaruSession s) {
        return (NaruRegistryImpl) s.registry();
    }

    @Test
    public void deniedToolCallIsReportedAndNeverExecutes() {
        NaruSessionImpl s = session();
        FlagTool tool = new FlagTool("echo_tool");
        registry(s).registerToolset(new SingleToolToolset("t1", tool));
        s.accessPolicy().putRule(deny("deny-echo", NaruResourceType.TOOL, "echo_tool"));

        NaruTask task = s.newTask(NaruTaskSpec.of());
        String result = s.registry().dispatch("echo_tool", Map.of(), task);

        assertTrue(result.startsWith("ERROR: permission denied"),
                "a denied call returns the stable denial shape, was: " + result);
        assertTrue(result.contains("echo_tool"));
        assertTrue(result.contains("deny-echo"), "the responsible rule is named: " + result);
        assertFalse(tool.ran.get(), "the tool must not run when denied");
    }

    @Test
    public void emptyPolicyAllowsTheTool() {
        NaruSessionImpl s = session();
        FlagTool tool = new FlagTool("echo_tool");
        registry(s).registerToolset(new SingleToolToolset("t1", tool));

        NaruTask task = s.newTask(NaruTaskSpec.of());
        String result = s.registry().dispatch("echo_tool", Map.of(), task);

        assertEquals("OK", result);
        assertTrue(tool.ran.get());
    }

    @Test
    public void deniedToolIsHiddenFromTheModel() {
        NaruSessionImpl s = session();
        FlagTool tool = new FlagTool("echo_tool");
        registry(s).registerToolset(new SingleToolToolset("t1", tool));

        NaruTask open = s.newTask(NaruTaskSpec.of());
        assertTrue(hasTool(open.findTools(), "echo_tool"), "visible while allowed");

        s.accessPolicy().putRule(deny("deny-echo", NaruResourceType.TOOL, "echo_tool"));
        NaruTask closed = s.newTask(NaruTaskSpec.of());
        assertFalse(hasTool(closed.findTools(), "echo_tool"),
                "a tool the actor may not call is not offered to the model");
    }

    @Test
    public void deniedDirectiveDoesNotExecute() {
        NaruSessionImpl s = session();
        FlagDirective dir = new FlagDirective("secret_dir");
        s.registry().registerDirectiveProvider(new NaruDirectiveProviderBase("test") {{
            registerDirective(dir);
        }});
        s.accessPolicy().putRule(deny("deny-dir", NaruResourceType.DIRECTIVE, "secret_dir"));

        NaruTask task = s.newTask(NaruTaskSpec.of());
        NaruStmtResult result =
                task.invokeDirective(dir, new NaruDirectiveCallContextImpl("secret_dir", "", task));

        assertNotNull(result.errorValue(), "a denied directive yields a failed statement");
        assertTrue(String.valueOf(result.errorValue()).startsWith("ERROR: permission denied"));
        assertFalse(dir.ran.get(), "the directive must not run when denied");
    }

    @Test
    public void allowedDirectiveRuns() {
        NaruSessionImpl s = session();
        FlagDirective dir = new FlagDirective("open_dir");
        s.registry().registerDirectiveProvider(new NaruDirectiveProviderBase("test") {{
            registerDirective(dir);
        }});

        NaruTask task = s.newTask(NaruTaskSpec.of());
        NaruStmtResult result =
                task.invokeDirective(dir, new NaruDirectiveCallContextImpl("open_dir", "", task));

        assertEquals(0, result.exitCode());
        assertTrue(dir.ran.get());
    }

    @Test
    public void theGateSpeaksForTheStatementAuthor() {
        NaruSessionImpl s = session();
        FlagTool tool = new FlagTool("echo_tool");
        registry(s).registerToolset(new SingleToolToolset("t1", tool));
        // Only agents are denied; the user the session belongs to may still call it.
        s.accessPolicy().putRule(new NaruPermissionRule()
                .id("deny-agents").resourceType(NaruResourceType.TOOL).patterns("echo_tool")
                .actions(NaruAction.CALL)
                .actorKind(NaruActorKind.AGENT)
                .effect(NaruAccessEffect.DENY));

        // The user acts directly: allowed.
        NaruTask userTask = s.newTask(NaruTaskSpec.of());
        assertEquals("OK", s.registry().dispatch("echo_tool", Map.of(), userTask));
        assertTrue(tool.ran.get());

        // An agent acts: denied, because the check reads the actor in effect.
        tool.ran.set(false);
        NaruTask agentTask = s.newTask(NaruTaskSpec.of()
                .actor(NaruActor.agent("reviewer", s.user())));
        String denied = s.registry().dispatch("echo_tool", Map.of(), agentTask);
        assertTrue(denied.startsWith("ERROR: permission denied"), denied);
        assertFalse(tool.ran.get());
    }

    private static boolean hasTool(List<NaruToolDefinition> defs, String name) {
        for (NaruToolDefinition d : defs) {
            if (name.equals(d.getName())) {
                return true;
            }
        }
        return false;
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static final class FlagTool implements NaruTool {
        private final String name;
        final AtomicBoolean ran = new AtomicBoolean();

        FlagTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public NText getDescription(NaruTask task) {
            return NText.ofPlain("test tool");
        }

        @Override
        public Set<String> tags() {
            return Collections.emptySet();
        }

        @Override
        public boolean isEssential() {
            return true;
        }

        @Override
        public NaruToolDefinition getDefinition(NaruTask task) {
            return new NaruToolDefinition(name, "test tool");
        }

        @Override
        public String execute(NaruToolCallContext context) {
            ran.set(true);
            return "OK";
        }
    }

    private static final class FlagDirective implements NaruDirective {
        private final String name;
        final AtomicBoolean ran = new AtomicBoolean();

        FlagDirective(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String group() {
            return "test";
        }

        @Override
        public String[] getAliases() {
            return new String[0];
        }

        @Override
        public String getDescription() {
            return "test directive";
        }

        @Override
        public NaruStmtResult execute(NaruDirectiveCallContext context) {
            ran.set(true);
            return NaruStmtResult.ofSuccess("done");
        }
    }

    private static final class SingleToolToolset implements NaruToolset {
        private final String id;
        private final List<NaruTool> tools;

        SingleToolToolset(String id, NaruTool tool) {
            this.id = id;
            this.tools = List.of(tool);
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public List<NaruTool> tools() {
            return tools;
        }

        @Override
        public void open(NaruSession session) {
        }

        @Override
        public void close() {
        }
    }
}
