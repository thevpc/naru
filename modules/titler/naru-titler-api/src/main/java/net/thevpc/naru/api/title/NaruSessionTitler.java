package net.thevpc.naru.api.title;

import net.thevpc.naru.api.agent.NaruSessionNameSource;
import net.thevpc.naru.api.agent.NaruSessionNames;
import net.thevpc.naru.api.registry.NaruSessionExtension;

/**
 * Names a session from what was said in it, with a context-free model call.
 *
 * <h2>What "context-free" means here</h2>
 * The request the model receives is the title instruction plus a bounded excerpt. Nothing
 * else: not the conversation, not the task's system prompt, not the tools. That is what
 * lets a cheap small model do the job, what keeps the token cost honest, and what stops a
 * titling call from spending the session's context window. The excerpt is built from the
 * <b>stored</b> history rather than the compacted context, so a compacted session is still
 * named after the conversation the user actually had.
 *
 * <h2>Where the call goes</h2>
 * Through the session's model registry and its metering seam, labelled {@code title}, so
 * the tokens show up in {@code /budget} the same way a task's do. It is not a task and it
 * does not touch the conversation: titling must never be visible in the transcript.
 *
 * <h2>Model choice</h2>
 * One place only -- {@link #suggest(NaruTitleRequest)} -- decides which model answers, from
 * {@code session.title.models}, or from an explicit override on the request. Callers that
 * want a title ask for one; nobody picks a model on the way to the titler.
 *
 * <h2>Why it is a session extension</h2>
 * Titling is a feature the kernel can live without, so nothing in the core names it: a
 * caller asks {@code session.registry().extension(NaruSessionTitler.EXTENSION,
 * NaruSessionTitler.class)} and gets nothing when the jar is absent. It is per session
 * rather than per agent because the automatic trigger needs per-session durable state --
 * how many attempts this session has already spent -- and a session extension's
 * {@link NaruSessionExtension#save(net.thevpc.naru.api.agent.NaruSession)} is the only
 * seam that gives a feature that without a store of its own.
 */
public interface NaruSessionTitler extends NaruSessionExtension {

    /** The name this extension is registered under. */
    String EXTENSION = "title";

    /**
     * Ordered {@code provider/model} keys the titler may use. Empty or unset means the
     * transport task's own model; a configured list that all fails is an error, not a
     * reason to spend the task's model. Comma-separated, so it fits one line in a config
     * file: {@code session.title.models: ollama/llama3.2:3b, openai/gpt-4o-mini}.
     */
    String MODELS_CONFIG_KEY = "session.title.models";

    /**
     * Whether a fresh session gets titled on its own before its first model request. True by
     * default; a backend that supplies its own titles turns it off with
     * {@code session.title.auto: false}, which is the only opt-out now that the kernel knows
     * nothing about titling.
     */
    String AUTO_CONFIG_KEY = "session.title.auto";

    /**
     * How many attempts the automatic trigger may make in a session's lifetime. Counted in
     * this extension's own persisted state, which is written <b>before</b> each call so that
     * a crash cannot buy an unlimited number of retries.
     */
    String MAX_ATTEMPTS_CONFIG_KEY = "session.title.maxAttempts";

    @Override
    default String name() {
        return EXTENSION;
    }

    /**
     * Produce a title without applying it.
     *
     * @param request what to name, what to read, and what to call through
     * @return the sanitized title, never blank and never longer than
     *         {@link NaruSessionNames#MAX_LENGTH} characters
     * @throws NaruTitleException when no model in scope produced a usable answer. When a
     *         list of title models is configured and every one of them fails, the task's
     *         current model is <b>not</b> tried: a person who configured cheap models and
     *         got an expensive answer has no way to notice, and a silent fallback to the
     *         most expensive model in the session is the wrong failure mode.
     */
    String suggest(NaruTitleRequest request);

    /**
     * Regenerate a name for {@code request}'s target and store it.
     *
     * <p>This is the explicit path, so it overrides whatever the session is called today --
     * including a name a user typed -- and records the result as
     * {@link NaruSessionNameSource#AUTO}. The automatic path never does this: it applies
     * only over a {@link NaruSessionNameSource#PLACEHOLDER} name and discards its answer
     * when a rename landed first.
     *
     * @return the applied title
     * @throws NaruTitleException when no model produced a usable answer; the name is left
     *         unchanged
     */
    String regenerate(NaruTitleRequest request);
}
