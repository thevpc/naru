package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruSummaryLevel;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruToolOutputPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders context-view items into the text a summarizer actually reads.
 *
 * <p>Two things happen here that do not happen anywhere else, and both are about not paying
 * for content the summary cannot use:
 *
 * <ul>
 *   <li><b>Thinking segments are dropped.</b> A reasoning model can emit more thinking than
 *       answer, and none of it survives into the summary's output. Feeding it in inflates the
 *       input, which then needs more chunks, which costs more calls. The reasoning that
 *       mattered was already reflected in the answer the model committed to.</li>
 *   <li><b>Tool output is shaped by policy.</b> Tool results are the bulk of a long agent
 *       conversation. {@code KEEP_ERRORS} keeps the ones that look like failures, since those
 *       usually explain the next step; {@code TRUNCATE} keeps the opening of each; {@code
 *       DROP} keeps only the call.</li>
 * </ul>
 */
public final class NaruSummaryInput {

    /** Tool output longer than this is truncated, which is most of a large file read. */
    private static final int TOOL_KEEP_CHARS = 400;

    /** How much of a kept-but-long tool output survives. */
    private static final int TOOL_TRUNCATE_CHARS = 160;

    private NaruSummaryInput() {
    }

    /**
     * Renders items for the summarizer.
     *
     * <p>An empty result is legitimate and means "nothing worth summarizing", which the caller
     * must handle rather than send an empty prompt to a model.
     */
    public static String render(List<NaruMessage> items, NaruToolOutputPolicy policy) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            NaruMessage m = items.get(i);
            if (m == null) {
                continue;
            }
            sb.append(renderOne(i, m, policy));
        }
        return sb.toString();
    }

    private static String renderOne(int index, NaruMessage m, NaruToolOutputPolicy policy) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n## item ").append(index).append(" [").append(m.getRole().id()).append("]\n");

        switch (m.getRole()) {
            case summary: {
                // An earlier summary that fell inside the covered range. It is not dropped:
                // it is the densest statement of what happened so far, and summarizing it
                // away is how a long session loses its thread.
                sb.append("(this is a summary of earlier conversation, kept as-is)\n");
                sb.append(text(m.getContent()));
                return sb.toString();
            }
            case tool: {
                sb.append("tool call: ").append(m.getToolName()).append('\n');
                sb.append(shape(m.getContent(), policy));
                return sb.toString();
            }
            case assistant: {
                sb.append(text(m.getContent()));
                if (m.hasToolCalls()) {
                    sb.append("\n(tool calls requested: ");
                    List<String> names = new ArrayList<>();
                    for (NaruToolCall call : m.getToolCalls()) {
                        names.add(call.getName());
                    }
                    sb.append(String.join(", ", names)).append(')');
                }
                return sb.toString();
            }
            default:
                sb.append(text(m.getContent()));
                return sb.toString();
        }
    }

    private static String text(String content) {
        return content == null ? "" : content;
    }

    /** Applies the tool-output policy to one result body. */
    static String shape(String content, NaruToolOutputPolicy policy) {
        String value = text(content);
        if (value.isEmpty()) {
            return "";
        }
        switch (policy) {
            case DROP:
                return "";
            case KEEP_ERRORS:
                if (!looksLikeFailure(value)) {
                    return "";
                }
                return truncate(value, TOOL_TRUNCATE_CHARS, " (output omitted; this call failed)");
            case TRUNCATE:
            default:
                return truncate(value, TOOL_KEEP_CHARS, null);
        }
    }

    /**
     * Whether a tool result looks like a failure.
     *
     * <p>Keyword based, and it is a heuristic. Chosen for a low false-negative rate instead:
     * missing a failure loses the single most useful thing a summary can carry, whereas
     * keeping a successful output that was really a success costs a few tokens. The words
     * checked are the ones NARU itself and the common tools produce.
     */
    static boolean looksLikeFailure(String value) {
        String head = value.length() > 2000 ? value.substring(0, 2000) : value;
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.contains("error")
                || lower.contains("exception")
                || lower.contains("failed")
                || lower.contains("failure")
                || lower.contains("cannot ")
                || lower.contains("traceback")
                || lower.contains("compilation failed")
                || lower.contains("exit code: 1")
                || lower.contains("status: 4")
                || lower.contains("status: 5");
    }

    private static String truncate(String value, int limit, String note) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "\n... [truncated " + (value.length() - limit)
                + " chars]" + (note == null ? "" : note);
    }

    /**
     * Items as they are fed to a summarizer: thinking stripped, everything else intact.
     *
     * <p>Returns copies where a change is needed, because a summarizer must not be able to
     * affect the source history by mutating what it was handed. Items that need no change are
     * passed through as-is rather than copied -- a summarizer reads them, and copying a
     * thousand messages to hand over a thousand untouched ones costs more than it is worth.
     */
    public static List<NaruMessage> withoutThinking(List<NaruMessage> items) {
        List<NaruMessage> out = new ArrayList<>(items.size());
        for (NaruMessage m : items) {
            if (m == null) {
                continue;
            }
            if (m.getRole() != NaruRole.assistant || !m.hasThinking()) {
                out.add(m);
                continue;
            }
            // copy(), not withContent(): the intent is "an independent copy with this cleared",
            // and withContent would be a way of saying that that happens to work today.
            out.add(m.copy().clearThinking());
        }
        return out;
    }
}