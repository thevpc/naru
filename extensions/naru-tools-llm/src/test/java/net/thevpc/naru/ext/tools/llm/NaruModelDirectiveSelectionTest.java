package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelInfo;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruRegistry;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code /model} resolution rules. A full model key such as
 * {@code ollama/qwen2.5-coder:7b} is what {@code /model} prints and what users paste
 * back, so it must select that model; a fuzzy keyword must keep listing.
 */
public class NaruModelDirectiveSelectionTest {

    private static final String PROVIDER = "ollama";
    private static final String MODEL = "qwen2.5-coder:7b";
    private static final String KEY = PROVIDER + "/" + MODEL;

    /**
     * {@link NaruModelConfig#toText()} is what the directive logs, and it is not part of
     * the api module's test fixtures, so the infos are built with the plain api types.
     */
    private static NaruModelCapabilities caps() {
        return new NaruModelCapabilities() {
            @Override
            public long contextLength() {
                return 32768;
            }

            @Override
            public boolean isVision() {
                return false;
            }

            @Override
            public boolean isTools() {
                return true;
            }

            @Override
            public boolean isThinking() {
                return false;
            }

            @Override
            public boolean isEmbedding() {
                return false;
            }

            @Override
            public boolean isTextOnly() {
                return true;
            }

            @Override
            public NaruCachingMode cachingMode() {
                return NaruCachingMode.NONE;
            }

            @Override
            public Set<String> keys() {
                return java.util.Collections.emptySet();
            }

            @Override
            public NElement toElement() {
                return NElement.ofObjectBuilder().build();
            }

            @Override
            public String toString() {
                return "caps";
            }
        };
    }

    private static NaruModelInfo info() {
        return new NaruModelInfo(PROVIDER, MODEL, caps());
    }

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

    /**
     * @return the proxy task, whose {@code model()} reflects whatever the directive
     * selected, and the messages the directive logged
     */
    @SuppressWarnings("unchecked")
    private static Harness buildTask(NaruDirectiveCallContext[] outContext) {
        List<NaruModelInfo> catalog = new ArrayList<>(Arrays.asList(info()));
        Map<String, NaruModelConfig> aliases = new LinkedHashMap<>();
        List<String> listed = new ArrayList<>();
        NaruModelConfig[] selected = {null};
        List<net.thevpc.nuts.text.NMsg> logs = new ArrayList<>();

        NaruAgent agent = (NaruAgent) Proxy.newProxyInstance(
                NaruModelDirectiveSelectionTest.class.getClassLoader(),
                new Class<?>[]{NaruAgent.class},
                (proxy, method, args) -> "env".equals(method.getName())
                        ? new NaruEnv() {
                            @Override
                            public NOptional<NElement> get(String key) {
                                return NOptional.ofEmpty();
                            }

                            @Override
                            public NOptional<NElement> get(String key, NaruVisibility visibility) {
                                return NOptional.ofEmpty();
                            }

                            @Override
                            public void put(String key, NElement value, NaruVisibility visibility) {
                            }
                        }
                        : null);

        NaruRegistry registry = (NaruRegistry) Proxy.newProxyInstance(
                NaruModelDirectiveSelectionTest.class.getClassLoader(),
                new Class<?>[]{NaruRegistry.class},
                (proxy, method, args) -> "modelsInfos".equals(method.getName()) ? catalog : null);

        NaruSession session = (NaruSession) Proxy.newProxyInstance(
                NaruModelDirectiveSelectionTest.class.getClassLoader(),
                new Class<?>[]{NaruSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "agent":
                            return agent;
                        case "registry":
                            return registry;
                        case "modelAliases":
                            return aliases;
                        case "setListedModels":
                            listed.clear();
                            listed.addAll((List<String>) args[0]);
                            return null;
                        case "listedModels":
                            return listed;
                        case "findModel": {
                            // only an exact key resolves, mirroring the real registry
                            String s = String.valueOf(args[0]);
                            for (NaruModelInfo mi : catalog) {
                                if (mi.key().toString().equals(s)) {
                                    return NOptional.of(new NaruModelConfig(mi.provider(), mi.model()));
                                }
                            }
                            return NOptional.ofEmpty();
                        }
                        default:
                            return null;
                    }
                });

        NaruTask[] taskRef = new NaruTask[1];
        NaruTask task = (NaruTask) Proxy.newProxyInstance(
                NaruModelDirectiveSelectionTest.class.getClassLoader(),
                new Class<?>[]{NaruTask.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "session":
                            return session;
                        case "model":
                            return selected[0];
                        case "setModel":
                            selected[0] = (NaruModelConfig) args[0];
                            return taskRef[0];
                        case "log":
                            for (Object a : args) {
                                if (a instanceof net.thevpc.nuts.text.NMsg) {
                                    logs.add((net.thevpc.nuts.text.NMsg) a);
                                }
                            }
                            return null;
                        default:
                            return null;
                    }
                });
        taskRef[0] = task;

        outContext[0] = (NaruDirectiveCallContext) Proxy.newProxyInstance(
                NaruModelDirectiveSelectionTest.class.getClassLoader(),
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
        return new Harness(task, logs);
    }

    /** A proxy task plus the messages the directive logged to it. */
    private static class Harness {
        final NaruTask task;
        final List<net.thevpc.nuts.text.NMsg> logs;

        Harness(NaruTask task, List<net.thevpc.nuts.text.NMsg> logs) {
            this.task = task;
            this.logs = logs;
        }

        String logged() {
            StringBuilder sb = new StringBuilder();
            for (net.thevpc.nuts.text.NMsg m : logs) {
                sb.append(m.toString()).append('\n');
            }
            return sb.toString();
        }
    }

    @Test
    public void testFullKeySelectsModel() {
        NaruDirectiveCallContext[] ctx = new NaruDirectiveCallContext[1];
        Harness h = buildTask(ctx);

        NaruStmtResult res = new NaruModelDirective()
                .executeList(ctx[0], NCmdLine.of(KEY));

        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));
        Assertions.assertNotNull(h.task.model(),
                "/model <full-key> must select the model, not just list it");
        Assertions.assertEquals(KEY, h.task.model().key().toString());
    }

    @Test
    public void testProviderSlashModelFormSelectsModel() {
        NaruDirectiveCallContext[] ctx = new NaruDirectiveCallContext[1];
        Harness h = buildTask(ctx);

        NaruStmtResult res = new NaruModelDirective()
                .executeList(ctx[0], NCmdLine.of("ollama/qwen2.5-coder:7b"));

        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));
        Assertions.assertNotNull(h.task.model());
    }

    @Test
    public void testFuzzyFilterStillListsAndDoesNotSelect() {
        NaruDirectiveCallContext[] ctx = new NaruDirectiveCallContext[1];
        Harness h = buildTask(ctx);

        NaruStmtResult res = new NaruModelDirective()
                .executeList(ctx[0], NCmdLine.of("coder"));

        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));
        Assertions.assertNull(h.task.model(),
                "a fuzzy keyword must keep listing rather than silently switching models");
        Assertions.assertTrue(h.logged().contains("Available models"),
                () -> "expected a listing, got: " + h.logged());
    }

    @Test
    public void testUnknownKeyStillReportsNoMatch() {
        NaruDirectiveCallContext[] ctx = new NaruDirectiveCallContext[1];
        Harness h = buildTask(ctx);

        NaruStmtResult res = new NaruModelDirective()
                .executeList(ctx[0], NCmdLine.of("ollama/no-such-model:1b"));

        Assertions.assertEquals(NaruStmtResultType.SUCCESS, res.type(), () -> String.valueOf(res));
        Assertions.assertNull(h.task.model());
        Assertions.assertTrue(h.logged().contains("No available models found"),
                () -> "expected a no-match report, got: " + h.logged());
    }
}
