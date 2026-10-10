package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;

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
     * Rich, human-readable description. It may carry styling (terminal colors,
     * emphasis) for the help output; the model never sees the styling, because
     * {@link NaruToolDefinition#getDescription()} strips it to plain text
     * ({@link NText#filteredText()}) before anything is serialized into a
     * request. Use {@link NText#ofPlain(String)} for an unstyled description.
     */
    NText getDescription(NaruTask task);

    Set<String> tags();

    /**
     * Whether this tool is a core/essential tool that stays visible even when it
     * wears no tag at all.
     *
     * <p>The tag gate is <b>fail-closed</b>: a tool with no tags has nothing to match
     * against, so by default it is <em>hidden</em> rather than offered to everyone --
     * "no tags" must never read as "no permission needed". A tool that is genuinely
     * unconditional (the {@code think} scratchpad is the only one today) has to say so
     * explicitly by overriding this method, which makes the exception visible in the
     * tool's own source instead of a silent property of the gate.
     */
    default boolean isEssential() {
        return false;
    }

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
