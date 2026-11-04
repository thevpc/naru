package net.thevpc.naru.api.title;

import net.thevpc.naru.api.agent.NaruSession;

import net.thevpc.naru.api.task.NaruTask;

/**
 * One titling job: which session to name, what to read, and what to call through.
 *
 * <p>{@code target} and {@code transport} are separate because they are usually different.
 * {@code /session title --id=abc} names a session that is not the caller's, and may name
 * one that is not even running; the model call still has to go through some task, because
 * a provider resolves its configuration -- credentials, timeouts, rate limits -- per task
 * and session. The transport task is only that: it is never written to, and nothing it
 * holds appears in the prompt unless it is also the target.
 *
 * <p>A titler must not add anything to the transport task's history, must not log into
 * its output, and must not change its model. The whole point of the split is that the
 * conversation the user is having stays exactly as it was.
 */
public final class NaruTitleRequest {

    private final NaruSession target;
    private final NaruTask transport;
    private final NaruTitleMode mode;
    private final String modelOverride;

    private NaruTitleRequest(NaruSession target, NaruTask transport, NaruTitleMode mode, String modelOverride) {
        this.target = java.util.Objects.requireNonNull(target, "target");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
        this.modelOverride = modelOverride;
    }

    /** Name {@code target} from its first user message, calling through {@code transport}. */
    public static NaruTitleRequest firstMessage(NaruSession target, NaruTask transport) {
        return new NaruTitleRequest(target, transport, NaruTitleMode.FIRST_MESSAGE, null);
    }

    /** Name {@code target} from an excerpt of its stored history, calling through {@code transport}. */
    public static NaruTitleRequest excerpt(NaruSession target, NaruTask transport) {
        return new NaruTitleRequest(target, transport, NaruTitleMode.EXCERPT, null);
    }

    /** The same request with a single model forced, replacing the configured list for this call. */
    public NaruTitleRequest withModel(String providerOrModelKey) {
        return new NaruTitleRequest(target, transport, mode, providerOrModelKey);
    }

    /** The session being named. */
    public NaruSession target() {
        return target;
    }

    /** The task the model call is made through; read-only as far as the titler is concerned. */
    public NaruTask transport() {
        return transport;
    }

    public NaruTitleMode mode() {
        return mode;
    }

    /** An explicit {@code provider/model} key, or null to use the configured list. */
    public String modelOverride() {
        return modelOverride;
    }
}
