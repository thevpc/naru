package net.thevpc.naru.modules.title;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionNameSource;
import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruThinkingConfig;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.title.NaruSessionTitler;
import net.thevpc.naru.api.title.NaruTitleException;
import net.thevpc.naru.api.title.NaruTitleException.Attempt;
import net.thevpc.naru.api.title.NaruTitleMode;
import net.thevpc.naru.api.title.NaruTitleRequest;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.mon.NChronometer;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NStringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The titler the default build ships: one session extension that both names a session and
 * decides when to do it on its own.
 *
 * <h2>Why the call does not go through {@code task.chat}</h2>
 * The transport task is the user's conversation. {@code task.chat} is the metered path, but
 * it is also the path that streams the answer into the transcript, moves the task's
 * interrupt and "already streamed" flags from another thread, and reports the usage against
 * the task's own model rather than the one being called. A title is none of those things.
 * So this class calls the protocol the way {@code chat} does, and then announces the usage
 * itself through the same metering seam {@code chat} announces on, with the right model key
 * and the {@code title} label. Skipping the transcript is the point; skipping the metering
 * would be a bug, so it is not skipped.
 *
 * <h2>State</h2>
 * Two things only, and both belong to a session rather than to a call: how many automatic
 * attempts this session has spent, and whether one is in flight. The first is persisted
 * through {@link #save(NaruSession)} -- the extension's own state slot in the session store,
 * not a column of the session header, because the kernel has no reason to know that titling
 * counts anything.
 */
public class NaruTitleExtension implements NaruSessionTitler {

    /** A title is thirty-odd characters; anything after that is the model explaining itself. */
    private static final int MAX_TITLE_TOKENS = 64;

    /** Titles are not creative work; a wandering sampler produces worse ones, not better. */
    private static final float TITLE_TEMPERATURE = 0.2f;

    /**
     * The label every titling call carries. Reported on the call rather than on a task
     * because a titling call has no task of its own, and a {@code /budget} report that
     * cannot separate titling from conversation cannot price it.
     */
    public static final String USAGE_LABEL_KEY = "purpose";
    public static final String USAGE_LABEL_VALUE = "title";

    private volatile NaruSession session;
    private final Object titlingLock = new Object();
    private int titleAttempts;
    private boolean titlingInFlight;
    private volatile ExecutorService titlingExecutor;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void open(NaruSession session) {
        this.session = session;
    }

    @Override
    public NElement save(NaruSession session) {
        int attempts;
        synchronized (titlingLock) {
            attempts = titleAttempts;
        }
        if (attempts == 0) {
            // null tells the core to drop this extension's state file entirely
            return null;
        }
        return NElement.ofObjectBuilder().set("attempts", attempts).build();
    }

    @Override
    public void load(NaruSession session, NElement state) {
        int attempts = state == null ? 0 : state.asObject()
                .map(o -> o.getIntValue("attempts").orElse(0)).orElse(0);
        synchronized (titlingLock) {
            titleAttempts = attempts;
        }
    }

    @Override
    public void close() {
        ExecutorService executor = titlingExecutor;
        titlingExecutor = null;
        session = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ the automatic trigger

    /**
     * Ask for a title, if this session is still untitled and still has attempts left.
     * Called before every model request, which is what lets a session whose first attempt
     * failed be named after the next message rather than never.
     *
     * <p>Returns as soon as the attempt is posted. The model call is never on the actor
     * thread: a titling call that took a minute would hold up the conversation for a minute
     * to buy a label, which is the wrong trade, and it would also mean the answer arrived
     * between two statements rather than beside them.
     *
     * <p>Nothing here may reach the transcript. The core logs a hook that throws, so this
     * one swallows: a session that could not be named keeps its placeholder quietly,
     * because titling is a convenience and a red error mid-conversation is not what the user
     * came for.
     *
     * <h2>Hibernation</h2>
     * The attempt is abandoned rather than waited for. A pending title must not keep a
     * quiescent session awake -- the grace period exists so an idle session can be put down,
     * and blocking it on a network call would be a worse outcome than the label it is trying
     * to buy. So hibernation proceeds, the commit step sees the session stopped and drops
     * the answer, and the attempt stays counted. A session that wakes with attempts left
     * titles itself on its next model request; one that has used them all keeps its
     * placeholder until someone asks with {@code /session title}.
     */
    @Override
    public void beforeModelRequest(NaruTask task) {
        NaruSession target = session;
        if (target == null || task == null) {
            return;
        }
        try {
            // Read the settings here, on the thread that owns the task: they come out of the
            // task's env chain, and a worker reading them a statement later could see a value
            // the user has since changed.
            int maxAttempts = NaruTaskConfig.getInt(task, MAX_ATTEMPTS_CONFIG_KEY, 3);
            if (!NaruTaskConfig.getBoolean(task, AUTO_CONFIG_KEY, true)) {
                return;
            }
            if (!isMainTask(target, task)) {
                return;
            }
            // Reading the source is what hands a session nobody has displayed yet its
            // Session N placeholder: until something asks for the name, no source is recorded,
            // and the claim below would read that as "already named" and never title it.
            if (target.nameSource() != NaruSessionNameSource.PLACEHOLDER) {
                return;
            }
            // Everything that follows is posted, including the write that counts the attempt:
            // the attempt persists itself, and a session's own actor thread must not be made
            // to stop the world for a label.
            executor().execute(() -> {
                if (claimTitleAttempt(target, maxAttempts)) {
                    runTitleAttempt(target, task);
                }
            });
        } catch (Exception e) {
            debug(target, NMsg.ofC("automatic session title could not be scheduled: %s", e.getMessage()));
        }
    }

    /**
     * The task the session is being talked through, which is the only one it gets named after.
     *
     * <p>The foreground pointer is the answer when it points at a live task. A session restored
     * from the store has no pointer at all -- the desktop answers a restored task's input request
     * directly, through {@code NaruInputRequest.deliver}, which never sets one -- so the first
     * task stands in, which is the same rule {@link #input(NaruTitleRequest, List)} uses to find
     * the history it reads. Without that fallback a restored session is never titled: the pointer
     * still says "task 0", and the guard reads that as a foreground task the message did not
     * come from.
     */
    private static boolean isMainTask(NaruSession session, NaruTask task) {
        long foreground = session.foregroundTaskId();
        if (task.id() == foreground) {
            return true;
        }
        if (session.findTask(foreground).isPresent()) {
            return false;
        }
        List<NaruTask> tasks = session.tasks();
        return !tasks.isEmpty() && tasks.get(0).id() == task.id();
    }

    /**
     * Take the one slot, count the attempt, and write it down before the call is made.
     *
     * <p>Persisting first is the point: a process that dies mid-call must not come back with
     * its attempt counter untouched and try again forever. Counting an attempt that did not
     * land is the cheaper mistake -- it costs one of three labels, and the cap exists to stop
     * a session that cannot be titled from spending a model call on every message.
     */
    private boolean claimTitleAttempt(NaruSession target, int maxAttempts) {
        synchronized (titlingLock) {
            if (titlingInFlight || !isStillLive(target) || titleAttempts >= maxAttempts) {
                return false;
            }
            titlingInFlight = true;
            titleAttempts++;
        }
        try {
            target.persist();
        } catch (RuntimeException e) {
            debug(target, NMsg.ofC("session title attempt could not be saved: %s", e.getMessage()));
        }
        return true;
    }

    /**
     * Whether this session can still be named.
     *
     * <p>{@code isRunning()} is the wrong question: a session only becomes "running" in
     * {@code start()}, while this trigger fires while the first turn is still being assembled
     * and an embedded host may title a session it never starts. What matters is that the
     * session has not been stopped, and the only way the API says that is by throwing.
     */
    private static boolean isStillLive(NaruSession target) {
        try {
            target.nameSource();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void runTitleAttempt(NaruSession target, NaruTask transport) {
        String title = null;
        try {
            title = suggest(NaruTitleRequest.firstMessage(target, transport));
        } catch (Exception e) {
            debug(target, NMsg.ofC("automatic session title failed: %s", e.getMessage()));
        }
        if (title != null) {
            commitAutomaticTitle(target, title);
        } else {
            releaseTitleAttempt();
        }
    }

    private void releaseTitleAttempt() {
        synchronized (titlingLock) {
            titlingInFlight = false;
        }
    }

    /**
     * Store the title, unless the user named the session while the model was answering.
     *
     * <p>Weaker than the in-session trigger this feature used to live behind: {@code rename}
     * is the kernel's write, so the guard and the write are two steps and a rename that lands
     * between them loses. The window is a few instructions wide, the trigger only ever fires
     * over a placeholder, and the alternative -- a kernel method that renames conditionally --
     * would put titling policy back in the core.
     */
    private void commitAutomaticTitle(NaruSession target, String title) {
        synchronized (titlingLock) {
            titlingInFlight = false;
        }
        try {
            if (target.nameSource() != NaruSessionNameSource.PLACEHOLDER) {
                // a rename landed while the model was thinking; the user's name is the one that stays
                return;
            }
            target.rename(title, NaruSessionNameSource.AUTO);
        } catch (RuntimeException e) {
            debug(target, NMsg.ofC("session title could not be saved: %s", e.getMessage()));
        }
    }

    private ExecutorService executor() {
        ExecutorService executor = titlingExecutor;
        if (executor != null && !executor.isTerminated()) {
            return executor;
        }
        synchronized (titlingLock) {
            if (titlingExecutor == null || titlingExecutor.isTerminated()) {
                titlingExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "naru-session-title");
                    t.setDaemon(true);
                    return t;
                });
            }
            return titlingExecutor;
        }
    }

    private void debug(NaruSession target, NMsg message) {
        try {
            target.log(NaruLogMode.SCRIPT, message.asDebug());
        } catch (RuntimeException ignored) {
            // a stopped session cannot be told anything, and it has nothing left to title
        }
    }

    // ------------------------------------------------------------------ the service

    @Override
    public String suggest(NaruTitleRequest request) {
        NaruTask transport = request.transport();
        NaruSession caller = transport.session();
        List<Attempt> attempts = new ArrayList<>();
        List<NaruModelConfig> candidates = candidates(request, attempts);
        if (candidates.isEmpty()) {
            throw new NaruTitleException(attempts, null);
        }
        String content = input(request, attempts);
        if (content == null) {
            throw new NaruTitleException(attempts, "the session has nothing to title from");
        }
        for (NaruModelConfig candidate : candidates) {
            String label = describe(candidate);
            NaruModelConfig tuned = candidate.withMaxTokens(MAX_TITLE_TOKENS)
                    .withTemperature(TITLE_TEMPERATURE);
            if (!caller.registry().isModelAllowed(tuned.key(), transport)) {
                attempts.add(new Attempt(label, "not allowed by the model filter"));
                continue;
            }
            NaruModelProtocol protocol = caller.registry().protocol(tuned, caller).orNull();
            if (protocol == null) {
                attempts.add(new Attempt(label, "no protocol for this model"));
                continue;
            }
            try {
                String answer = call(protocol, tuned, content, transport);
                String title = NaruTitleSanitizer.sanitize(answer, tuned.thinkingTags());
                if (title == null) {
                    attempts.add(new Attempt(label, "answered with no usable title"));
                    continue;
                }
                return title;
            } catch (Exception e) {
                attempts.add(new Attempt(label, reason(e)));
            }
        }
        throw new NaruTitleException(attempts, null);
    }

    @Override
    public String regenerate(NaruTitleRequest request) {
        String title = suggest(request);
        request.target().rename(title, NaruSessionNameSource.AUTO);
        return title;
    }

    // ------------------------------------------------------------------ model choice

    /**
     * The one place a titling model is chosen.
     *
     * <p>An unset list falls back to the transport task's model, because a session with one
     * model configured should not need a second setting to get a title. A configured list
     * never falls back: whoever named cheap models did it to bound what titling costs, and
     * answering that with the conversation's most expensive model is a failure that is
     * invisible in the bill unless someone is looking for it. Those people are the ones who
     * set the list.
     *
     * <p>Keys that do not resolve are recorded as attempts and skipped rather than thrown
     * on, so a list with one stale entry in it still titles.
     */
    private List<NaruModelConfig> candidates(NaruTitleRequest request, List<Attempt> attempts) {
        NaruTask transport = request.transport();
        NaruSession caller = transport.session();
        String override = NStringUtils.stripToNull(request.modelOverride());
        if (override != null) {
            NaruModelConfig forced = caller.findModel(override).orNull();
            if (forced == null) {
                attempts.add(new Attempt(override, "no such model is available"));
                return List.of();
            }
            return List.of(forced);
        }
        List<String> configured = NaruTaskConfig.getStringList(transport, MODELS_CONFIG_KEY, List.of());
        List<NaruModelConfig> out = new ArrayList<>();
        for (String raw : configured) {
            String key = NStringUtils.stripToNull(raw);
            if (key == null) {
                continue;
            }
            NaruModelConfig resolved = caller.findModel(key).orNull();
            if (resolved == null) {
                attempts.add(new Attempt(key, "no such model is available"));
                continue;
            }
            out.add(resolved);
        }
        if (!out.isEmpty()) {
            return out;
        }
        // A list that was configured but resolved to nothing is still a configured list: the
        // attempts above carry those reasons, and falling through here would answer a person
        // who asked "why did my cheap model fail" with a paid call on another one.
        boolean listWasConfigured = !configured.isEmpty();
        if (listWasConfigured) {
            return List.of();
        }
        NaruModelConfig taskModel = transport.model();
        if (taskModel == null) {
            attempts.add(new Attempt("(task model)", "the transport task has no model set"));
            return List.of();
        }
        return List.of(taskModel);
    }

    // ------------------------------------------------------------------ input

    private String input(NaruTitleRequest request, List<Attempt> attempts) {
        NaruSession target = request.target();
        NaruTask main = mainTask(target);
        if (main == null) {
            if (request.mode() == NaruTitleMode.EXCERPT) {
                attempts.add(new Attempt(describe(request.transport().model()),
                        "session '" + target.uuid() + "' is not open in this process, "
                                + "so its history cannot be read"));
            }
            return null;
        }
        if (request.mode() == NaruTitleMode.FIRST_MESSAGE) {
            return NaruTitlePrompt.firstMessageContent(NaruTitleInput.firstMessage(main));
        }
        List<String> turns = NaruTitleInput.excerpt(main);
        if (turns.isEmpty()) {
            return null;
        }
        return NaruTitlePrompt.userContent(turns);
    }

    /**
     * The session's main task: the one the user is talking through. A session with no live
     * task -- the catalog view of a session that is not open here -- has no main task, which
     * is the case regeneration reports rather than guesses at.
     */
    private static NaruTask mainTask(NaruSession target) {
        NaruTask fg = target.findTask(target.foregroundTaskId()).orNull();
        if (fg != null) {
            return fg;
        }
        List<NaruTask> tasks = target.tasks();
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    // ------------------------------------------------------------------ the call

    /**
     * One context-free call, metered.
     *
     * <p>Batched rather than streamed: there is no console to paint onto, and a response
     * arrives complete either way. The batched path also means thinking never reaches us as
     * fragments -- {@link NaruMessage#getFilteredContent()} is the one classification of
     * answer-versus-reasoning the engine has, and it is used here rather than a second
     * parser written next to it.
     */
    private String call(NaruModelProtocol protocol, NaruModelConfig config,
                        String content, NaruTask transport) {
        Map<String, NElement> env = new LinkedHashMap<>();
        // Asking politely: a protocol that has a spelling for "do not think" will use it.
        // Whether it survives the call is the protocol's decision, which is why the answer is
        // sanitized regardless.
        env.put(NaruThinkingConfig.THINKING_KEY, NElement.ofBoolean(false));
        NaruModelRequest request = new NaruModelRequest(
                List.of(NaruMessage.system(NaruTitlePrompt.INSTRUCTION), NaruMessage.user(content)),
                List.of(),
                env);
        NChronometer chronometer = NChronometer.of();
        NaruResponse response = protocol.chat(request, transport);
        announceUsage(transport.session(), config.key(), response, chronometer, transport);
        if (response == null || response.getMessage() == null) {
            return null;
        }
        return response.getMessage().getFilteredContent();
    }

    /**
     * Announce the call on the same metering seam a task's own calls use.
     *
     * <p>The model key is the title model that answered, not the transport task's model --
     * that is the whole reason the titler reports the usage instead of letting the task do
     * it. A call that is not announced is a call that is free, and titling across a hundred
     * sessions is not free.
     *
     * <p>Announced on the session that made the call rather than the session being named,
     * because that is where the provider's listeners are: a budget extension registered on
     * the target would be told about a call it has no transport for.
     */
    private void announceUsage(NaruSession caller, NaruModelKey model, NaruResponse response,
                               NChronometer chronometer, NaruTask transport) {
        long prompt = response == null ? 0 : response.getPromptTokens();
        long completion = response == null ? 0 : response.getEvalTokens();
        long cacheWrite = response == null ? -1 : response.getCacheWriteTokens();
        long cacheRead = response == null ? -1 : response.getCacheReadTokens();
        caller.reportModelCallUsage(transport, model, prompt, completion, cacheWrite, cacheRead,
                Map.of(USAGE_LABEL_KEY, USAGE_LABEL_VALUE), chronometer.stop().duration());
    }

    private static String describe(NaruModelConfig config) {
        if (config == null) {
            return "(no model)";
        }
        NaruModelKey key = config.key();
        return key == null ? "(no model)" : key.provider() + "/" + key.model();
    }

    /** A one-line reason a user can act on: what failed, not where in the stack it failed. */
    private static String reason(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        String first = message.split("\r\n|\r|\n", 2)[0];
        return first.length() > 200 ? first.substring(0, 200) : first;
    }
}
