package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The O3 "effect tags AND" rule, behind {@code naru.tags.effectAnd}.
 *
 * <p>Today a tool is visible when <em>any</em> of its tags is granted. That is fine for a
 * tool that only mentions a domain ({@code git}, {@code dev}), but it reads wrong for a
 * tool that reaches outside the process: {@code run_shell} wears {@code network}, {@code exec}
 * and {@code write}, and "any one" means a task granted only {@code write} -- a right meant
 * for editing a file -- is also handed a shell.
 *
 * <p>The rule is opt-in while it is evaluated: {@code off} (default) keeps OR,
 * {@code on} enforces "every effect granted, at least one domain granted", and
 * {@code warn} keeps OR but reports the tools the rule would withdraw. These tests pin all
 * three so the mode is not aspirational.
 */
public class NaruEffectTagAndTest {

    private static final String FLAG = "naru.tags.effectAnd";

    private NaruAgent agent;
    private NaruSessionImpl session;
    private final List<String> outputs = new ArrayList<>();

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
        System.clearProperty(FLAG);
        agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-effect-tag-and"));
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(FLAG);
        if (session != null) {
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
    }

    /** Opens a session under the given flag mode ({@code null} leaves it unset). */
    private void openSession(String flagMode) {
        if (flagMode != null) {
            System.setProperty(FLAG, flagMode);
        }
        outputs.clear();
        session = new NaruSessionImpl(agent, agent.projectDirectory(),
                new NaruStreamInteraction(o -> outputs.add(String.valueOf(o.message()))), true,
                NOOP_LISTENER, null, null, null);
    }

    private NaruPromptMode mode(String name) {
        NaruPromptMode m = session.registry().mode(name).orNull();
        assertNotNull(m, "no mode '" + name + "' is registered");
        return m;
    }

    /** An implement-mode task; plan mode would veto write/exec tools before the tag gate runs. */
    private NaruTask newTask(String... tags) {
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.promptMode(mode("implement"));
        for (String tag : tags) {
            task.addToolTag(tag);
        }
        return task;
    }

    private static List<String> toolNames(NaruTask task) {
        return task.findTools().stream().map(NaruToolDefinition::getName).toList();
    }

    private boolean warnedAboutEffectAnd() {
        return outputs.stream().anyMatch(o -> o != null
                && o.contains("effect-tag AND rule"));
    }

    // ── off (default): exactly the old OR rule ───────────────────────────────

    @Test
    public void offByDefaultAndStillOr() {
        openSession(null);
        assertEquals("off", session.effectTagAndMode(),
                "the flag must be off unless something asks for it");

        // run_shell = {network, exec, write}: any single one is enough under OR.
        List<String> names = toolNames(newTask("write"));
        assertTrue(names.contains("run_shell"),
                () -> "with the flag off, 'write' alone must still show run_shell: " + names);
    }

    // ── on: every effect granted, domain keeps OR ────────────────────────────

    @Test
    public void onRequiresEveryEffectTagNotJustOne() {
        openSession("on");
        assertTrue(session.isEffectTagAndEnabled());

        List<String> withWriteOnly = toolNames(newTask("write"));
        assertFalse(withWriteOnly.contains("run_shell"),
                () -> "'write' alone must not reveal a tool that also needs exec and network: "
                        + withWriteOnly);

        List<String> withAllEffects = toolNames(newTask("write", "exec", "network"));
        assertTrue(withAllEffects.contains("run_shell"),
                () -> "all three effects granted must reveal run_shell: " + withAllEffects);
    }

    @Test
    public void onKeepsDomainTagsOr() {
        openSession("on");

        // git_commit = {dev, git, write}: needs the write effect plus at least one domain.
        assertFalse(toolNames(newTask("git")).contains("git_commit"),
                "git without write must not reveal git_commit under AND");
        assertTrue(toolNames(newTask("git", "write")).contains("git_commit"),
                "git + write must reveal git_commit: every effect held, one domain held");

        // git_status = {dev, git}: no effect at all, so it is pure OR and git alone reveals it.
        assertTrue(toolNames(newTask("git")).contains("git_status"),
                "a domain-only tool must keep the OR rule");
    }

    // ── warn: OR is kept, the mismatch is reported ───────────────────────────

    @Test
    public void warnModeKeepsOrAndReportsWhatWouldDisappear() {
        openSession("warn");
        assertEquals("warn", session.effectTagAndMode());
        assertFalse(session.isEffectTagAndEnabled(),
                "warn mode must not enforce the rule");

        List<String> names = toolNames(newTask("write"));
        assertTrue(names.contains("run_shell"),
                () -> "warn mode keeps today's OR visibility: " + names);
        assertTrue(warnedAboutEffectAnd(),
                "warn mode must name the tools the rule would withdraw");
    }

    // ── the resolver ─────────────────────────────────────────────────────────

    @Test
    public void modeIsResolvedFromTheSystemProperty() {
        openSession("on");
        assertEquals("on", session.effectTagAndMode());
    }

    @Test
    public void unknownModeFallsBackToOff() {
        openSession("banana");
        assertEquals("off", session.effectTagAndMode(),
                "an unreadable flag value must not silently enforce a new rule");
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
