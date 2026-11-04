package net.thevpc.naru.ext.tools.compact;

import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruToolCall;
import net.thevpc.naru.api.model.NaruWindowSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses where to cut a context view, and refuses to cut in a place that breaks it.
 *
 * <p>A cut is the index at which the kept window begins. Everything before it becomes the
 * summarizer's input. Getting it wrong in the direction of "too late" produces a request the
 * provider rejects; getting it wrong as "too early" just summarizes a little more than
 * needed. So the rule here is to start at the requested position and move <em>earlier</em>
 * until the boundary is clean, never later: a kept window is a lower bound on what survives,
 * not an upper one.
 *
 * <h2>What makes a boundary dirty</h2>
 *
 * <p>Two structures in a conversation are only meaningful whole:
 *
 * <ul>
 *   <li><b>A tool call and its result.</b> A provider rejects a request whose tool result has
 *       no preceding call, and a call with no result either. Cutting between them produces
 *       exactly that, and the error surfaces as an opaque provider message rather than as a
 *       compaction bug.</li>
 *   <li><b>An assistant message and its thinking segments.</b> These travel together in one
 *       item already, so this mostly guards against a cut landing inside an assistant turn
 *       whose answer is still being accumulated.</li>
 * </ul>
 *
 * <p>Reasoning segments are dropped from the summarizer's <em>input</em> but that is a
 * separate decision taken afterwards: here they still count as part of the item that owns
 * them, because which items are covered is decided before what is read from them.
 */
public final class NaruCompactCut {

    private NaruCompactCut() {
    }

    /**
     * The cut index for a window, or -1 when there is nothing older than the window to
     * compact.
     *
     * @param items the context view, in order. Never includes excluded items.
     * @param window how much of the end must survive verbatim
     */
    public static int cutOf(List<NaruMessage> items, NaruWindowSpec window) {
        if (items == null || items.isEmpty() || window == null || window.isNone()) {
            return 0;
        }
        int requested = requestedCut(items, window);
        if (requested >= items.size()) {
            // The window already keeps everything. -1 rather than 0, because 0 means "keep
            // nothing" to every other part of the system -- a caller that read 0 as
            // "summarize the whole conversation" here would compact a conversation that
            // needed no compaction, and charge for it.
            return -1;
        }
        if (requested <= 0) {
            return requested < 0 ? -1 : 0;
        }
        return moveEarlierToCleanBoundary(items, requested);
    }

    /**
     * The cut the window asks for, before any correction.
     *
     * <p>-1 means the whole view fits inside the window, so there is nothing to compact.
     */
    private static int requestedCut(List<NaruMessage> items, NaruWindowSpec window) {
        switch (window.unit()) {
            case NONE:
                return 0;
            case ITEMS: {
                int keep = Math.min(window.amount(), items.size());
                return keep >= items.size() ? -1 : items.size() - keep;
            }
            case TURNS: {
                List<Integer> boundaries = turnStarts(items);
                int turnsWanted = window.amount();
                if (turnsWanted <= 0) {
                    return 0;
                }
                if (boundaries.isEmpty()) {
                    // No turn boundary anywhere: treat the whole thing as one turn rather than
                    // guessing at turns that do not exist.
                    return 0;
                }
                if (turnsWanted >= boundaries.size()) {
                    // Every turn in the view is inside the window.
                    return -1;
                }
                return boundaries.get(boundaries.size() - turnsWanted);
            }
            case TOKENS: {
                long budget = window.amount();
                long running = 0;
                int cut = items.size();
                for (int i = items.size() - 1; i >= 0; i--) {
                    running += NaruCompactTokens.estimate(items.get(i));
                    if (running > budget) {
                        cut = i + 1;
                        break;
                    }
                }
                return cut;
            }
            default:
                return 0;
        }
    }

    /**
     * Indices at which a user turn starts.
     *
     * <p>Uses the {@code turnBoundary} flag, which is set on the first item of each user turn
     * and is persisted -- so the same turns are found after a reload. Items with no boundary
     * at all (a conversation that started mid-turn, or one built by a script) all belong to
     * turn 0, and {@code boundaries} is empty for it.
     */
    private static List<Integer> turnStarts(List<NaruMessage> items) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isTurnBoundary()) {
                starts.add(i);
            }
        }
        return starts;
    }

    /**
     * Moves a cut earlier until no structure is split at it.
     *
     * <p>Only ever earlier. A cut moved later would keep less than the window asked for, and
     * "keep at least this much" is the promise the window makes.
     */
    public static int moveEarlierToCleanBoundary(List<NaruMessage> items, int cut) {
        int c = Math.max(0, Math.min(cut, items.size()));
        while (c > 0 && splitsAGroup(items, c)) {
            c--;
        }
        return c;
    }

    /**
     * Whether the boundary at {@code cut} splits a tool call from its result, or an assistant
     * message from its answer.
     *
     * <p>Both checks look at the item on each side of the boundary, because either can be the
     * one that is orphaned.
     */
    static boolean splitsAGroup(List<NaruMessage> items, int cut) {
        if (cut <= 0 || cut >= items.size()) {
            return false;
        }
        NaruMessage left = items.get(cut - 1);
        NaruMessage right = items.get(cut);
        if (left == null || right == null) {
            return false;
        }
        // A tool result whose call is on the summarized side is not. The call is not always the
        // item immediately to the left: one assistant message may make several calls and be
        // followed by several results, so the search walks back over the sibling results to
        // find the call they belong to.
        if (isToolResult(right) && !callIsKept(items, cut, right)) {
            return true;
        }
        // A tool call whose results are all about to be summarized away leaves the provider
        // with a call and no answer.
        if (hasToolCalls(left) && someResultsAreSummarizedAway(items, cut, left)) {
            return true;
        }
        return false;
    }

    /**
     * Whether the call this tool result answers survives on the kept side of the boundary.
     *
     * <p>Looks within the kept side only. One assistant message may make several calls and be
     * followed by several results, so the owner is the nearest message on that side that
     * carries calls, not necessarily the item immediately before the boundary. A result with
     * no id is treated as answered: providers do not always echo it back, and an id we cannot
     * match is far more likely to belong to the call in front of it than to be spurious.
     */
    private static boolean callIsKept(List<NaruMessage> items, int cut, NaruMessage toolResult) {
        String wanted = toolResult.getToolCallId();
        for (int i = cut; i < items.size(); i++) {
            NaruMessage m = items.get(i);
            if (isToolResult(m)) {
                continue;
            }
            if (!hasToolCalls(m)) {
                continue;
            }
            if (wanted == null || wanted.isBlank()) {
                return true;
            }
            for (NaruToolCall call : m.getToolCalls()) {
                if (wanted.equals(call.getId())) {
                    return true;
                }
            }
            // The nearest call-bearing message on the kept side is not this result's owner.
            return false;
        }
        return false;
    }

    private static boolean isToolResult(NaruMessage m) {
        return m.getRole() == NaruRole.tool;
    }

    private static boolean hasToolCalls(NaruMessage m) {
        return m.getRole() == NaruRole.assistant && m.hasToolCalls();
    }

    /**
     * Whether any result of the calls in {@code caller} ends up in the summarized span.
     *
     * <p>True means the boundary is dirty: the call would be kept while its results are
     * summarized away, leaving the provider with a tool call it has no answer for.
     *
     * <p>An unmatched count is treated as answering. Providers and tools do not always echo a
     * call id back, and a result whose id is unknown is far more likely to belong to the call
     * next to it than to be spurious. Being strict here would walk the cut backwards past an
     * entire tool-using turn and summarize far more than the window asked for.
     */
    private static boolean someResultsAreSummarizedAway(List<NaruMessage> items, int cut,
                                                        NaruMessage caller) {
        List<String> wanted = new ArrayList<>();
        for (NaruToolCall call : caller.getToolCalls()) {
            wanted.add(call.getId());
        }
        if (wanted.isEmpty()) {
            return false;
        }
        // how many of the calls are answered inside the kept window
        java.util.Set<String> answeredInKept = new java.util.HashSet<>();
        for (int i = cut; i < items.size(); i++) {
            NaruMessage m = items.get(i);
            if (isToolResult(m) && m.getToolCallId() != null) {
                answeredInKept.add(m.getToolCallId());
            }
        }
        for (String id : wanted) {
            if (id == null || !answeredInKept.contains(id)) {
                return true;
            }
        }
        return false;
    }
}