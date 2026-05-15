package net.thevpc.naru.api.tool;

/**
 * A tool that can be called by the agent's reasoning model.
 *
 * <p>Implementations must be stateless (or thread-safe) — the registry
 * reuses the same instance across calls.
 */
public interface NaruDirective {

    /** Machine-readable name used in the tool schema (no spaces). */
    String getName();

    /** Human-readable description sent to the model. */
    String getDescription();

    /**
     * Execute the tool and return a string result that will be sent back
     * to the model as a "tool" role message.
     *
     * @param context per-run context (project dir, session, etc.)
     * @return result string (text, JSON snippet, error message, …)
     */
    void execute(NaruDirectiveCallContext context);
}
