package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruToolCallEvent;

import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Which tool calls this activation already drew straight from the live stream.
 *
 * <p>A tool call reaches a desktop view twice: once as the lifecycle events the engine fires while
 * dispatching, and again as the durable history that is checkpointed and re-read. Both describe the
 * same call, and each view must show exactly one card or one row. The transcript and the tools list
 * are deliberately separate views over the same batch, so they share this index rather than each
 * inventing its own idea of what has already been drawn -- two views disagreeing about that is how
 * a session that ran tools ended up listing them twice once the checkpoint landed.
 *
 * <p>A call is marked as soon as it is seen live, on its opening event rather than only on its
 * result: the assistant message that carries the call is echoed by the store as a separate
 * checkpoint from the one that carries the result, so a replay that only skipped settled calls would
 * still open a second card from that first echo and then settle it.
 *
 * <p>Only a call seen live is marked, and only a call arriving from history is looked up, which is
 * what keeps the two consumers of one batch from interfering: whichever view runs first cannot make
 * the other one skip a restored session's own tool calls.
 *
 * <p>Only a call the model gave an id is remembered. A model that sends no id can legitimately run
 * the same tool twice, and with nothing to tell the two apart the honest failure mode is the extra
 * card rather than the missing one.
 */
public final class DrawnCalls {

    /** Bounded so a long-lived session cannot grow the set without limit; keys are marked once. */
    private static final int MAX_KEYS = 1024;

    /** Separates the task from the call id; no real id contains a NUL. */
    private static final String SEP = String.valueOf((char) 0);

    private final LinkedHashSet<String> keys = new LinkedHashSet<>();

    /** True when this call already has a card or a row from the live stream. */
    public boolean isMarked(NaruToolCallEvent event) {
        String key = key(event);
        return key != null && keys.contains(key);
    }

    /** Mark a call seen live so its durable echo is not drawn a second time. */
    public void mark(NaruToolCallEvent event) {
        String key = key(event);
        if (key == null) {
            return;
        }
        keys.add(key);
        while (keys.size() > MAX_KEYS) {
            Iterator<String> it = keys.iterator();
            it.next();
            it.remove();
        }
    }

    public void clear() {
        keys.clear();
    }

    /**
     * The identity of one call: its task, and the id the model chose to send. Null when the model
     * sent none, which is the case this index refuses to guess about.
     */
    private static String key(NaruToolCallEvent event) {
        if (event == null) {
            return null;
        }
        String id = event.callId();
        if (id == null || id.isBlank()) {
            return null;
        }
        return event.taskId() + SEP + id;
    }
}
