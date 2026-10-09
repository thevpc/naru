package net.thevpc.naru.agent.spawn;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionListener;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.scheduler.NaruEvent;
import net.thevpc.naru.api.spawn.NaruSpawnPolicy;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.ext.tools.llm.ModelDelegateTool;
import net.thevpc.naru.impl.engine.NaruAgentImpl;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.io.NPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model-initiated spawn path ({@code delegate_to_model} with a {@code routine}) is
 * deliberately narrower than the call-site path: it can revoke tags and inherit less, but it
 * may never add a tag. These pin both halves — the tool schema exposes no add/grant
 * parameter at all, and a narrowing spawn resolves to exactly the parent's set minus the
 * revoked tags, with the named policy coming from the session configuration rather than the
 * call.
 */
@Timeout(60)
public class NaruDelegateSpawnTest {

    private static final NaruSessionListener NOOP_LISTENER = new NaruSessionListener() {
        @Override
        public void onEventAppended(NaruEvent newEvent) {
        }

        @Override
        public void sessionStarted(NaruSession session) {
        }

        @Override
        public void sessionStopped(NaruSession session) {
        }

        @Override
        public void onSessionReloaded(NaruSession naruSession) {
        }
    };

    private NaruAgent agent;
    private NaruSessionImpl session;

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
        agent = new NaruAgentImpl();
        agent.projectDirectory(NPath.ofTempFolder("naru-delegate-spawn"));
        session = new NaruSessionImpl(agent, agent.projectDirectory(), null, true,
                NOOP_LISTENER, null, null, null);
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

    private NaruTask newTask() {
        return session.newTask(NaruTaskSpec.of());
    }

    private void writeRoutine(String name, String... body) {
        StringBuilder sb = new StringBuilder();
        for (String line : body) {
            sb.append(line).append('\n');
        }
        NPath f = agent.projectDirectory().resolve(name);
        f.mkParentDirs();
        f.writeString(sb.toString());
    }

    private NaruEvent lastSpawned(long fromSeq) {
        return session.eventLog().scan(fromSeq, e -> NaruEvent.TASK_SPAWNED.equals(e.name()))
                .stream().reduce((a, b) -> b)
                .orElseThrow(() -> new AssertionError("no TaskSpawned event after seq " + fromSeq));
    }

    @Test
    public void theToolSchemaExposesNoTagAddParameter() {
        NaruTask task = newTask();
        NaruToolDefinition def = new ModelDelegateTool().getDefinition(task);
        List<String> params = ((NaruToolDefinitionFunction) def).getParams().stream()
                .map(NaruToolParameter::getName).collect(Collectors.toList());

        assertEquals(List.of("model_name", "prompt", "image_path", "routine", "revoke_tags", "inherit"),
                params, "the model-visible spawn parameters changed");
        assertTrue(params.contains("revoke_tags"), "the model may only narrow");
        assertFalse(params.stream().anyMatch(p -> p.contains("add") || p.contains("grant")
                        || p.contains("tags") && !p.equals("revoke_tags")),
                () -> "the model path must expose no way to add tags: " + params);
    }

    @Test
    public void modelPathRevokesTagsButNeverAddsAny() {
        writeRoutine("probe.naru", "/return 42");
        NaruTask parent = newTask();
        parent.addToolTag("write").addToolTag("exec");
        session.start();
        long fromSeq = session.eventLog().currentSeq() + 1;

        String reply = ModelDelegateTool.callModel(parent, null, "do the thing", null,
                "probe.naru", "exec", "tags");

        assertTrue(reply.contains("42"), () -> "the child result must come back: " + reply);
        NaruEvent spawned = lastSpawned(fromSeq);
        String tags = String.valueOf(spawned.payload().get("tags"));
        assertTrue(tags.startsWith("[write]"),
                () -> "the model path narrowed to exactly the parent tags minus exec: " + tags);
        // no tag appeared that the model did not get from the parent
        assertFalse(tags.startsWith("[write,exec]"), tags);
    }

    @Test
    public void modelPathPolicyComesFromTheSessionEnvNotTheCall() {
        writeRoutine("probe.naru", "/return 7");
        NaruTask parent = newTask();
        parent.addToolTag("write").addToolTag("exec");
        session.defineSpawnPolicy(new NaruSpawnPolicy("review-safe")
                .inherit(net.thevpc.naru.api.spawn.NaruSpawnInherit.TAGS)
                .revokeTags("exec"));
        parent.setTaskEnv(NaruSpawnPolicy.ENV_CONFIG_POLICY, "review-safe");
        session.start();
        long fromSeq = session.eventLog().currentSeq() + 1;

        ModelDelegateTool.callModel(parent, null, "go", null, "probe.naru", null, null);

        NaruEvent spawned = lastSpawned(fromSeq);
        assertEquals("review-safe", spawned.payload().get("policy"),
                "the configured session policy must be applied by the model path");
        assertEquals("[write]", String.valueOf(spawned.payload().get("tags")).split(" ")[0],
                () -> "the policy revoke must have removed exec: " + spawned.payload().get("tags"));
    }

    @Test
    public void modelPathRejectsAMissingTarget() {
        NaruTask parent = newTask();
        String reply = ModelDelegateTool.callModel(parent, null, "go", null,
                "no-such-routine.naru", null, null);
        assertTrue(reply.startsWith("Error:"), () -> "a missing target must be reported: " + reply);
    }
}
