package net.thevpc.naru.api.tool;

/**
 * A tool that can be called by the agent's reasoning model.
 *
 * <p>Implementations must be stateless (or thread-safe) — the registry
 * reuses the same instance across calls.
 */
public interface NaruDirective {

    /** Machine-readable name used in the tool schema (no spaces). */
    String name();
    String group();
    String[] getAliases();

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

    /**
     * Resolve autocomplete candidates for this directive.
     */
    default java.util.List<net.thevpc.nuts.cmdline.NArgCandidate> resolveCandidates(
            net.thevpc.nuts.cmdline.NCmdLine cmdLine,
            net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver.Pos pos,
            net.thevpc.naru.api.agent.NaruSession session) {
        return java.util.Collections.emptyList();
    }
}
