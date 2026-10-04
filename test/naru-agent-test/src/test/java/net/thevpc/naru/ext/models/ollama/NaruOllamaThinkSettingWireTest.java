package net.thevpc.naru.ext.models.ollama;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.ext.models.NaruModelCapabilitiesImpl;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code model.thinking=false} as Ollama receives it.
 *
 * <p>Lives in the ollama package because {@code preprocessRequest} is protected, and
 * it is a separate class from the settings test because the other half of "thinking
 * is off" -- the {@code think} tool leaving the schema -- is not an ollama concern.
 *
 * <p>The protocol here is built with thinking <em>declared</em>, so the capability
 * fallback would ask for reasoning. That is the point: only the setting can turn it
 * off, so a test that did not pin the capability would pass with the setting ignored.
 */
public class NaruOllamaThinkSettingWireTest {

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

    /**
 * Runs a script and hands back its task, with the session still alive.
     *
     * <p>The shape is the documented embedding one: a resident interactive task that
     * never terminates, plus the request task. A session stops itself when its last
     * task ends, and a stopped session refuses the registry and env reads these
     * assertions depend on. The resident task must answer input without ever
     * replying, or it is cancelled as a leak.
     */
    private static NaruTask runScript(String... statements) {
        NaruAgent agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-think-wire"));
        NaruSession session = agent.newSession()
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

    private static NaruModelProtocolOllamaNative reasoningProtocol(NaruSession session) {
        NaruModelProvider provider = session.registry().provider("ollama").orElseThrow(
                () -> new IllegalStateException("the ollama provider is not registered"));
        NaruModelCapabilities capabilities = new NaruModelCapabilitiesImpl(
                false, true, true, false, 8192, NaruCachingMode.NONE);
        return new NaruModelProtocolOllamaNative(provider,
                new NaruModelConfig("ollama", "qwen3"), "ollama", capabilities);
    }

    /** The {@code think} field a request for this task would actually carry. */
    private static NElement wireBody(NaruTask task) {
        NaruModelRequest prepared = reasoningProtocol(task.session())
                .preprocessRequest(new NaruModelRequest(
                        List.of(NaruMessage.user("hi")), Collections.emptyList(),
                        new HashMap<>()), task);
        return new NaruOllamaNativeRequestSerializer()
                .serialize(prepared, new NaruModelConfig("ollama", "qwen3"), task.session(), true);
    }

    private static boolean thinkOnTheWire(NaruTask task) {
        return wireBody(task).asObject().get().getBooleanValue("think").orElse(true);
    }

    @Test
    public void anUnsetSettingLeavesTheCapabilityToDecide() {
        assertTrue(thinkOnTheWire(runScript("/print 1")),
                "a reasoning model must still be asked to reason when nothing said otherwise");
    }

    @Test
    public void turningThinkingOffIsSentAsFalse() {
        assertTrue(wireBody(runScript("/settings model.thinking=false"))
                        .asObject().get().get("think").isPresent(),
                "false must be sent explicitly: leaving the field out means \"no preference\", not \"do not\"");
        assertFalse(thinkOnTheWire(runScript("/settings model.thinking=false")),
                "the setting has to reach the request body, not just the tool list");
    }

    @Test
    public void turningThinkingBackOnAsksForReasoningAgain() {
        assertTrue(thinkOnTheWire(runScript(
                "/settings model.thinking=false",
                "/settings model.thinking=true")),
                "re-enabling has to work, or the setting is a one-way door");
    }

    @Test
    public void aNonBooleanSettingFallsBackToTheCapability() {
        assertTrue(thinkOnTheWire(runScript("/settings model.thinking=maybe")),
                "an unparseable value must leave the capability in charge rather than guess false");
    }
}