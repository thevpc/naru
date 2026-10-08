package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan mode must not offer a single tool that writes or executes, no matter what the
 * task has been granted.
 *
 * <p>The mode's veto is {@code PlanNaruPromptMode.acceptToolTags}: it rejects any tool
 * whose tag set contains {@code write} or {@code exec}. That check runs against the
 * tool's <b>whole</b> tag set, so a multi-tagged tool is rejected if any one of its
 * tags is banned -- which is what makes {@code file_write} ({@code fs}+{@code write})
 * and {@code run_shell} ({@code network}+{@code exec}) stay out even when the task
 * holds {@code fs} and {@code network}, the two read-friendly tags a planner is
 * normally given.
 *
 * <p>Granting everything first is deliberate: the test asks the question as sharply as
 * it can be asked. If the gate were permissive in any direction -- mode veto first,
 * tag gate second, or tag grant bypassing the veto -- a fully granted task in plan
 * mode would show the leak here.
 */
public class NaruPlanModeToolGateTest {

    private NaruSession session;
    private NaruTask task;

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
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-plan-mode-gate"));
        session = new NaruSessionImpl(agent, agent.projectDirectory(), null, true,
                NOOP_LISTENER, null, null, null);
        task = session.newTask(NaruTaskSpec.of());
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

    private NaruPromptMode mode(String name) {
        NaruPromptMode m = session.registry().mode(name).orNull();
        assertNotNull(m, "no mode '" + name + "' is registered");
        return m;
    }

    /** Tags a planner needs: reading, navigating, and planning itself. */
    private void grantEverything() {
        for (String tag : session.registry().availableTags().keySet()) {
            task.addToolTag(tag);
        }
    }

    private List<String> toolNames() {
        return task.findTools().stream().map(NaruToolDefinition::getName).toList();
    }

    private Map<String, NaruTool> tools() {
        return session.registry().tools();
    }

    @Test
    public void planModeOffersNoToolWearingWriteOrExec() {
        grantEverything();
        task.promptMode(mode("plan"));

        List<String> banned = toolNames().stream()
                .filter(n -> {
                    NaruTool t = tools().get(n);
                    return t != null && (t.tags().contains("write") || t.tags().contains("exec"));
                })
                .collect(Collectors.toList());

        assertEquals(List.of(), banned,
                () -> "plan mode promised READ-ONLY but offered these write/exec tools: " + banned);
    }

    /**
     * The sharper version: the two tools that matter are multi-tagged, so a naive
     * "does the task hold fs/network" check would let them through.
     */
    @Test
    public void fileWriteAndRunShellStayOutEvenWhenFsAndNetworkAreGranted() {
        task.addToolTag("fs");
        task.addToolTag("network");
        task.promptMode(mode("plan"));

        List<String> names = toolNames();
        assertTrue(names.contains("file_read"), () -> "planning needs read access: " + names);
        assertEquals(List.of(), List.of("file_write", "run_shell").stream()
                        .filter(names::contains).collect(Collectors.toList()),
                () -> "write/exec tools leaked into plan mode for a task granted fs+network: " + names);
    }

    @Test
    public void theSameTaskSeesThoseToolsInImplementMode() {
        // without this, the test above would also pass if the tools had simply
        // disappeared from the registry altogether
        task.addToolTag("fs");
        task.addToolTag("network");
        task.promptMode(mode("implement"));

        List<String> names = toolNames();
        assertTrue(names.contains("file_write"), () -> "file_write must be usable when executing: " + names);
        assertTrue(names.contains("run_shell"), () -> "run_shell must be usable when executing: " + names);
    }

    /**
     * The veto is the mode's, not the grant's: taking the tags away and giving them
     * back must not change what plan mode is willing to show.
     */
    @Test
    public void theVetoSurvivesRetagging() {
        task.promptMode(mode("plan"));
        grantEverything();

        List<String> banned = toolNames().stream()
                .filter(n -> {
                    NaruTool t = tools().get(n);
                    return t != null && (t.tags().contains("write") || t.tags().contains("exec"));
                })
                .collect(Collectors.toList());
        assertEquals(List.of(), banned,
                () -> "granting tags after entering plan mode bypassed the mode veto: " + banned);
    }

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void sessionStarted(NaruSession s) {
        }

        @Override
        public void sessionStopped(NaruSession s) {
        }

        @Override
        public void onSessionReloaded(NaruSession s) {
        }
    };
}
