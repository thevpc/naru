package net.thevpc.naru.desktop.model;

import net.thevpc.naru.api.agent.NaruModelCallListener;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelKey;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.desktop.util.EDT;
import net.thevpc.naru.desktop.util.ToolCardText;
import net.thevpc.nuts.time.NDuration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The model-call history behind the Requests tab: one row per provider call, newest first, each
 * holding the request and the response in full.
 * <p>
 * The inspection seam ({@link NaruModelCallListener}) hands over the engine's own structured
 * objects, and this keeps them. The view it replaces truncated every message to 200 characters
 * inside one text dump, which is the one thing an inspector must not do: a prompt that is too
 * large, a tool result that was supposed to be cached, or an instruction that never reached the
 * model are all only visible in the whole. So nothing here is shortened — the list trims whole
 * calls from the back and counts what it dropped instead of quietly keeping a prefix.
 * <p>
 * EDT-confined and Swing-free like {@link ToolCallModel}, so it can be tested headless and a
 * future front end can share it.
 */
public final class ModelCallLog implements NaruModelCallListener {

    /**
     * Calls kept before the oldest is dropped. A request holds the whole conversation, so this
     * is the difference between a debug log and a memory leak: 200 calls of a long session is
     * already tens of megabytes of retained messages.
     */
    public static final int MAX_CALLS = 200;

    /** One recorded call, with the request and response exactly as the engine passed them. */
    public record Call(long sequence, long taskId, NaruModelKey model, NaruModelRequest request,
                       NaruResponse response, long durationMillis, Instant at) {

        public String modelLabel() {
            return model == null ? "unknown" : model.provider() + " / " + model.model();
        }

        public String provider() {
            return model == null ? "" : model.provider();
        }

        /** Wall clock the provider took, or null when nothing was measured. */
        public String latencyLabel() {
            return ToolCardText.duration(durationMillis);
        }

        public int messageCount() {
            return request == null || request.messages() == null ? 0 : request.messages().size();
        }

        public int toolDefinitionCount() {
            return request == null || request.tools() == null ? 0 : request.tools().size();
        }

        /** {@code -1} means the provider reported nothing; the UI must not read that as zero. */
        public int promptTokens() {
            return response == null ? -1 : response.getPromptTokens();
        }

        public int completionTokens() {
            return response == null ? -1 : response.getEvalTokens();
        }

        public int cacheReadTokens() {
            return response == null ? -1 : response.getCacheReadTokens();
        }

        public int cacheWriteTokens() {
            return response == null ? -1 : response.getCacheWriteTokens();
        }

        public boolean hasCacheAccounting() {
            return response != null && response.hasCacheAccounting();
        }

        /** Input tokens served from cache as a share of all input tokens; negative when unreported. */
        public double cacheReadShare() {
            int in = promptTokens();
            int read = cacheReadTokens();
            if (in <= 0 || read < 0) {
                return -1;
            }
            return (double) read / in;
        }

        public int toolCallCount() {
            return response == null || !response.hasToolCalls() ? 0 : response.getMessage().getToolCalls().size();
        }

        /** Why the call ended, or null when the provider did not say. */
        public String stopReason() {
            String reason = response == null ? null : response.getStopReason();
            return reason == null || reason.isBlank() ? null : reason;
        }

        /** The text the model answered with, or an empty string when it answered with tool calls. */
        public String answerText() {
            if (response == null || response.getMessage() == null) {
                return "";
            }
            String text = response.getMessage().getFilteredContent();
            return text == null ? "" : text;
        }

        /**
         * True when the call came back with neither text nor a tool call — the one state a row has
         * that deserves a hue, because a call like this ends a turn without moving it.
         */
        public boolean producedNothing() {
            return answerText().isBlank() && toolCallCount() == 0;
        }

        /** The tail of the conversation as sent: the last message that carried any text. */
        public String lastUserPreview() {
            if (request == null || request.messages() == null) {
                return "";
            }
            for (NaruMessage m : request.messages().reversed()) {
                if (m == null || m.getRole() == null) {
                    continue;
                }
                String text = m.getFilteredContent();
                if (text != null && !text.isBlank()) {
                    return singleLine(text);
                }
            }
            return "";
        }

        /** Case-folded haystack for the filter box: model, task, stop reason and prompt tail. */
        public String filterKey() {
            return (modelLabel() + " #" + taskId + " " + (stopReason() == null ? "" : stopReason())
                    + " " + lastUserPreview()).toLowerCase(java.util.Locale.ROOT);
        }

        private static String singleLine(String text) {
            int cut = text.indexOf('\n');
            String head = cut < 0 ? text : text.substring(0, cut);
            return head.length() > 240 ? head.substring(0, 240) : head;
        }
    }

    /**
     * Session totals for the summary strip, over the calls still held. Both latency figures are
     * {@code -1} when no call in view was timed — never a {@code 0}, which a reader would take for a
     * sub-millisecond call.
     */
    public record Summary(int calls, int tasks, int models, long promptTokens, long completionTokens,
                          long cacheReadTokens, long cacheWriteTokens, boolean cacheAccounted,
                          long averageMillis, long slowestMillis, int droppedCalls) {

        /**
         * Cached share of input tokens across the held calls, or {@code -1} when no call reported
         * cache accounting. That is not the same as a share of zero: a provider that never answers
         * the question must not be drawn as a cache that never hits.
         */
        public double cacheReadShare() {
            if (!cacheAccounted || promptTokens <= 0) {
                return -1;
            }
            return Math.min(1, (double) cacheReadTokens / promptTokens);
        }
    }

    public interface Listener {
        void modelCallsChanged();
    }

    private final List<Call> calls = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    /** Bumped on the engine's thread, which is not the EDT and may be two task threads at once. */
    private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong();
    /** Written on the EDT only, like {@link #calls}. */
    private int droppedCalls;

    /**
     * Called by the engine on the thread that made the call, so it must not block: the record is
     * built there and the mutation is handed to the EDT.
     */
    @Override
    public void onModelCall(NaruTask task, NaruModelKey model, NaruModelRequest request,
                            NaruResponse response, NDuration duration) {
        long id = sequence.incrementAndGet();
        long taskId = task == null ? -1 : task.id();
        long millis = duration == null ? -1 : duration.toMillis();
        Call call = new Call(id, taskId, model, request, response, millis, Instant.now());
        EDT.invoke(() -> add(call));
    }

    private void add(Call call) {
        EDT.check("ModelCallLog.add");
        calls.add(0, call);
        while (calls.size() > MAX_CALLS) {
            calls.remove(calls.size() - 1);
            droppedCalls++;
        }
        fire();
    }

    /** Newest first, as the list shows them. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public Call callAt(int index) {
        return index >= 0 && index < calls.size() ? calls.get(index) : null;
    }

    public Summary summary() {
        java.util.Set<Long> tasks = new java.util.HashSet<>();
        java.util.Set<String> models = new java.util.HashSet<>();
        long in = 0, out = 0, read = 0, write = 0, timed = 0, total = 0, slowest = 0;
        boolean cacheAccounted = false;
        for (Call c : calls) {
            tasks.add(c.taskId());
            models.add(c.modelLabel());
            in += Math.max(0, c.promptTokens());
            out += Math.max(0, c.completionTokens());
            read += Math.max(0, c.cacheReadTokens());
            write += Math.max(0, c.cacheWriteTokens());
            cacheAccounted |= c.hasCacheAccounting();
            if (c.durationMillis() >= 0) {
                timed++;
                total += c.durationMillis();
                slowest = Math.max(slowest, c.durationMillis());
            }
        }
        return new Summary(calls.size(), tasks.size(), models.size(), in, out, read, write,
                cacheAccounted, timed == 0 ? -1 : total / timed, timed == 0 ? -1 : slowest, droppedCalls);
    }

    /** Forget every call; used when the window switches session. */
    public void clear() {
        EDT.check("ModelCallLog.clear");
        calls.clear();
        droppedCalls = 0;
        fire();
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fire() {
        for (Listener l : new ArrayList<>(listeners)) {
            l.modelCallsChanged();
        }
    }
}
