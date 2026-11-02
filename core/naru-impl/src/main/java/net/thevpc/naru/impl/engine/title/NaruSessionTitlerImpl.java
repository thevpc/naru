package net.thevpc.naru.impl.engine.title;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSessionNameSource;
import net.thevpc.naru.api.agent.NaruSessionTitler;
import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.agent.NaruTitleException;
import net.thevpc.naru.api.agent.NaruTitleException.Attempt;
import net.thevpc.naru.api.agent.NaruTitleMode;
import net.thevpc.naru.api.agent.NaruTitleRequest;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelProtocol;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.model.NaruThinkingConfig;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.engine.NaruSessionImpl;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.mon.NChronometer;
import net.thevpc.nuts.util.NStringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The titler the core ships.
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
 * None. Configuration is read per call from the request, so two titling calls in flight --
 * an automatic one and a {@code /session title} the user just typed -- neither share a
 * mutable field nor wait on each other.
 */
public class NaruSessionTitlerImpl implements NaruSessionTitler {

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
        if (!(caller instanceof NaruSessionImpl impl)) {
            return;
        }
        long prompt = response == null ? 0 : response.getPromptTokens();
        long completion = response == null ? 0 : response.getEvalTokens();
        long cacheWrite = response == null ? -1 : response.getCacheWriteTokens();
        long cacheRead = response == null ? -1 : response.getCacheReadTokens();
        impl.fireModelCallUsage(transport, model, prompt, completion, cacheWrite, cacheRead,
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
