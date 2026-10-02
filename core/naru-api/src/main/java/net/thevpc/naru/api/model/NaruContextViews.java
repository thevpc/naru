package net.thevpc.naru.api.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one place that turns a task's history into what the model is actually sent.
 *
 * <p>A task has two views of its conversation and they are not the same list:
 *
 * <ul>
 *   <li><b>History</b> -- every item, always, in the order it was produced. This is what
 *       display, export and versioning read, and it never shrinks. Compaction does not
 *       delete anything.</li>
 *   <li><b>Context view</b> -- history minus the items an active summary already stands in
 *       for, plus the summary items themselves. This is what gets measured, sent and
 *       logged.</li>
 * </ul>
 *
 * <p>Splitting them is what makes compaction non-destructive: the expensive bytes are
 * dropped on the way to the model, and remain readable on disk and in {@code /history}.
 * Collapsing the two would force one of those consumers to be wrong.
 *
 * <p>Static and dependency-free on purpose. It lives in {@code naru-api} and imports
 * nothing from the engine, so a session written with summaries can be read, and a summary
 * can be rendered to a model, by any build -- including one with no compaction extension
 * installed at all.
 */
public final class NaruContextViews {

    /**
     * Delimiters around a rendered summary.
     *
     * <p>A model must be able to tell a summary from something it was actually told. Plain
     * prose would be indistinguishable from a real assistant turn, and the agent would then
     * treat it as evidence of work that happened rather than as a report about it. The
     * markers are also the signal the context-view size is measured on.
     */
    public static final String SUMMARY_BEGIN = "--- BEGIN SUMMARY OF EARLIER CONVERSATION ---";
    public static final String SUMMARY_END = "--- END SUMMARY OF EARLIER CONVERSATION ---";

    private NaruContextViews() {
    }

    /**
     * The context view of a history: items with a summary standing in for them removed.
     *
     * <p>Order is preserved and the input list is not modified. A {@code summary}-role item
     * stays in the view -- it is the replacement, not the replaced.
     *
     * <p>A {@code summary}-role item whose state is not {@code ACTIVE} is dropped along with
     * everything else: an undone or superseded summary must not reach the model, because its
     * covered items are back in the view and sending both would double them.
     */
    public static List<NaruMessage> contextView(List<NaruMessage> history) {
        if (history == null || history.isEmpty()) {
            return Collections.emptyList();
        }
        List<NaruMessage> out = new ArrayList<>(history.size());
        for (NaruMessage m : history) {
            if (m == null) {
                continue;
            }
            if (m.isSummary()) {
                if (m.isActiveSummary()) {
                    out.add(m);
                }
                continue;
            }
            if (!m.isExcluded()) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * The summary text as it should reach the model, or null when the item is not an
     * active summary.
     *
     * <p>Wrapping is what keeps a summary honest: the model is told plainly that this is a
     * report of earlier conversation rather than part of it.
     */
    public static String renderSummary(NaruMessage message) {
        if (message == null || !message.isActiveSummary()) {
            return null;
        }
        NaruSummaryInfo info = message.getSummary();
        StringBuilder sb = new StringBuilder();
        sb.append(SUMMARY_BEGIN).append('\n');
        sb.append("(The conversation before this point was compacted. This is a summary of it,")
                .append(" not the conversation itself. Anything not in this summary is either")
                .append(" irrelevant or was deliberately dropped.)\n");
        if (info != null) {
            sb.append("(Covers ").append(info.coveredItemCount()).append(" earlier items, ")
                    .append("trigger ").append(info.trigger() == null ? "?" : info.trigger().name().toLowerCase());
            if (!info.ok()) {
                sb.append(", possibly truncated");
            }
            if (info.stale()) {
                sb.append(", STALE: the covered history changed after this was written");
            }
            sb.append(")\n");
        }
        sb.append(message.getContent() == null ? "" : message.getContent());
        sb.append('\n').append(SUMMARY_END);
        return sb.toString();
    }

    /**
     * The context view with each summary replaced by its rendered block.
     *
     * <p>Used where the caller needs the model-facing text rather than the history item --
     * measuring a summarizer's input, or writing a request log. A non-summary item is
     * passed through untouched, so the result mixes {@code NaruMessage}s of both kinds and
     * is only suitable for that.
     */
    public static List<NaruMessage> renderedContextView(List<NaruMessage> history) {
        List<NaruMessage> view = contextView(history);
        List<NaruMessage> out = new ArrayList<>(view.size());
        for (NaruMessage m : view) {
            if (m.isSummary()) {
                out.add(m.copy().withContent(renderSummary(m)));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * The context view in the form a provider request is built from.
     *
     * <p>The one thing that differs from {@link #renderedContextView} is a summary's role: it
     * is sent as {@code user}, not as {@code summary}.
     *
     * <p>{@code summary} is a NARU-internal role for marking an item in storage and for
     * rendering. It is not part of any provider's chat schema -- a serializer that maps roles
     * by name would emit {@code "role": "summary"} and the provider would reject the request,
     * with an error that says nothing about compaction. So the role is dropped at the boundary
     * and the rendered block goes out as user content, which every provider accepts and which
     * reads correctly: the agent did not write this, but it is something the operator put in
     * front of it, which is exactly what a user turn is.
     *
     * <p>The summary's own metadata is not carried over either. It is already in the rendered
     * text -- the item count, the trigger, whether it is truncated -- so re-sending it as
     * structured fields would put it on the wire twice.
     *
     * <p>The input is not modified.
     */
    public static List<NaruMessage> wireContextView(List<NaruMessage> history) {
        List<NaruMessage> rendered = renderedContextView(history);
        List<NaruMessage> out = new ArrayList<>(rendered.size());
        for (NaruMessage m : rendered) {
            if (m == null) {
                continue;
            }
            if (m.isSummary()) {
                out.add(m.asPlainUserContent(m.getContent()));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * The items in {@code history} that an active summary covers.
     *
     * <p>Identified by the exclusion flag rather than by re-deriving a range from
     * {@code coversFromId}/{@code coversToId}, so this stays correct when a user has since
     * inserted or deleted items inside what the summary originally covered.
     */
    public static List<NaruMessage> coveredItems(List<NaruMessage> history) {
        List<NaruMessage> out = new ArrayList<>();
        if (history == null) {
            return out;
        }
        for (NaruMessage m : history) {
            if (m != null && !m.isSummary() && m.isExcluded()) {
                out.add(m);
            }
        }
        return out;
    }

    /** The active summary items, in history order. */
    public static List<NaruMessage> activeSummaries(List<NaruMessage> history) {
        List<NaruMessage> out = new ArrayList<>();
        if (history == null) {
            return out;
        }
        for (NaruMessage m : history) {
            if (m != null && m.isActiveSummary()) {
                out.add(m);
            }
        }
        return out;
    }
}