package net.thevpc.naru.modules.title;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruWorkspace;
import net.thevpc.naru.api.model.NaruCachingMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelCapabilities;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelProvider;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.task.NaruTaskSpec;
import net.thevpc.naru.api.title.NaruSessionTitler;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.naru.impl.engine.scheduler.NaruTaskImpl;
import net.thevpc.naru.impl.interaction.NaruStreamInteraction;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NOptional;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * The harness the titling tests share: a session with a scripted title model, and the main
 * task a front end would have.
 *
 * <p>Kept out of the test classes themselves because three of them need the same offline
 * session, and a copy per class drifts -- the one that drifts is the one that stops
 * describing what the production path actually does.
 */
final class TitlingHarness {

    static final String PROVIDER = "fake";
    static final String MODEL = "titling-probe";
    static final String ANSWER = "Resizing an LVM partition";

    private TitlingHarness() {
    }

    /** A provider that hands back a canned title, optionally after a delay, or fails. */
    static final class Probe implements NaruModelProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile String answer = ANSWER;
        volatile boolean fail;
        volatile long delayMillis;
        volatile boolean answered;

        @Override
        public NOptional<NaruModelProtocol> getProtocol(NaruModelConfig model, NaruSession session) {
            return NOptional.of(new NaruModelProtocol() {
                @Override
                public NaruResponse chat(NaruModelRequest request, NaruTask task) {
                    calls.incrementAndGet();
                    if (delayMillis > 0) {
                        try {
                            Thread.sleep(delayMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (fail) {
                        throw new IllegalStateException("provider is down");
                    }
                    answered = true;
                    NaruResponse response = new NaruResponse();
                    response.setMessage(NaruMessage.assistant(answer));
                    return response;
                }

                @Override
                public NaruModelCapabilities getCapabilities() {
                    return Capabilities.INSTANCE;
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
            return List.of(MODEL);
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

    private enum Capabilities implements NaruModelCapabilities {
        INSTANCE;

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
    }

    static NPath tempProject() {
        return NPath.ofTempFolder("naru-titling-" + System.nanoTime());
    }

    /** An offline session, with the probe as its only model and titling on or off. */
    static NaruSessionImpl session(NPath projectDir, Probe provider, boolean hostWantsTitles) {
        return session(projectDir, provider, hostWantsTitles, null);
    }

    /**
     * The same, addressed by id: a host that restores the same conversation across two
     * processes names it with {@code id()}, and that is how a restart is tested.
     *
     * <p>The host's choice is the {@code session.title.auto} setting rather than a builder
     * call: the kernel stopped knowing that titling exists, so the opt-out is a setting the
     * titling extension reads, exactly like the one a person would set in a config file.
     */
    static NaruSessionImpl session(NPath projectDir, Probe provider, boolean hostWantsTitles, String id) {
        NaruWorkspace workspace = NaruWorkspace.of();
        workspace.projectDirectory(projectDir);
        NaruSessionImpl session = (NaruSessionImpl) workspace.newSession()
                .sessionDir(projectDir)
                .id(id)
                .scope(net.thevpc.naru.api.store.NaruSessionScope.PRIVATE)
                .interaction(new NaruStreamInteraction(o -> {
                }))
                .configureDefaults(false)
                .sessionListener(null)
                .directiveFilter(null)
                .toolFilter(null)
                .tagFilter(null)
                .build();
        // Registered by hand rather than discovered: these sessions are built with
        // configureDefaults(false), so the assembly the test performs is explicit -- which is
        // also how an embedding host declares its own feature set.
        session.registry().registerSessionExtension(new NaruTitleExtension());
        session.setProjectEnv(NaruSessionTitler.AUTO_CONFIG_KEY,
                NElement.ofBoolean(hostWantsTitles), net.thevpc.naru.api.agent.NaruVisibility.PRIVATE);
        session.registry().registerModelProvider(provider);
        return session;
    }

    /** The titler this session has, which is how every caller reaches the feature now. */
    static NaruSessionTitler titler(NaruSession session) {
        NaruSessionTitler titler = session.registry()
                .extension(NaruSessionTitler.EXTENSION, NaruSessionTitler.class).orNull();
        if (titler == null) {
            throw new IllegalStateException("the titling extension is not on the test classpath");
        }
        return titler;
    }

    /**
     * Reach the boundary the automatic trigger lives on: the engine calls
     * {@code beforeModelRequest} before every request, so a test that wants a title appends
     * the message and then asks the hook what it would do.
     */
    static void modelRequest(NaruSession session, NaruTask task) {
        titler(session).beforeModelRequest(task);
    }

    /** The main task, as a front end would have it: foreground and talking through a model. */
    static NaruTask mainTask(NaruSessionImpl session) {
        NaruTaskImpl task = (NaruTaskImpl) session.newTask(NaruTaskSpec.of());
        task.setModel(new NaruModelConfig(PROVIDER, MODEL));
        // fg() is how a host marks the task the user is talking through; the trigger is
        // deliberately limited to that task, so a background child never renames the session
        task.fg();
        return task;
    }

    /**
     * The conversation task without the foreground mark, which is how a restored session reaches
     * the engine: the desktop answers the parked task's input request through
     * {@code NaruInputRequest.deliver} and never points the session at that task.
     */
    static NaruTask unmarkedTask(NaruSessionImpl session) {
        NaruTaskImpl task = (NaruTaskImpl) session.newTask(NaruTaskSpec.of());
        task.setModel(new NaruModelConfig(PROVIDER, MODEL));
        return task;
    }

    static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 8000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for the titling worker");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /**
     * A negative wait: nothing may happen for this long. Short, because the worker is already
     * running by then -- a call that was going to be made is made within milliseconds.
     */
    static void assertQuietlyNothing() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
