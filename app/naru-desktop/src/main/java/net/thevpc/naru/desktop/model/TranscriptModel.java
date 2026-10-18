package net.thevpc.naru.desktop.model;

import net.thevpc.naru.desktop.host.TranscriptEvent;
import net.thevpc.naru.desktop.util.EDT;

import java.util.ArrayList;
import java.util.HashMap;
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

    // ── feeding ───────────────────────────────────────────────────────────────

    /** Apply one coalesced batch. Called on the EDT by the session model. */
    public void apply(List<TranscriptEvent> batch) {
        EDT.check("TranscriptModel.apply");
        boolean structural = false;
        boolean text = false;
        for (TranscriptEvent e : batch) {
            if (e instanceof TranscriptEvent.Chunk c) {
                boolean isNew = byId.get(c.blockId()) == null;
                TranscriptBlock b = blockFor(c.blockId(), c.mode());
                structural |= isNew;
                int before = b.length();
                String add = c.text();
                if (c.finalFragment() && !add.isEmpty() && !add.endsWith("\n")) {
                    add = add + "\n";
                }
                b.append(add);
                retainedChars += b.length() - before;
                if (c.finalFragment()) {
                    close(c.blockId());
                }
                text = true;
            } else if (e instanceof TranscriptEvent.Opened o) {
                boolean isNew = byId.get(o.blockId()) == null;
                blockFor(o.blockId(), o.mode());
                structural |= isNew;
                openBlockId = o.blockId();
            } else if (e instanceof TranscriptEvent.Closed cl) {
                structural |= close(cl.blockId()) != null;
            } else if (e instanceof TranscriptEvent.Lagged) {
                // the host already told us it dropped chunks; a resync line is structural
                structural = true;
            } else if (e instanceof TranscriptEvent.HostClosed) {
                structural |= closeOpen() != null;
            }
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

    private TranscriptBlock blockFor(String id, net.thevpc.naru.api.agent.NaruLogMode mode) {
        TranscriptBlock b = byId.get(id);
        if (b != null) {
            return b;
        }
        b = new TranscriptBlock(id, mode, blocks.size());
        blocks.add(b);
        byId.put(id, b);
        openBlockId = id;
        viewDirty = true;
        return b;
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
                retainedChars -= next.length();
                trimmedBlocks++;
                removed = true;
                continue;
            }
            blocks.remove(0);
            byId.remove(first.id());
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
                if (!verbose && BlockStyle.of(b.mode()).verboseOnly()) {
                    continue;
                }
                view.add(b);
            }
            viewDirty = false;
        }
        return view;
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
        openBlockId = null;
        retainedChars = 0;
        trimmedBlocks = 0;
        viewDirty = true;
        fire(true, false);
    }
}
