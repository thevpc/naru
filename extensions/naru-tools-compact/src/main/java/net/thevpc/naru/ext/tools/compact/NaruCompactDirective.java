package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.context.NaruCompactionException;
import net.thevpc.naru.api.context.NaruCompactionResult;
import net.thevpc.naru.api.model.NaruContextSpec;
import net.thevpc.naru.api.model.NaruContextViews;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSummaryInfo;
import net.thevpc.naru.api.model.NaruSummaryLevel;
import net.thevpc.naru.api.model.NaruSummaryOptions;
import net.thevpc.naru.api.model.NaruToolOutputPolicy;
import net.thevpc.naru.api.model.NaruWindowSpec;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NStringBuilder;

import java.util.List;

/**
 * {@code /compact} -- compact now, or report and undo.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code /compact} or {@code /compact run} -- summarize everything older than the keep
 *       window and write the summary into the task.</li>
 *   <li>{@code /compact status} -- what is in force, what is covered, and what it would
 *       save. Reads only.</li>
 *   <li>{@code /compact preview} -- what a compaction would produce, without changing
 *       anything. Runs the same code path as {@code run}, so it cannot flatter the result.</li>
 *   <li>{@code /compact undo [id]} -- put the covered items back. With no id, undoes the most
 *       recent summary.</li>
 * </ul>
 *
 * <p>Options are read as {@code key=value} pairs in any order, so
 * {@code /compact keep=8turns level=aggressive} works as well as either alone. An unknown key
 * is reported rather than ignored: silently dropping {@code levels=aggressive} would compact at
 * the wrong granularity and look like the feature had a bug.
 */
public class NaruCompactDirective extends NaruDirectiveBase {

    public static final String NAME = "compact";

    public NaruCompactDirective() {
        super(NAME, "ai", "summarize older conversation to free up context", "summarize");
        register(new SubCommandRun());
        register(new SubCommandStatus());
        register(new SubCommandPreview());
        register(new SubCommandUndo());
    }

    /**
     * The extension for this session, or null when it is not installed.
     *
     * <p>Null rather than an exception: the directive is always registered -- it comes from
     * the same jar as the extension, but a user can assemble a classpath with one without
     * the other -- so it has to report "not installed" the way any other missing feature is
     * reported, rather than throwing out of a lookup.
     */
    private static NaruCompactExtension ext(NaruTask task) {
        if (task == null || task.session() == null) {
            return null;
        }
        return task.session().registry()
                .extension(NAME, NaruCompactExtension.class)
                .orElse(null);
    }

    private static NaruStmtResult notInstalled(NaruTask task) {
        String msg = NMsg.ofC(
                "compaction is not installed in this session. Add the naru-tools-compact "
                        + "extension to the classpath.").toString();
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", msg).asError());
        return NaruStmtResult.ofError(msg);
    }

    // ── option parsing ──────────────────────────────────────────────────────

    /** Parsed {@code key=value} options plus the one bare positional argument. */
    static final class Options {
        String keep;
        String level;
        String focus;
        String models;
        String outputs;
        Long maxTokens;
        final StringBuilder positional = new StringBuilder();
        final StringBuilder errors = new StringBuilder();

        void error(String message) {
            if (errors.length() > 0) {
                errors.append("; ");
            }
            errors.append(message);
        }

        NaruWindowSpec window(NaruCompactConfig config) {
            if (keep == null) {
                return config.keep();
            }
            try {
                return NaruWindowSpec.parse(keep);
            } catch (RuntimeException e) {
                error(e.getMessage());
                return config.keep();
            }
        }

        NaruSummaryOptions summaryOptions(NaruCompactConfig config) {
            NaruSummaryLevel effectiveLevel = config.level();
            if (level != null) {
                NaruSummaryLevel parsed = NaruSummaryLevel.parse(level);
                if (parsed == null) {
                    error("unknown level '" + level + "'; expected one of "
                            + java.util.Arrays.toString(NaruSummaryLevel.values()));
                } else {
                    effectiveLevel = parsed;
                }
            }
            NaruToolOutputPolicy outputs = null;
            if (this.outputs != null) {
                outputs = NaruToolOutputPolicy.parse(this.outputs);
                if (outputs == null) {
                    error("unknown tool output policy '" + this.outputs + "'; expected one of "
                            + java.util.Arrays.toString(NaruToolOutputPolicy.values()));
                }
            }
            NaruSummaryOptions options = NaruSummaryOptions.of(effectiveLevel);
            if (focus != null) {
                options = options.withFocus(focus);
            }
            if (maxTokens != null) {
                options = options.withMaxTokens(maxTokens);
            }
            if (models != null) {
                options = options.withModels(NaruSummaryModelSelector.parseList(models));
            }
            if (outputs != null) {
                options = options.withToolOutputs(outputs);
            }
            return options;
        }
    }

    /**
     * Reads the remaining arguments as {@code key=value} pairs.
     *
     * <p>A bare word accumulates into the positional buffer rather than being treated as an
     * unknown key, so {@code /compact preview 4turns} and
     * {@code /compact preview keep=4turns} both work.
     */
    static Options parseOptions(NCmdLine cmdLine) {
        Options options = new Options();
        if (cmdLine == null) {
            return options;
        }
        NCmdLine rest = cmdLine;
        while (rest != null && !rest.isEmpty() && rest.peek().isPresent()) {
            NArg next = rest.next().orNull();
            if (next == null) {
                break;
            }
            // Read the value first. NCmdLine may have split "keep=4turns" into a key and a
            // value, or kept it as one image depending on the form, so both are accepted
            // rather than requiring the user to know which the parser chose.
            String key = next.key();
            String value = next.stringValue();
            if (key == null || key.isEmpty()) {
                String image = next.stringValue();
                int eq = image == null ? -1 : image.indexOf('=');
                if (eq <= 0) {
                    if (options.positional.length() > 0) {
                        options.positional.append(' ');
                    }
                    options.positional.append(image);
                    continue;
                }
                key = image.substring(0, eq).trim();
                value = image.substring(eq + 1).trim();
            }
            if (value == null) {
                // A bare key with no attached value: take the next argument as its value, so
                // "keep 4turns" works as well as "keep=4turns".
                NArg following = rest.peek().isPresent() ? rest.next().orNull() : null;
                value = following == null ? null : following.stringValue();
            }
            key = key.trim().toLowerCase();
            value = value == null ? "" : value.trim();
            switch (key) {
                case "keep":
                case "window":
                    options.keep = value;
                    break;
                case "level":
                    options.level = value;
                    break;
                case "focus":
                    options.focus = value;
                    break;
                case "models":
                case "model":
                    options.models = value;
                    break;
                case "tooloutputs":
                case "outputs":
                    options.outputs = value;
                    break;
                case "maxtokens":
                    try {
                        options.maxTokens = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        options.error("maxTokens must be a number, got '" + value + "'");
                    }
                    break;
                default:
                    options.error("unknown option '" + key
                            + "'; expected one of keep, level, focus, models, toolOutputs, maxTokens");
            }
        }
        return options;
    }

    // ── subcommands ─────────────────────────────────────────────────────────

    private class SubCommandRun extends AbstractSubCommand {
        SubCommandRun() {
            super("run", NText.ofPlain("summarize older conversation now"),
                    new SubCommandHelp(NText.ofPlain("[keep=4turns] [level=aggressive] [focus=\"...\"]"),
                            NText.ofPlain("compact now")));
        }

        @Override
        public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
            NaruTask task = context.task();
            NaruCompactExtension extension = ext(task);
            if (extension == null) {
                return notInstalled(task);
            }
            Options options = parseOptions(cmdLine);
            if (options.errors.length() > 0) {
                return NaruStmtResult.ofError(options.errors.toString());
            }
            NaruCompactConfig config = new NaruCompactConfig(task);
            try {
                NaruCompactionResult result = extension.compactNow(task,
                        options.window(config), options.summaryOptions(config));
                return NaruStmtResult.ofSuccess(report(task, result));
            } catch (NaruCompactionException e) {
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", e.getMessage()).asError());
                return NaruStmtResult.ofError(e.getMessage());
            }
        }
    }

    private class SubCommandPreview extends AbstractSubCommand {
        SubCommandPreview() {
            super("preview", NText.ofPlain("show what compacting would do, changing nothing"),
                    new SubCommandHelp(NText.ofPlain("[keep=4turns] [level=aggressive]"),
                            NText.ofPlain("dry run")));
        }

        @Override
        public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
            NaruTask task = context.task();
            NaruCompactExtension extension = ext(task);
            if (extension == null) {
                return notInstalled(task);
            }
            Options options = parseOptions(cmdLine);
            if (options.errors.length() > 0) {
                return NaruStmtResult.ofError(options.errors.toString());
            }
            NaruCompactConfig config = new NaruCompactConfig(task);
            try {
                long window = NaruCompactExtension.contextWindowOf(task, task.model());
                NaruContextSpec spec = NaruContextSpec.of(window, options.window(config),
                        options.summaryOptions(config));
                // preview, not compact: the same compactor, with apply=false
                NaruCompactionResult result = net.thevpc.naru.api.context.NaruCompactors
                        .preview(task, spec);
                NStringBuilder sb = NStringBuilder.of();
                sb.println(report(task, result));
                if (result.summaryItem() != null) {
                    sb.println("");
                    sb.println("--- would insert ---");
                    sb.println(result.summaryItem().getContent());
                }
                return NaruStmtResult.ofSuccess(sb.toString());
            } catch (NaruCompactionException e) {
                return NaruStmtResult.ofError(e.getMessage());
            }
        }
    }

    private class SubCommandStatus extends AbstractSubCommand {
        SubCommandStatus() {
            super("status", NText.ofPlain("show compaction state and settings"));
        }

        @Override
        public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
            NaruTask task = context.task();
            NaruCompactExtension extension = ext(task);
            if (extension == null) {
                return notInstalled(task);
            }
            String text = extension.describe(task);
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", text));
            return NaruStmtResult.ofSuccess(text);
        }
    }

    private class SubCommandUndo extends AbstractSubCommand {
        SubCommandUndo() {
            super("undo", NText.ofPlain("restore the items a summary replaced"),
                    new SubCommandHelp(NText.ofPlain("[id]"),
                            NText.ofPlain("undo the newest summary, or the one with this id")));
        }

        @Override
        public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
            NaruTask task = context.task();
            NaruCompactExtension extension = ext(task);
            if (extension == null) {
                return notInstalled(task);
            }
            String id = null;
            NCmdLine rest = cmdLine;
            if (rest != null && !rest.isEmpty() && rest.peek().isPresent()) {
                id = rest.next().get().image();
            }
            if (id == null || id.isBlank()) {
                id = newestActiveSummaryId(task);
                if (id == null) {
                    return NaruStmtResult.ofError("no active summary to undo");
                }
            }
            NaruCompactionResult result = extension.undo(task, id);
            if (!result.isSuccess()) {
                return NaruStmtResult.ofError(result.message());
            }
            String msg = "undid summary " + id
                    + "; the covered items are back in the context view";
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", msg));
            return NaruStmtResult.ofSuccess(msg);
        }
    }

    /**
     * The id of the most recently created active summary.
     *
     * <p>By creation time rather than position, because the newest summary is not always the
     * last item -- a later compaction can be inserted before an earlier one's covered span,
     * leaving history order unrelated to creation order.
     */
    static String newestActiveSummaryId(NaruTask task) {
        String best = null;
        java.time.Instant bestAt = null;
        for (NaruMessage m : NaruContextViews.activeSummaries(task.history())) {
            NaruSummaryInfo info = m.getSummary();
            if (info == null || info.id() == null) {
                continue;
            }
            if (bestAt == null || info.createdAt() == null
                    || (bestAt != null && info.createdAt().isAfter(bestAt))) {
                best = info.id();
                bestAt = info.createdAt();
            }
        }
        return best;
    }

    /** A one-line report of what a compaction did. */
    static String report(NaruTask task, NaruCompactionResult result) {
        if (result == null) {
            return "compaction produced no result";
        }
        switch (result.outcome()) {
            case NOTHING_TO_COMPACT:
                return "nothing to compact: the conversation already fits the keep window";
            case CACHE_HIT:
                return "reused a cached summary of this exact content ("
                        + NaruCompactTokens.format(result.coveredTokens())
                        + " -> " + NaruCompactTokens.format(result.summaryTokens())
                        + " tokens); nothing changed";
            case PRODUCED:
                return "would cover " + result.coveredItemCount() + " items, "
                        + NaruCompactTokens.format(result.coveredTokens())
                        + " -> " + NaruCompactTokens.format(result.summaryTokens())
                        + " tokens"
                        + (result.modelUsed() == null ? "" : " using " + result.modelUsed());
            case APPLIED:
                return "compacted " + result.coveredItemCount() + " items: "
                        + NaruCompactTokens.format(result.coveredTokens())
                        + " -> " + NaruCompactTokens.format(result.summaryTokens())
                        + " tokens (saved " + NaruCompactTokens.format(result.savedTokens()) + ")"
                        + (result.modelUsed() == null ? "" : " using " + result.modelUsed())
                        + appendSkipped(result);
            default:
                return result.message() == null ? "compaction failed" : result.message();
        }
    }

    private static String appendSkipped(NaruCompactionResult result) {
        List<String> skipped = result.skippedModels();
        return skipped == null || skipped.isEmpty() ? "" : "; skipped " + String.join("; ", skipped);
    }
}