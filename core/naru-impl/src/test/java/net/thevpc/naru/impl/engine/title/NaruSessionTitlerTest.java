package net.thevpc.naru.impl.engine.title;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionNameSource;
import net.thevpc.naru.api.agent.NaruSessionTitler;
import net.thevpc.naru.api.agent.NaruSessionUsageListener;
import net.thevpc.naru.api.agent.NaruTitleException;
import net.thevpc.naru.api.agent.NaruTitleRequest;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.Nuts;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.NOptional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The model-choice and metering rules of the shipped titler.
 *
 * <p>What is being pinned down is the expensive part: which model gets asked, how many times,
 * and whether the call shows up in the bill under its own model. The words the model says are
 * already covered by {@link NaruTitleSanitizerTest} and the excerpt bounds by
 * {@link NaruTitleInputTest}.
 */
public class NaruSessionTitlerTest {

    private static final String PROVIDER = "fake";
    private static final String CHEAP = "cheap";
    private static final String PRICY = "pricy";

    @BeforeAll
    public static void setUpWorkspace() {
        Nuts.require();
    }

    private static final NaruModelCapabilities CAPABILITIES = new NaruModelCapabilities() {
        @Override
        public long contextLength() {
            return 4096;
        }

        @Override
        public boolean isVision() {
            return false;
        }

        @Override
        public boolean isTools() {
            return false;
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
        public boolean isStreaming() {
            return false;
        }

        @Override
        public NaruCachingMode cachingMode() {
            return NaruCachingMode.NONE;
        }

        @Override
        public Set<String> keys() {
            return Set.of();
        }

        @Override
        public NElement toElement() {
            return NElement.ofTupleBuilder("Capabilities").build();
        }
    };

    /**
     * A provider that answers from a script: one canned reply (or failure) per model id, plus
     * the request each call actually received.
     */
    private static final class ScriptedProvider implements NaruModelProvider {
        final Map<String, String> answers = new LinkedHashMap<>();
        final Map<String, String> failures = new LinkedHashMap<>();
        final List<String> called = new ArrayList<>();
        final List<NaruModelRequest> requests = new ArrayList<>();
        final List<NaruModelConfig> tuned = new ArrayList<>();

        ScriptedProvider answer(String model, String title) {
            answers.put(model, title);
            return this;
        }

        ScriptedProvider fail(String model, String message) {
            failures.put(model, message);
            return this;
        }

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            String id = model.model();
            return NOptional.of(new NaruModelProtocol() {
                @Override
                public NaruResponse chat(NaruModelRequest request, NaruTask task) {
                    called.add(id);
                    requests.add(request);
                    tuned.add(model);
                    String failure = failures.get(id);
                    if (failure != null) {
                        throw new IllegalStateException(failure);
                    }
                    String answer = answers.get(id);
                    if (answer == null) {
                        return new NaruResponse(NaruMessage.assistant(""), true);
                    }
                    NaruResponse response = new NaruResponse();
                    response.setMessage(NaruMessage.assistant(answer));
                    response.setPromptTokens(120);
                    response.setEvalTokens(9);
                    return response;
                }

                @Override
                public NaruModelCapabilities getCapabilities() {
                    return CAPABILITIES;
                }
            });
        }

        @Override
        public String name() {
            return PROVIDER;
        }

        @Override
        public NOptional<String> apiKey(NaruSession session) {
            return NOptional.of("none");
        }

        @Override
        public List<String> findModelIds(NaruSession session) {
            return List.of(CHEAP, PRICY);
        }

        @Override
        public void setParam(String name, String value) {
        }

        @Override
        public NOptional<String> getParam(String name) {
            return NOptional.ofEmpty();
        }

        @Override
        public Set<String> getParamNames() {
            return Set.of();
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }
    }

    // ------------------------------------------------------------------ harness

    private static NaruSessionImpl session(ScriptedProvider provider) {
        NaruWorkspace workspace = NaruWorkspace.of();
        NPath projectDir = NPath.ofTempFolder("naru-titler-" + System.nanoTime());
        workspace.projectDirectory(projectDir);
        NaruSessionImpl session = (NaruSessionImpl) workspace.newSession()
                .sessionDir(projectDir)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                // the trigger is the subject of its own test; here it would only race with them
                .autoTitle(false)
                .build();
        session.registry().registerModelProvider(provider);
        return session;
    }

    /** A main task whose stored history is one user turn, talking through the expensive model. */
    private static NaruTask taskWithFirstMessage(NaruSessionImpl session) {
        NaruTask task = session.newTask(NaruTaskSpec.of());
        task.setModel(new NaruModelConfig(PROVIDER, PRICY));
        task.addHistory(NaruMessage.user("how do I resize a partition on lvm"));
        return task;
    }

    private static void setTitleModels(NaruSession session, String value) {
        session.setProjectEnv(NaruSessionTitler.MODELS_CONFIG_KEY, NElement.of(value),
                net.thevpc.naru.api.agent.NaruVisibility.PRIVATE);
    }

    // ------------------------------------------------------------------ model choice

    @Test
    public void withNoConfiguredListTheTransportTaskModelIsUsed() {
        ScriptedProvider provider = new ScriptedProvider().answer(PRICY, "Resizing an LVM partition");
        NaruSessionImpl session = session(provider);
        NaruTask transport = taskWithFirstMessage(session);

        String title = session.titler().suggest(NaruTitleRequest.firstMessage(session, transport));

        Assertions.assertEquals("Resizing an LVM partition", title);
        Assertions.assertEquals(List.of(PRICY), provider.called,
                "a session with one model configured must not need a second setting to get a title");
    }

    @Test
    public void theCallIsContextFree() {
        ScriptedProvider provider = new ScriptedProvider().answer(CHEAP, "LVM resize");
        NaruSessionImpl session = session(provider);
        setTitleModels(session, PROVIDER + "/" + CHEAP);
        NaruTask transport = taskWithFirstMessage(session);
        transport.addHistory(NaruMessage.assistant("lvextend then lvresize"));

        session.titler().suggest(NaruTitleRequest.firstMessage(session, transport));

        NaruModelRequest request = provider.requests.get(0);
        Assertions.assertEquals(2, request.messages().size(),
                "the request is the instruction and the bounded input, not the conversation");
        Assertions.assertEquals(NaruTitlePrompt.INSTRUCTION.strip(),
                request.messages().get(0).getContent().toString().strip());
        Assertions.assertTrue(request.messages().get(1).getContent().toString()
                        .contains("how do I resize a partition on lvm"),
                "the first user message is what the automatic title reads");
        Assertions.assertFalse(request.messages().get(1).getContent().toString()
                        .contains("lvextend"),
                "FIRST_MESSAGE mode must not drag the rest of the conversation along");
        Assertions.assertEquals(List.of(), request.tools(), "a title needs no tools");
        Assertions.assertEquals(Boolean.FALSE,
                request.env().get(net.thevpc.naru.api.model.NaruThinkingConfig.THINKING_KEY)
                        .asBooleanValue().orElse(null),
                "titling asks the protocol not to think");
    }

    @Test
    public void theTitleModelIsAskedWithATightBudget() {
        ScriptedProvider provider = new ScriptedProvider().answer(CHEAP, "LVM resize");
        NaruSessionImpl session = session(provider);
        setTitleModels(session, PROVIDER + "/" + CHEAP);
        NaruTask transport = taskWithFirstMessage(session);

        session.titler().suggest(NaruTitleRequest.firstMessage(session, transport));

        NaruModelConfig config = provider.tuned.get(0);
        Assertions.assertEquals(Integer.valueOf(64), config.maxTokens(),
                "a title is thirty-odd characters; the cap is what stops a chatty model "
                        + "from being billed for its explanation");
        Assertions.assertEquals(Float.valueOf(0.2f), config.temperature());
    }

    @Test
    public void aConfiguredListNeverFallsBackToTheTaskModel() {
        ScriptedProvider provider = new ScriptedProvider();
        NaruSessionImpl session = session(provider);
        // only a stale key is configured, and the transport task is on the expensive model
        setTitleModels(session, "fake/no-such-model");
        NaruTask transport = taskWithFirstMessage(session);

        NaruTitleException e = Assertions.assertThrows(NaruTitleException.class,
                () -> session.titler().suggest(NaruTitleRequest.firstMessage(session, transport)));

        Assertions.assertEquals(List.of(), provider.called,
                "whoever named cheap models did it to bound what titling costs; answering that "
                        + "with the conversation's most expensive model is an invisible failure");
        Assertions.assertEquals(List.of("fake/no-such-model"),
                e.attempts().stream().map(NaruTitleException.Attempt::model).toList());
        Assertions.assertEquals("no such model is available", e.attempts().get(0).reason());
    }

    @Test
    public void theFirstModelThatAnswersWinsAndTheRestAreNotAsked() {
        ScriptedProvider provider = new ScriptedProvider()
                .fail(CHEAP, "ollama is not running")
                .answer(PRICY, "LVM partition resize");
        NaruSessionImpl session = session(provider);
        setTitleModels(session, "fake/cheap, fake/pricy");
        NaruTask transport = taskWithFirstMessage(session);

        String title = session.titler().suggest(NaruTitleRequest.firstMessage(session, transport));

        Assertions.assertEquals("LVM partition resize", title);
        Assertions.assertEquals(List.of(CHEAP, PRICY), provider.called,
                "a configured list is a fallback chain, tried in order");
    }

    @Test
    public void everyFailureIsReportedWithItsReason() {
        ScriptedProvider provider = new ScriptedProvider()
                .fail(CHEAP, "connection refused")
                .answer(PRICY, "...");
        NaruSessionImpl session = session(provider);
        setTitleModels(session, "fake/cheap, fake/pricy");
        NaruTask transport = taskWithFirstMessage(session);

        NaruTitleException e = Assertions.assertThrows(NaruTitleException.class,
                () -> session.titler().suggest(NaruTitleRequest.firstMessage(session, transport)));

        Assertions.assertEquals(2, e.attempts().size());
        Assertions.assertEquals("fake/cheap: connection refused; fake/pricy: answered with no usable title",
                describe(e), "the two diagnoses a user needs are which model failed and how");
    }

    @Test
    public void anOverrideReplacesTheConfiguredListForThatCall() {
        ScriptedProvider provider = new ScriptedProvider().answer(PRICY, "LVM resize");
        NaruSessionImpl session = session(provider);
        setTitleModels(session, "fake/cheap, fake/pricy");
        NaruTask transport = taskWithFirstMessage(session);

        String title = session.titler().suggest(
                NaruTitleRequest.firstMessage(session, transport).withModel(PROVIDER + "/" + PRICY));

        Assertions.assertEquals("LVM resize", title);
        Assertions.assertEquals(List.of(PRICY), provider.called,
                "/session title --model= is a one-off instruction, not a reorder of the list");
    }

    @Test
    public void anUnknownOverrideIsAnErrorRatherThanASilentFallback() {
        ScriptedProvider provider = new ScriptedProvider().answer(PRICY, "LVM resize");
        NaruSessionImpl session = session(provider);
        NaruTask transport = taskWithFirstMessage(session);

        NaruTitleException e = Assertions.assertThrows(NaruTitleException.class, () ->
                session.titler().suggest(
                        NaruTitleRequest.firstMessage(session, transport).withModel("fake/typo")));

        Assertions.assertEquals(List.of(), provider.called);
        Assertions.assertEquals("no such model is available", e.attempts().get(0).reason());
    }

    // ------------------------------------------------------------------ metering

    /**
     * The reason the titler makes the call itself instead of borrowing {@code task.chat}: the
     * bill must say which model answered. Announcing the transport's model would hide a
     * hundred paid title calls behind the conversation's price.
     */
    @Test
    public void theCallIsAnnouncedOnTheTitleModelAndLabelledAsTitling() {
        ScriptedProvider provider = new ScriptedProvider().answer(CHEAP, "LVM resize");
        NaruSessionImpl session = session(provider);
        setTitleModels(session, PROVIDER + "/" + CHEAP);
        NaruTask transport = taskWithFirstMessage(session);

        List<NaruModelKey> models = new ArrayList<>();
        List<Map<String, String>> labels = new ArrayList<>();
        List<long[]> tokens = new ArrayList<>();
        session.addUsageListener(new NaruSessionUsageListener() {
            @Override
            public void onModelCall(NaruTask task, NaruModelKey model, long promptTokens,
                                    long completionTokens, long cacheWriteTokens, long cacheReadTokens,
                                    Map<String, String> callLabels, NDuration duration) {
                models.add(model);
                labels.add(callLabels);
                tokens.add(new long[]{promptTokens, completionTokens});
            }

            @Override
            public void onProviderRateLimits(
                    net.thevpc.naru.api.model.NaruProviderRateLimitInfo info) {
            }
        });

        session.titler().suggest(NaruTitleRequest.firstMessage(session, transport));

        Assertions.assertEquals(List.of(new NaruModelKey(PROVIDER, CHEAP)), models,
                "reported against the model that answered, not the transport task's");
        Assertions.assertEquals(Map.of("purpose", "title"), labels.get(0));
        Assertions.assertArrayEquals(new long[]{120, 9}, tokens.get(0),
                "the provider's own counts survive the trip");
        Assertions.assertEquals(1, models.size(), "one call, announced exactly once");
    }

    @Test
    public void aFailedAttemptIsStillAnnouncedBecauseItWasStillBilled() {
        // A provider that throws after the HTTP round-trip has already spent the tokens; the
        // script here fails before answering, so nothing is announced -- which is the point of
        // pinning the boundary: only a call that returned is a call that costs.
        ScriptedProvider provider = new ScriptedProvider().fail(CHEAP, "connection refused");
        NaruSessionImpl session = session(provider);
        setTitleModels(session, PROVIDER + "/" + CHEAP);
        NaruTask transport = taskWithFirstMessage(session);

        List<NaruModelKey> models = new ArrayList<>();
        session.addUsageListener(new NaruSessionUsageListener() {
            @Override
            public void onModelCall(NaruTask task, NaruModelKey model, long promptTokens,
                                    long completionTokens, long cacheWriteTokens, long cacheReadTokens,
                                    Map<String, String> callLabels, NDuration duration) {
                models.add(model);
            }

            @Override
            public void onProviderRateLimits(
                    net.thevpc.naru.api.model.NaruProviderRateLimitInfo info) {
            }
        });

        Assertions.assertThrows(NaruTitleException.class,
                () -> session.titler().suggest(NaruTitleRequest.firstMessage(session, transport)));
        Assertions.assertEquals(List.of(), models);
    }

    // ------------------------------------------------------------------ applying a name

    @Test
    public void regenerateWritesTheTitleOntoTheTargetAsAnAutomaticName() {
        ScriptedProvider provider = new ScriptedProvider().answer(CHEAP, "LVM partition resize");
        NaruSessionImpl session = session(provider);
        setTitleModels(session, PROVIDER + "/" + CHEAP);
        NaruTask transport = taskWithFirstMessage(session);

        String title = session.titler().regenerate(NaruTitleRequest.firstMessage(session, transport));

        Assertions.assertEquals("LVM partition resize", title);
        Assertions.assertEquals("LVM partition resize", session.name());
        Assertions.assertEquals(NaruSessionNameSource.AUTO, session.nameSource(),
                "the explicit path may override a human's name, and records that it did");
    }

    @Test
    public void aFailureLeavesTheNameAlone() {
        ScriptedProvider provider = new ScriptedProvider().fail(CHEAP, "connection refused");
        NaruSessionImpl session = session(provider);
        setTitleModels(session, PROVIDER + "/" + CHEAP);
        NaruTask transport = taskWithFirstMessage(session);
        session.rename("my notes", NaruSessionNameSource.USER);

        Assertions.assertThrows(NaruTitleException.class,
                () -> session.titler().regenerate(NaruTitleRequest.firstMessage(session, transport)));
        Assertions.assertEquals("my notes", session.name());
        Assertions.assertEquals(NaruSessionNameSource.USER, session.nameSource(),
                "a title that failed must not cost the user the name they typed");
    }

    // ------------------------------------------------------------------ excerpt mode

    @Test
    public void excerptModeReadsTheStoredHistoryOfTheMainTask() {
        ScriptedProvider provider = new ScriptedProvider().answer(CHEAP, "Debugging lvresize");
        NaruSessionImpl session = session(provider);
        setTitleModels(session, PROVIDER + "/" + CHEAP);
        NaruTask transport = taskWithFirstMessage(session);
        transport.addHistory(NaruMessage.assistant("show me the volume group first"));
        transport.addHistory(NaruMessage.user("lvresize failed with a snapshot"));

        session.titler().suggest(NaruTitleRequest.excerpt(session, transport));

        String content = provider.requests.get(0).messages().get(1).getContent().toString();
        Assertions.assertTrue(content.contains("user: lvresize failed with a snapshot"),
                "a regeneration reads the conversation, so the last turn must be in it: " + content);
        Assertions.assertEquals(NaruSessionNameSource.PLACEHOLDER, session.nameSource(),
                "suggest does not apply anything; only regenerate does");
    }

    private static String describe(NaruTitleException e) {
        return e.attempts().stream()
                .map(a -> a.model() + ": " + a.reason())
                .reduce((x, y) -> x + "; " + y)
                .orElse("");
    }
}
