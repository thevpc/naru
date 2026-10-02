package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.context.NaruCompactionException;
import net.thevpc.naru.api.context.NaruCompactionResult;
import net.thevpc.naru.api.model.NaruContextSpec;
import net.thevpc.naru.api.model.NaruSummaryLevel;
import net.thevpc.naru.api.model.NaruSummaryOptions;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.model.NaruToolOutputPolicy;
import net.thevpc.naru.api.model.NaruWindowSpec;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;

/**
 * Lets the agent compact its own conversation.
 *
 * <p>Off unless {@code naru.compact.modelCanCompact} is true. The default is off for a
 * specific reason: an agent that can discard its own context can also decide to discard it,
 * and a model that has just been squeezed for room is exactly the kind of component that
 * concludes the squeeze was the problem. Enabling it is reasonable for a task that is
 * expected to run long enough to need it -- a build loop, a migration -- and a bad idea for
 * one where the user wants to watch what the model does.
 *
 * <p>The tool reports tokens saved rather than item counts, because tokens are the currency
 * the decision is actually made in and the model can reason about them.
 */
public class ContextCompactTool extends DefaultNaruTool {

    public static final String NAME = "context_compact";

    public ContextCompactTool() {
        super(NAME, new String[]{NaruToolTags.AI});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Summarize the older part of this conversation to free up context. "
                + "Nothing is deleted: the summary stands in for those messages in future "
                + "requests, and they remain readable in full history. "
                + "Use when the conversation is long and earlier details no longer need to be "
                + "quoted exactly. Do not use it to avoid reading something -- the summary is "
                + "only as good as what was in the messages it replaces.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("keep",
                        "How much of the recent conversation to preserve verbatim, as a count "
                                + "and unit: '2000tokens', '4turns' or '20items'. "
                                + "Defaults to the configured value.", false).build(),
                NaruToolParameter.string("level",
                        "How hard to compress: 'light', 'normal' or 'aggressive'. "
                                + "Aggressive keeps conclusions and decisions and drops "
                                + "reasoning; light keeps most detail.", false).build(),
                NaruToolParameter.string("focus",
                        "What to pay attention to in the summary, such as 'the API we chose' "
                                + "or 'open questions'. Optional.", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        NaruTask task = context.task();
        NaruCompactConfig config = new NaruCompactConfig(task);
        if (!config.modelCanCompact()) {
            return "Compaction by tool call is disabled. The user can run /compact, or set "
                    + NaruCompactConfig.MODEL_CAN_COMPACT + " = true to allow it.";
        }
        NaruCompactExtension extension = task.session().registry()
                .extension(NaruCompactExtension.NAME, NaruCompactExtension.class)
                .orElse(null);
        if (extension == null) {
            return "The compaction extension is not installed in this session.";
        }

        NaruWindowSpec window = context.stringArg("keep").orNull() == null
                ? config.keep()
                : NaruWindowSpec.parse(context.stringArg("keep").orNull().trim());

        NaruSummaryLevel level = config.level();
        String levelArg = context.stringArg("level").orNull();
        if (levelArg != null && !levelArg.isBlank()) {
            NaruSummaryLevel parsed = NaruSummaryLevel.parse(levelArg.trim());
            if (parsed == null) {
                return "Unknown level '" + levelArg + "'. Expected one of "
                        + java.util.Arrays.toString(NaruSummaryLevel.values()) + ".";
            }
            level = parsed;
        }
        NaruSummaryOptions options = NaruSummaryOptions.of(level)
                .withFocus(context.stringArg("focus").orNull());
        // The level already selects a tool-output policy; re-stating it here would let a
        // call contradict its own level, so the level's policy stands.
        options = options.withToolOutputs(level.toolOutputs());

        try {
            NaruCompactionResult result = extension.compactNow(task, window, options);
            if (result.outcome() == NaruCompactionResult.Outcome.NOTHING_TO_COMPACT) {
                return "Nothing to compact: the conversation already fits the keep window.";
            }
            StringBuilder sb = new StringBuilder("Compacted ");
            sb.append(result.coveredItemCount()).append(" earlier messages, freeing about ")
                    .append(NaruCompactTokens.format(Math.max(0, result.savedTokens())))
                    .append(" tokens (")
                    .append(NaruCompactTokens.format(result.coveredTokens()))
                    .append(" -> ")
                    .append(NaruCompactTokens.format(result.summaryTokens()))
                    .append(")");
            if (result.modelUsed() != null) {
                sb.append(" using ").append(result.modelUsed());
            }
            if (!result.skippedModels().isEmpty()) {
                sb.append(". Skipped: ").append(String.join("; ", result.skippedModels()));
            }
            sb.append(". The full history is unchanged; /compact undo restores the messages.");
            return sb.toString();
        } catch (NaruCompactionException e) {
            // Reported as text, not thrown: a tool that fails has to tell the model what went
            // wrong so it can adapt, and this is the model's own context it is being asked to
            // compress.
            return "Could not compact: " + e.getMessage();
        }
    }

    /** Kept referenced so the policy type stays a visible part of this tool's contract. */
    static NaruToolOutputPolicy policyFor(NaruSummaryLevel level) {
        return level.toolOutputs();
    }

    /** The spec a call would build, exposed for tests that check the wiring. */
    static NaruContextSpec specFor(long contextWindow, NaruWindowSpec keep, NaruSummaryOptions options) {
        return NaruContextSpec.of(contextWindow, keep, options);
    }
}