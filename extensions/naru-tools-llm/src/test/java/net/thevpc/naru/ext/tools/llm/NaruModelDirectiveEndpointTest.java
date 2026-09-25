package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruEnv;
import net.thevpc.naru.api.agent.NAruVisibility;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.routine.NaruStmtResultType;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.core.NWorkspace;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exercises the {@code /model endpoint add|list|remove} directive against a
 * proxy-based session whose env is fully in memory — no files, no network.
 */
public class NaruModelDirectiveEndpointTest {

    private final Map<String, NElement> publicStore = new LinkedHashMap<>();
    private final Map<String, NElement> privateStore = new LinkedHashMap<>();
    private final NaruDirectiveCallContext context = buildContext();

    @BeforeAll
    public static void setUp() {
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

    private NaruDirectiveCallContext buildContext() {
        NaruEnv env = new NaruEnv() {
            @Override
            public NOptional<NElement> get(String key) {
                if (privateStore.containsKey(key)) {
                    return NOptional.of(privateStore.get(key));
                }
                if (publicStore.containsKey(key)) {
                    return NOptional.of(publicStore.get(key));
                }
                return NOptional.ofEmpty();
            }

            @Override
            public void put(String key, NElement value, NAruVisibility visibility) {
                if (value == null) {
                    publicStore.remove(key);
                    privateStore.remove(key);
                } else if (visibility == NAruVisibility.PRIVATE) {
                    privateStore.put(key, value);
                } else {
                    publicStore.put(key, value);
                }
            }
        };
        NaruAgent agent = (NaruAgent) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruAgent.class},
                (proxy, method, args) -> "env".equals(method.getName()) ? env : null);
        NaruSession session = (NaruSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "agent":
                            return agent;
                        case "setProjectEnv":
                            env.put((String) args[0], (NElement) args[1], (NAruVisibility) args[2]);
                            return null;
                        case "getProjectEnv":
                            return env.get((String) args[0]);
                        case "findModel":
                            return NOptional.ofEmpty();
                        default:
                            return null;
                    }
                });
        NaruTask task = (NaruTask) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> "session".equals(method.getName()) ? session : null);
        return (NaruDirectiveCallContext) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{NaruDirectiveCallContext.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "task":
                            return task;
                        case "name":
                            return "model";
                        case "argument":
                            return "";
                        default:
                            return null;
                    }
                });
    }

    @Test
    public void testEndpointAddListRemoveRoundTrip() {
        NaruModelDirective directive = new NaruModelDirective();

        // add
        NaruStmtResult add = directive.executeEndpoint(context, NCmdLine.of("add my-llm --url=http://127.0.0.1:9999 --type=openapi --models=a,b --apiKey=secret --probe=false"));
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, add.type(), () -> String.valueOf(add));
        Assertions.assertEquals("my-llm", publicStore.get("custom.endpoints").asStringValue().get());
        Assertions.assertEquals("http://127.0.0.1:9999", publicStore.get("custom.endpoints.my-llm.url").asStringValue().get());
        Assertions.assertEquals("secret", privateStore.get("custom.endpoints.my-llm.apiKey").asStringValue().get());
        Assertions.assertFalse(publicStore.get("custom.endpoints.my-llm.probe").asBooleanValue().get());
        Assertions.assertNull(privateStore.get("custom.endpoints.my-llm.url"));

        // list
        NaruStmtResult list = directive.executeEndpoint(context, NCmdLine.of("list"));
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, list.type(), () -> String.valueOf(list));

        // remove
        NaruStmtResult remove = directive.executeEndpoint(context, NCmdLine.of("remove my-llm"));
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, remove.type(), () -> String.valueOf(remove));
        Assertions.assertFalse(publicStore.containsKey("custom.endpoints.my-llm.url"));
        Assertions.assertFalse(privateStore.containsKey("custom.endpoints.my-llm.apiKey"));
        // empty endpoint list is persisted as an empty string
        Assertions.assertEquals("", publicStore.get("custom.endpoints").asStringValue().get());
    }

    @Test
    public void testEndpointRemoveUnknownFails() {
        NaruModelDirective directive = new NaruModelDirective();
        NaruStmtResult remove = directive.executeEndpoint(context, NCmdLine.of("remove nope"));
        Assertions.assertEquals(NaruStmtResultType.ERROR, remove.type());
    }

    @Test
    public void testEndpointUnknownOperationFails() {
        NaruModelDirective directive = new NaruModelDirective();
        NaruStmtResult res = directive.executeEndpoint(context, NCmdLine.of("frobnicate"));
        Assertions.assertEquals(NaruStmtResultType.ERROR, res.type());
    }

    @Test
    public void testAnthropicTypeEndpoint() {
        NaruModelDirective directive = new NaruModelDirective();
        NaruStmtResult add = directive.executeEndpoint(context, NCmdLine.of("add claude --url=https://api.anthropic.com --type=anthropic --models=claude-sonnet-4"));
        Assertions.assertEquals(NaruStmtResultType.SUCCESS, add.type(), () -> String.valueOf(add));
        Assertions.assertEquals("anthropic", publicStore.get("custom.endpoints.claude.type").asStringValue().get());
        Assertions.assertEquals("claude-sonnet-4", publicStore.get("custom.endpoints.claude.models").asStringValue().get());
    }
}