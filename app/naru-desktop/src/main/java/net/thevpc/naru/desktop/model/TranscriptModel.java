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
         *                   indices moved; false when only the text of existing rows changed —
         *                   the rows in question are then listed by {@link #lastTextChanged()}
         */
        void transcriptChanged(boolean structural);
    }

    private final List<TranscriptBlock> blocks = new ArrayList<>();
    private final List<TranscriptBlock> view = new ArrayList<>();
    private final Map<String, TranscriptBlock> byId = new HashMap<>();
    /**
     * Blocks whose text changed in the most recent {@link #apply}. A text-only batch reports
     * these instead of a boolean alone, so a view refreshes exactly the rows that grew — the
     * difference between one row repainting and the whole card column being thrown away on
     * every flush tick while an answer streams.
     */
    private final List<TranscriptBlock> changedText = new ArrayList<>();

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
        changedText.clear();
        for (TranscriptEvent e : batch) {
            if (e instanceof TranscriptEvent.Chunk c) {
                boolean isNew = byId.get(c.blockId()) == null;
                TranscriptBlock b = blockFor(c.blockId(), c.mode(), c.taskId());
                structural |= isNew;
                if (c.directiveResult()) {
                    b.setDirectiveResult(true);
                }
                if (c.error()) {
                    // A failure is never transient: it is the one thing in a noisy mode the user
                    // has to be able to act on, so it survives the message that supersedes its
                    // mode's narration.
                    b.setTransient(false);
                }
                int before = b.length();
                String add = MsgText.plain(c.text());
                if (c.finalFragment() && !add.isEmpty() && !add.endsWith("\n")) {
                    add = add + "\n";
                }
                b.append(add);
                retainedChars += b.length() - before;
                noteTextChanged(b);
                // Chunk.finalFragment() ends a *message*, not the block: a run of whole
                // messages from one directive is still one block, and only Closed freezes it.
                // Closing here would make the next line in the same block undrawable.
                text = true;
            } else if (e instanceof TranscriptEvent.Opened o) {
                boolean isNew = byId.get(o.blockId()) == null;
                TranscriptBlock ob = blockFor(o.blockId(), o.mode(), o.taskId());
                if (o.directiveResult()) {
                    ob.setDirectiveResult(true);
                }
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
                    // An assistant answer is streamed as the raw model turn and later arrives
                    // again as the durable message. Attaching the message to the block that is
                    // already on screen turns that streaming row into the model's own card — the
                    // answer appears as it is written and settles in the place it already
                    // occupies — where creating a second block would show the same text twice.
                    TranscriptBlock streamed = (m.message().getRole() == net.thevpc.naru.api.agent.NaruRole.assistant)
                            ? streamedAnswerFor(m.taskId(), m.message().getContent()) : null;
                    boolean isNew = streamed == null && byId.get(m.blockId()) == null;
                    NaruLogMode mode = m.message().getRole() == net.thevpc.naru.api.agent.NaruRole.user
                            ? NaruLogMode.SCRIPT : NaruLogMode.AGENT_RESPONSE;
                    TranscriptBlock b = streamed != null ? streamed : blockFor(m.blockId(), mode, m.taskId());
                    b.setMessage(m.message(), m.sequence());
                    String body = m.message().getContent();
                    if (body == null) body = "";
                    if (!m.message().getParts().isEmpty()) {
                        for (var part : m.message().getParts()) body += "\n" + part.type() + ": " + part.payload();
                    }
                    if (streamed == null) {
                        appendText(b, body.endsWith("\n") || body.isEmpty() ? body : body + "\n");
                    }
                    noteTextChanged(b);
                    close(b.id());
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

    /**
     * The still-open raw-model block that the stored assistant message is the durable copy of, or
     * null when this message has no streamed counterpart to promote.
     * <p>
     * Matched by task and text on purpose: the raw stream and the stored message carry different
     * ids, and the whole point is to end up with one block rather than two. A blank stored body
     * never matches — a card is still due for a message whose text the stream did not show.
     */
    private TranscriptBlock streamedAnswerFor(long taskId, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String wanted = content.strip();
        for (TranscriptBlock b : blocks) {
            if (b.taskId() == taskId
                    && !b.isChatMessage()
                    && !b.isToolCard()
                    && b.mode() == NaruLogMode.MODEL_RESPONSE
                    && b.text().strip().equals(wanted)) {
                return b;
            }
        }
        return null;
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
        b.setTransient(BlockStyle.of(mode).lifecycle() == BlockStyle.Lifecycle.TRANSIENT);
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
            noteTextChanged(b);
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
        noteTextChanged(b);
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

    /** Record one block for {@link #lastTextChanged()}; a batch rarely touches more than a few. */
    private void noteTextChanged(TranscriptBlock b) {
        if (b != null && !changedText.contains(b)) {
            changedText.add(b);
        }
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

    /**
     * The rows the transcript actually displays.
     * <p>
     * Two rules, from {@link BlockStyle}: a block whose audience is {@link
     * BlockStyle.Audience#ENGINE_DETAIL} is kept only when the user asked for detail ({@code verbose});
     * a {@link BlockStyle.Lifecycle#TRANSIENT} block — live narration such as "Model: gpt-4o…" or
     * "Tool: file_read" — is dropped once a durable block lands after it, because the message it
     * announced has arrived and supersedes it. Transient blocks are only ever hidden here in the
     * conversation; {@link #blocks()} and the Logs tab keep them.
     */
    public List<TranscriptBlock> visible() {
        if (viewDirty) {
            view.clear();
            for (int i = 0; i < blocks.size(); i++) {
                TranscriptBlock b = blocks.get(i);
                BlockStyle style = BlockStyle.forBlock(b);
                if (!verbose && style.audience() == BlockStyle.Audience.ENGINE_DETAIL) {
                    continue;
                }
                if (!verbose && b.isTransient() && supersededByDurableAfter(i)) {
                    continue;
                }
                view.add(b);
            }
            viewDirty = false;
        }
        return view;
    }

    /**
     * Whether a durable block lands after {@code index} and so supersedes the narration before it.
     * <p>
     * "Durable" here means a real message, answer or tool card — the things a turn produces — not
     * merely a block whose mode happens to be durable. A later transient line does not supersede an
     * earlier one: a run of narration between two messages is one continuous "what the engine did",
     * and hiding all but the last would make it flicker.
     */
    private boolean supersededByDurableAfter(int index) {
        for (int i = index + 1; i < blocks.size(); i++) {
            TranscriptBlock later = blocks.get(i);
            if (later.isChatMessage() || later.isToolCard()
                    || later.mode() == NaruLogMode.MODEL_RESPONSE
                    || (later.mode() == NaruLogMode.AGENT_RESPONSE && !later.isTransient())) {
                return true;
            }
        }
        return false;
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

    /**
     * True while any block is still open: output is arriving, or a card has not settled yet.
     * <p>
     * This is deliberately not {@code openBlock() != null}: a host note or a stored message
     * opens a block of its own for the length of one batch, which leaves the "currently open"
     * pointer empty while a stream is still flowing into an older block. A reader that needs
     * "is the engine producing something right now" — the composer's Stop state — wants this
     * question answered from the blocks themselves.
     */
    public boolean hasOpenBlock() {
        for (TranscriptBlock b : blocks) {
            if (!b.isClosed()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The blocks whose text changed in the last {@link #apply}, in batch order. A view showing
     * a text-only change refreshes these rows and nothing else; an empty list means the batch
     * only moved rows, which is already covered by a structural reload.
     */
    public List<TranscriptBlock> lastTextChanged() {
        return java.util.Collections.unmodifiableList(changedText);
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
        changedText.clear();
        openBlockId = null;
        retainedChars = 0;
        trimmedBlocks = 0;
        viewDirty = true;
        fire(true, false);
    }
}
