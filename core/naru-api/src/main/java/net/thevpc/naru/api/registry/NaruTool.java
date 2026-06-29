package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.task.NaruTask;

import java.util.Set;

/**
 * A tool that can be called by the agent's reasoning model.
 *
 * <p>Implementations must be stateless (or thread-safe) — the registry
 * reuses the same instance across calls.
 */
public interface NaruTool {

    /**
     * Machine-readable name used in the tool schema (no spaces).
     */
    String name();

    /**
     * Human-readable description sent to the model.
     */
    String getDescription(NaruTask task);

    Set<String> tags();

    default boolean isRelevant(NaruTask task){
        return true;
    }

    /**
     * Returns the full OpenAI-compatible JSON tool definition.
     */
    NaruToolDefinition getDefinition(NaruTask task);

    /**
     * Execute the tool and return a string result that will be sent back
     * to the model as a "tool" role message.
     *
     * @param context per-run context (project dir, session, etc.)
     * @return result string (text, JSON snippet, error message, …)
     */
    String execute(NaruToolCallContext context);

    default boolean acceptMode(NaruPromptMode mode) {
        return true;
    }
}
