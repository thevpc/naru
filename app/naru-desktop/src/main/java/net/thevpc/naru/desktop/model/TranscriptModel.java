package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruOutput;
import net.thevpc.naru.api.agent.NaruToolCallEvent;
import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.naru.desktop.util.MsgText;
import net.thevpc.naru.desktop.util.ToolCardText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The transcript as a view-model: an ordered list of {@link TranscriptBlock}s, fed by batches of
 * host events on the EDT, and nothing else. No Swing import — that is what makes it testable
 * headless (plan §8) and what keeps a future {@code TurnApiHost} from needing a second
 * implementation.
 * <p>
 * Two kinds of change are reported, because widgets pay very differently for them: a text
 * append refreshes one cell, while an open/close or a verbose-filter change moves rows and
 * makes the list reload its contents. A batch collapses into at most one notification of each.
 * <p>
 * Memory is bounded: closed blocks are trimmed from the front once the transcript exceeds
 * {@link #setMaxChars}, with the count kept in {@link #trimmedBlocks()} so the UI can say
 * "N earlier blocks trimmed" instead of silently showing a truncated history.
 */
public final class TranscriptModel {

    /** Default ceiling on retained transcript characters — roughly 4 MB of text. */
    public static final int DEFAULT_MAX_CHARS = 4 * 1024 * 1024;

    /**
     * A tool result longer than this starts collapsed. Cards are the one block kind whose body is
     * routinely enormous — a {@code file_read} of a whole source file — and a collapsed card still
     * shows the tool, the duration and the character count, so nothing is hidden, only deferred.
     */
    public static final int TOOL_CARD_COLLAPSE_CHARS = 1_200;

    public interface Listener {
        /**
         * @param structural true when blocks were added, closed, removed or re-filtered and row
         *                   indices moved; false when only the text of existing rows changed
         */
        void transcriptChanged(boolean structural);
    }

    private final List<TranscriptBlock> blocks = new ArrayList<>();
    private final List<TranscriptBlock> view = new ArrayList<>();
    private final Map<String, TranscriptBlock> byId = new HashMap<>();

    private final List<Listener> listeners = new ArrayList<>();

    private boolean verbose;
    private int maxChars = DEFAULT_MAX_CHARS;
    private int retainedChars;
    private int trimmedBlocks;
    private String openBlockId;
    private long cardSequence;
    /**
     * Cards waiting for their final event, keyed by {@code (taskId, callId or tool name)}. A deque,
     * not a map entry, because a model that sends no call id can legitimately have two calls to the
     * same tool outstanding in one task — and those settle in the order they started, which is the
     * only ordering the engine gives us to match them by.
     */
    private final Map<String, Deque<String>> openCards = new HashMap<>();

    // ── feeding ───────────────────────────────────────────────────────────────

    /** Apply one coalesced batch. Called on the EDT by the session model. */
    public void apply(List<TranscriptEvent> batch) {
        EDT.check("TranscriptModel.apply");
        boolean structural = false;
        boolean text = false;
        boolean messagesChanged = false;
        for (TranscriptEvent e : batch) {
            if (e instanceof TranscriptEvent.Chunk c) {
                boolean isNew = byId.get(c.blockId()) == null;
                TranscriptBlock b = blockFor(c.blockId(), c.mode(), c.taskId());
                structural |= isNew;
                int before = b.length();
                String add = MsgText.plain(c.text());
                if (c.finalFragment() && !add.isEmpty() && !add.endsWith("\n")) {
                    add = add + "\n";
                }
                b.append(add);
                retainedChars += b.length() - before;
                // Chunk.finalFragment() ends a *message*, not the block: a run of whole
                // messages from one directive is still one block, and only Closed freezes it.
                // Closing here would make the next line in the same block undrawable.
                text = true;
            } else if (e instanceof TranscriptEvent.Opened o) {
                boolean isNew = byId.get(o.blockId()) == null;
                blockFor(o.blockId(), o.mode(), o.taskId());
                structural |= isNew;
                openBlockId = o.blockId();
            } else if (e instanceof TranscriptEvent.Closed cl) {
                structural |= close(cl.blockId()) != null;
            } else if (e instanceof TranscriptEvent.ToolCall tc) {
                structural |= toolCard(tc.call());
                // every lifecycle event appends its header line to a row, so a repaint is due
                // whether or not a row was added
                text = true;
            } else if (e instanceof TranscriptEvent.Message m) {
                if (m.message() != null) {
                    boolean isNew = byId.get(m.blockId()) == null;
                    NaruLogMode mode = m.message().getRole() == net.thevpc.naru.api.agent.NaruRole.user
                            ? NaruLogMode.SCRIPT : NaruLogMode.AGENT_RESPONSE;
                    TranscriptBlock b = blockFor(m.blockId(), mode, m.taskId());
                    b.setMessage(m.message(), m.sequence());
                    String body = m.message().getContent();
                    if (body == null) body = "";
                    if (!m.message().getParts().isEmpty()) {
                        for (var part : m.message().getParts()) body += "\n" + part.type() + ": " + part.payload();
                    }
                    appendText(b, body.endsWith("\n") || body.isEmpty() ? body : body + "\n");
                    close(m.blockId());
                    structural |= isNew;
                    messagesChanged = true;
                    text = true;
                }
            } else if (e instanceof TranscriptEvent.Lagged) {
                // the host already told us it dropped chunks; a resync line is structural
                structural = true;
            } else if (e instanceof TranscriptEvent.HostClosed hc) {
                structural |= closeOpen() != null;
                // a card left open never settles: this activation ended. Say so on the card rather
                // than let it sit there claiming a tool is still running
                structural |= settleOpenCards(hc.reason());
            }
        }
        if (messagesChanged) {
            // Blocks are appended in the order the conversation happened. Never re-sort them
            // afterwards: a live echo has no durable sequence yet, and mixing "sequence when
            // known" with "creation index otherwise" is not a transitive comparator. It can
            // throw IllegalArgumentException from List.sort and it can move a just-sent user
            // line below the answer it prompted.
            structural = true;
        }
        structural |= trim();
        if (structural) {
            rebuildView();
        }
        fire(structural, text);
    }

    private TranscriptBlock close(String id) {
        TranscriptBlock b = byId.get(id);
        if (b == null || b.isClosed()) {
            return null;
        }
        b.close();
        if (id.equals(openBlockId)) {
            openBlockId = null;
        }
        return b;
    }

    /** Close whatever is open, so a shutdown or an interrupt never leaves a half block. */
    public TranscriptBlock closeOpen() {
        String id = openBlockId;
        return id == null ? null : close(id);
    }

    private TranscriptBlock blockFor(String id, NaruLogMode mode, long taskId) {
        TranscriptBlock b = byId.get(id);
        if (b != null) {
            return b;
        }
        b = new TranscriptBlock(id, mode, taskId, blocks.size());
        blocks.add(b);
        byId.put(id, b);
        openBlockId = id;
        viewDirty = true;
        return b;
    }

    // ── tool cards (E1) ───────────────────────────────────────────────────────

    /**
     * Apply one tool-call lifecycle event to the transcript as a card.
     *
     * <p>The card is a block like any other, which is what makes it free to scroll, collapse and
     * trim with the rest of the transcript. Its mode is {@code SCRIPT} so it stays visible without
     * verbose on — a tool call is not noise — and the widget tells it apart by
     * {@link TranscriptBlock#isToolCard()}.
     *
     * @return true when a row was added or a row's open/closed state moved
     */
    private boolean toolCard(NaruToolCallEvent event) {
        if (event == null) {
            return false;
        }
        String key = cardKey(event);
        if (event.kind() == NaruToolCallEvent.Kind.STARTED
                || event.kind() == NaruToolCallEvent.Kind.PARKED) {
            TranscriptBlock b = null;
            if (event.kind() == NaruToolCallEvent.Kind.PARKED) {
                // waiting is a state of the call that is already on screen; STARTED is not, because
                // a model with no call id can begin a second one while the first is still open
                Deque<String> q = openCards.get(key);
                String openId = q == null ? null : q.peekFirst();
                b = openId == null ? null : byId.get(openId);
                if (b != null && b.isClosed()) {
                    b = null;
                }
            }
            boolean added = false;
            if (b == null) {
                b = cardBlock(event);
                openCards.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(b.id());
                b.setToolCall(event);
                added = true;
            }
            appendText(b, ToolCardText.header(event));
            return added;
        }
        Deque<String> q = openCards.get(key);
        String openId = q == null ? null : q.pollFirst();
        if (q != null && q.isEmpty()) {
            openCards.remove(key);
        }
        TranscriptBlock b = openId == null ? null : byId.get(openId);
        boolean added = false;
        if (b == null || b.isClosed()) {
            // a final event with no open card: the call was rejected before it dispatched, or the
            // STARTED belonged to an activation that has since been trimmed away
            b = cardBlock(event);
            added = true;
        }
        b.setToolCall(event);
        appendText(b, ToolCardText.header(event));
        String body = ToolCardText.cardBody(event);
        if (body != null && !body.isEmpty()) {
            appendText(b, body.endsWith("\n") ? body : body + "\n");
        }
        close(b.id());
        if (b.length() > TOOL_CARD_COLLAPSE_CHARS) {
            b.setCollapsed(true);
        }
        return added;
    }

    private TranscriptBlock cardBlock(NaruToolCallEvent event) {
        String id = "tc" + (++cardSequence);
        // a card must not become the block a later chunk joins, so the text block that was open
        // before it keeps that role — the card is tracked by openCards instead
        String previous = openBlockId;
        TranscriptBlock b = blockFor(id, NaruLogMode.SCRIPT, event.taskId());
        openBlockId = previous != null && !previous.equals(id) ? previous : null;
        return b;
    }

    /**
     * Match an event to its card. {@code callId} is what the model chose to send and some models
     * send nothing, so the tool name is the fallback key; the task id keeps two tasks calling the
     * same tool apart, which is the ambiguity that used to be guessed from ordering.
     */
    private static String cardKey(NaruToolCallEvent event) {
        String id = event.callId();
        String key = id == null || id.isBlank() ? String.valueOf(event.toolName()) : id;
        return event.taskId() + "\u0000" + key;
    }

    /** Close every card still waiting, because this activation is over. */
    private boolean settleOpenCards(String reason) {
        if (openCards.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Deque<String> q : openCards.values()) {
            for (String id : q) {
                TranscriptBlock b = byId.get(id);
                if (b != null && !b.isClosed()) {
                    appendText(b, "✗ no result: " + (reason == null ? "the host closed" : reason) + "\n");
                    close(id);
                    changed = true;
                }
            }
        }
        openCards.clear();
        return changed;
    }

    private void appendText(TranscriptBlock b, String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        int before = b.length();
        b.append(s);
        retainedChars += b.length() - before;
    }

    /** Cards still awaiting their final event. A widget shows them as running. */
    public int openToolCards() {
        int n = 0;
        for (Deque<String> q : openCards.values()) {
            n += q.size();
        }
        return n;
    }

    /**
     * Forget a block as a pending card. Trim only removes closed blocks, so this normally finds
     * nothing; it is here because a card that stopped being a row must stop being a match target,
     * or a later final event would settle into a block that is no longer in the transcript.
     */
    private void forgetCard(String id) {
        for (java.util.Iterator<Map.Entry<String, Deque<String>>> it = openCards.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<String, Deque<String>> e = it.next();
            e.getValue().remove(id);
            if (e.getValue().isEmpty()) {
                it.remove();
            }
        }
    }

    /** Drop the oldest closed blocks until the retained text fits the budget. */
    private boolean trim() {
        if (retainedChars <= maxChars) {
            return false;
        }
        boolean removed = false;
        while (retainedChars > maxChars && !blocks.isEmpty()) {
            TranscriptBlock first = blocks.get(0);
            if (!first.isClosed() && blocks.size() > 1) {
                // never trim the block currently being written; trim past it instead
                TranscriptBlock next = blocks.get(1);
                if (!next.isClosed()) {
                    break;
                }
                blocks.remove(1);
                byId.remove(next.id());
                forgetCard(next.id());
                retainedChars -= next.length();
                trimmedBlocks++;
                removed = true;
                continue;
            }
            blocks.remove(0);
            byId.remove(first.id());
            forgetCard(first.id());
            retainedChars -= first.length();
            trimmedBlocks++;
            removed = true;
        }
        return removed;
    }

    // ── reading ───────────────────────────────────────────────────────────────

    private boolean viewDirty = true;

    /** The rows the transcript actually displays, after the verbose filter. */
    public List<TranscriptBlock> visible() {
        if (viewDirty) {
            view.clear();
            for (TranscriptBlock b : blocks) {
                if (!verbose && BlockStyle.forBlock(b).verboseOnly()) {
                    continue;
                }
                view.add(b);
            }
            viewDirty = false;
        }
        return view;
    }

    /** All retained blocks before any pane-specific visibility filters. */
    public List<TranscriptBlock> allBlocks() {
        return java.util.Collections.unmodifiableList(blocks);
    }

    public TranscriptBlock visibleAt(int row) {
        List<TranscriptBlock> v = visible();
        return row >= 0 && row < v.size() ? v.get(row) : null;
    }

    /**
     * Every block, filtered or not. The view never uses this — it shows {@link #visible()} — but a
     * dump or a test has to see the verbose rows too, or it cannot tell "the engine said nothing"
     * from "the filter hid it".
     */
    public List<TranscriptBlock> blocks() {
        return List.copyOf(blocks);
    }

    /** The tool cards in the transcript, oldest first, whatever the verbose filter does to them. */
    public List<TranscriptBlock> toolCards() {
        List<TranscriptBlock> out = new ArrayList<>();
        for (TranscriptBlock b : blocks) {
            if (b.isToolCard()) {
                out.add(b);
            }
        }
        return out;
    }

    public int visibleSize() {
        return visible().size();
    }

    public int totalBlocks() {
        return blocks.size();
    }

    public int trimmedBlocks() {
        return trimmedBlocks;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean value) {
        if (verbose != value) {
            verbose = value;
            viewDirty = true;
            rebuildView();
            fire(true, false);
        }
    }

    /** Floor of 1 KiB keeps a test budget honest without letting the transcript shrink to nothing. */
    public void setMaxChars(int value) {
        this.maxChars = Math.max(1024, value);
    }

    public int retainedChars() {
        return retainedChars;
    }

    /** The block currently receiving output, or null when everything is closed. */
    public TranscriptBlock openBlock() {
        return openBlockId == null ? null : byId.get(openBlockId);
    }

    // ── listeners ─────────────────────────────────────────────────────────────

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void rebuildView() {
        viewDirty = true;
        visible();
    }

    private void fire(boolean structural, boolean text) {
        if (!structural && !text) {
            return;
        }
        List<Listener> copy = new ArrayList<>(listeners);
        for (Listener l : copy) {
            l.transcriptChanged(structural);
        }
    }

    /** Forget everything. Used when switching projects or after a session reload. */
    public void clear() {
        EDT.check("TranscriptModel.clear");
        blocks.clear();
        byId.clear();
        view.clear();
        openCards.clear();
        openBlockId = null;
        retainedChars = 0;
        trimmedBlocks = 0;
        viewDirty = true;
        fire(true, false);
    }
}
