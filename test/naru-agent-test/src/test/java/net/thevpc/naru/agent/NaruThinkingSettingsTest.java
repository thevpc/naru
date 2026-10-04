package net.thevpc.naru.agent;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.agent.NaruVisibility;
import net.thevpc.naru.api.model.NaruThinkingConfig;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code model.thinking=false}, from the directive that writes it to the field a
 * model actually reads.
 *
 * <p>Switching reasoning off is two separate claims, and only doing the first is how
 * "thinking is off" ends up not true:
 *
 * <ol>
 *   <li>the model is not asked to reason -- {@code think:false} on the wire;</li>
 *   <li>the model is not handed a tool for narrating reasoning through, because an
 *       offered tool is an instruction to call it.</li>
 * </ol>
 *
 * <p>The second is the one that is easy to miss and impossible to see in a response:
 * the reasoning comes back as {@code think} tool calls instead, at full length.
 */
public class NaruThinkingSettingsTest {

    private NaruAgent agent;
    private NPath folder;
    private NaruSession session;

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
        folder = NPath.ofTempFolder("naru-thinking");
        agent = new NaruAgentImpl();
        agent.projectDirectory(folder);
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

    /**
 * Runs a script and hands back its task, with the session still alive.
     *
     * <p>The shape is the documented embedding one: a resident interactive task that
     * never terminates, plus the request task. It is needed because a session stops
     * itself when its last task ends, and a stopped session refuses every read these
     * assertions depend on -- {@code findTools()}, and the project env behind the
     * whole settings chain -- with "session is stopped". The resident task has to
     * answer input without ever replying, or it would be cancelled as a leak.
     */
    private NaruTask runScript(String... statements) {
        if (session != null) {
            // each call opens a new session; a test that calls this twice must not
            // leave the first one's resident task running
            try {
                session.stop();
            } catch (Exception ignored) {
            }
        }
        session = agent.newSession()
                .interaction(new NaruStreamInteraction(o -> {
                }, request -> {
                }))
                .interactive()
                .task(NaruTaskSpec.of().statements("/print 0").name("host"))
                .build();
        session.run();
        NaruTask task = session.run(
                NaruTaskSpec.of().statements(statements).resolveName());
        task.await();
        return task;
    }

    private static List<String> toolNames(NaruTask task) {
        return task.findTools().stream().map(NaruToolDefinition::getName).toList();
    }

    // ── the setting is readable, writable and tri-state ─────────────────────

    @Test
    public void thinkingIsOnWhenNothingSaysOtherwise() {
        NaruTask task = runScript("/print 1");
        assertTrue(NaruThinkingConfig.isEnabled(task),
                "an unset model.thinking must not read as off: that is how reasoning silently disappears");
        assertFalse(NaruThinkingConfig.find(task).isPresent(),
                "unset has to stay distinguishable from false, or the wire flag cannot default to the capability");
    }

    @Test
    public void theDirectiveWritesToThePrivateFileAndReadsBack() {
        NaruTask task = runScript("/settings model.thinking=false");

        assertTrue(folder.resolve(".naru/local/config/env.tson").isRegularFile(),
                () -> "expected the private config to be written, got: " + folder);
        assertFalse(NaruThinkingConfig.isEnabled(task));
        assertFalse(NaruThinkingConfig.find(task).orElse(true));
        // the stored value is an NElement, not a java Boolean, so the assertion has to
        // go through the element -- otherwise this would "pass" for the string "false"
        // just as easily as for the boolean
        NElement stored = task.session()
                .getProjectEnv(NaruThinkingConfig.THINKING_KEY, NaruVisibility.PRIVATE).orNull();
        assertNotNull(stored, "the private config should hold the setting");
        assertTrue(stored.isBoolean(),
                () -> "expected a boolean element, got " + stored.getClass() + ": " + stored);
        assertFalse(stored.asBooleanValue().orElse(true));
    }

    @Test
    public void aPublicWriteLandsInTheCheckedInFile() {
        runScript("/settings --public model.thinking=false");
        assertTrue(folder.resolve(".naru/config/env.tson").isRegularFile(),
                () -> "expected the checked-in config to be written, got: " + folder);
        assertFalse(folder.resolve(".naru/local/config/env.tson").isRegularFile(),
                "--public must not also write the private file");
    }

    /**
     * Private and public are the visibility axis of one store, not two scopes: a
     * private value shadows a public one for the same key, and removing the private
     * one uncovers the public one rather than leaving nothing.
     */
    @Test
    public void privateShadowsPublicAndUncoversItOnRemoval() {
        NaruTask task = runScript(
                "/settings --public model.thinking=false",
                "/settings model.thinking=true");
        assertTrue(NaruThinkingConfig.isEnabled(task),
                "the private value wins over the public one for the same key");

        NaruTask afterRemoval = runScript(
                "/settings --public model.thinking=false",
                "/settings model.thinking=true",
                "/settings model.thinking=null");
        assertFalse(NaruThinkingConfig.isEnabled(afterRemoval),
                "removing the private value should uncover the public one, not clear the key");
    }

    @Test
    public void removingTheKeyRestoresTheDefault() {
        NaruTask task = runScript(
                "/settings model.thinking=false",
                "/settings model.thinking=null");
        assertTrue(NaruThinkingConfig.isEnabled(task));
    }

    /**
     * {@code /set} owns the task and session env; {@code /settings} must not offer a
     * second spelling for them. Pinned because the overlap would be invisible: the
     * session write would work, just silently skipping the config files, and the two
     * commands would disagree about which store a value lives in.
     */
    @Test
    public void theDirectiveDoesNotClaimTheTaskOrSessionEnv() {
        NaruTask task = runScript("/settings model.thinking=false");
        NaruTaskConfig.Resolved resolved = NaruTaskConfig.resolve(task, NaruThinkingConfig.THINKING_KEY).get();
        assertEquals(NaruTaskConfig.SCOPE_CONFIG, resolved.scope(),
                "a /settings write belongs to the config store and nowhere else");
        assertEquals(NaruVisibility.PRIVATE, resolved.visibility());
        assertFalse(task.session().getSessionEnv(NaruThinkingConfig.THINKING_KEY).isPresent(),
                "/settings must not write the session env that /set owns");
        assertFalse(task.getTaskEnv(NaruThinkingConfig.THINKING_KEY, false).isPresent(),
                "/settings must not write the task env that /set owns");
    }

    @Test
    public void aValueThatIsNotABooleanIsNotGuessedAt() {
        // "maybe" must not read as false: a typo that switches reasoning off is
        // worse than one that leaves it alone and says so.
        NaruTask task = runScript("/settings model.thinking=maybe");
        assertTrue(NaruThinkingConfig.isEnabled(task));
        assertEquals("maybe", NaruTaskConfig.toText(
                        task.session().getProjectEnv("model.thinking").orNull()),
                "the literal the user typed is what should be stored");
    }

    // ── the think tool follows the setting ──────────────────────────────────

    @Test
    public void turningThinkingOffRemovesTheThinkTool() {
        NaruTask task = runScript("/settings model.thinking=false");
        assertFalse(toolNames(task).contains("think"),
                () -> "the think tool is an instruction to narrate reasoning; it must go too: " + toolNames(task));
    }

    /**
     * The other half -- {@code think:false} actually reaching the request body -- is
     * {@code NaruOllamaThinkSettingWireTest}, which lives in the ollama package
     * because {@code preprocessRequest} is protected.
     */
    @Test
    public void theSettingIsRecordedWhereAProtocolCanReadIt() {
        NaruTask task = runScript("/settings model.thinking=false");
        assertFalse(NaruThinkingConfig.find(task).orElse(true),
                "a protocol resolves this on every request, so it has to survive as a setting "
                        + "and not only as a side effect on the tool list");
    }
}