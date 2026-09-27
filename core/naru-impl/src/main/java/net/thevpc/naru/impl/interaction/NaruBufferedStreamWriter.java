package net.thevpc.naru.impl.interaction;

import net.thevpc.naru.api.agent.NaruInteraction;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.nuts.text.NMsg;

import java.util.EnumMap;
import java.util.Map;

/**
 * Holds streamed fragments until the stream ends, then reports them as one message.
 *
 * <p>For an interaction with nowhere to draw incrementally -- a headless session
 * forwarding to an SSE listener, a logger, a test recorder -- a fragment is not a
 * meaningful unit of output. Emitting each token as its own event would give a host
 * hundreds of messages it has to reassemble, and a half-rendered markdown line is
 * worse than none. So fragments are joined per mode and reported once, which is
 * exactly what the batched path used to do.
 *
 * <p>Buffers are per mode because a single model turn interleaves reasoning and
 * answer: joined into one buffer, a thinking model's output would arrive as a single
 * message with the reasoning glued to the front of the reply.
 */
public class NaruBufferedStreamWriter {

    private final Map<NaruLogMode, StringBuilder> buffers = new EnumMap<>(NaruLogMode.class);

    /**
     * @param target where the finished message goes
     * @param mode   the mode this fragment belongs to
     * @param end    whether this is the last fragment for that mode
     */
    public void write(NaruInteraction target, NaruLogMode mode, NMsg fragment, boolean end) {
        NMsg finished = append(mode, fragment, end);
        if (finished != null) {
            target.write(mode, finished);
        }
    }

    /**
     * Adds a fragment and returns the joined message when that mode has just ended,
     * or null while it is still accumulating. The caller decides how the finished
     * message is emitted, which is what lets a closing interaction report it after
     * its own {@code closed} flag has already shut the normal path.
     */
    public NMsg append(NaruLogMode mode, NMsg fragment, boolean end) {
        if (fragment != null) {
            buffers.computeIfAbsent(mode, k -> new StringBuilder()).append(fragment.toString());
        }
        if (!end) {
            return null;
        }
        StringBuilder buffer = buffers.remove(mode);
        if (buffer == null || buffer.length() == 0) {
            return null;
        }
        return NMsg.ofC("%s", buffer);
    }

    /**
     * Returns anything still open, for a session that ends mid-stream: without this a
     * cancelled turn would throw away reasoning the model already paid to produce.
     */
    public java.util.List<Map.Entry<NaruLogMode, NMsg>> drain() {
        java.util.List<Map.Entry<NaruLogMode, NMsg>> out = new java.util.ArrayList<>();
        for (Map.Entry<NaruLogMode, StringBuilder> entry : buffers.entrySet()) {
            if (entry.getValue().length() > 0) {
                out.add(Map.entry(entry.getKey(), NMsg.ofC("%s", entry.getValue())));
            }
        }
        buffers.clear();
        return out;
    }

    /**
     * Reports anything still open through {@code target}, for an interaction that is
     * still willing to write.
     */
    public void flushAll(NaruInteraction target) {
        for (Map.Entry<NaruLogMode, NMsg> entry : drain()) {
            target.write(entry.getKey(), entry.getValue());
        }
    }
}
