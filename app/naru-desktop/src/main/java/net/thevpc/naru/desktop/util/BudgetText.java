package net.thevpc.naru.desktop.util;

import net.thevpc.naru.desktop.model.ModelCallLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Budget tab's text, built from what this session actually measured.
 *
 * <p>It used to print a sentence saying that the numbers would appear when a service exposed them,
 * which is a panel that advertises its own emptiness. Everything here has a source: the calls the
 * desktop watched, and the token totals the engine reported on those calls. A provider that did not
 * answer a question is drawn as {@code not reported}, never as a zero — a cache that hits at 0% and
 * a provider that gives no cache accounting are different facts, and only one of them is a problem
 * worth looking at.
 */
public final class BudgetText {

    private BudgetText() {
    }

    /**
     * @param totals the session token line from the engine's usage listener, may be blank
     * @param summary the aggregate over the calls still held
     * @param calls the held calls, newest first, for the per-model and stop-reason breakdown
     */
    public static String render(String totals, ModelCallLog.Summary summary, List<ModelCallLog.Call> calls) {
        int silent = 0;
        boolean anyTokens = false;
        Map<String, ModelAgg> byModel = new LinkedHashMap<>();
        Map<String, Integer> byStop = new LinkedHashMap<>();
        for (ModelCallLog.Call c : calls) {
            if (c.producedNothing()) {
                silent++;
            }
            anyTokens |= c.promptTokens() >= 0;
            byModel.computeIfAbsent(c.modelLabel(), k -> new ModelAgg()).add(c);
            String stop = c.stopReason();
            if (stop != null && !stop.isBlank()) {
                byStop.merge(stop, 1, Integer::sum);
            }
        }

        StringBuilder sb = new StringBuilder();
        if (totals != null && !totals.isBlank()) {
            sb.append("Session totals · ").append(totals.strip()).append("\n");
        }
        sb.append("Calls in view · ").append(summary.calls())
                .append(" across ").append(summary.tasks()).append(" task")
                .append(summary.tasks() == 1 ? "" : "s").append(", ")
                .append(summary.models()).append(" model").append(summary.models() == 1 ? "" : "s")
                .append('\n');
        if (summary.droppedCalls() > 0) {
            // the cap is a view limit, not a session limit: say so, or the reader compares these
            // numbers against a budget extension that counted more
            sb.append("Earlier calls dropped from the view · ").append(summary.droppedCalls())
                    .append(" (figures below cover only the calls held)\n");
        }
        // an aggregate of unreported calls sums to zero, and zero is a fact the provider never
        // gave; the flags come from the calls because the summary has no "nothing reported" state
        sb.append("Tokens · ")
                .append(anyTokens ? tokens(summary.promptTokens()) + " in / " + tokens(summary.completionTokens()) + " out"
                        : "not reported").append('\n');
        sb.append("Cache · ");
        if (summary.cacheAccounted()) {
            sb.append("read ").append(tokens(summary.cacheReadTokens()))
                    .append(", write ").append(tokens(summary.cacheWriteTokens()))
                    .append(" · ").append(cacheShare(summary.cacheReadShare()));
        } else {
            sb.append("not reported");
        }
        sb.append('\n');
        sb.append("Latency · avg ").append(time(summary.averageMillis()))
                .append(" · slowest ").append(time(summary.slowestMillis())).append('\n');

        if (silent > 0) {
            sb.append("Outcome · ").append(silent).append(" call")
                    .append(silent == 1 ? "" : "s")
                    .append(" reported no text (open Requests to see which)\n");
        }
        if (!byModel.isEmpty()) {
            sb.append("\nPer model\n");
            for (Map.Entry<String, ModelAgg> e : byModel.entrySet()) {
                sb.append("  ").append(e.getKey()).append(" · ").append(e.getValue()).append('\n');
            }
        }
        if (!byStop.isEmpty()) {
            StringBuilder stops = new StringBuilder("\nStop reasons · ");
            byStop.forEach((k, v) -> stops.append(k).append(' ').append(v).append("  "));
            sb.append(stops.toString().stripTrailing()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * One model's share of the held calls. Each counter keeps the engine's {@code -1} meaning
     * separately: a provider that answers no usage question at all is reported as such, and a
     * mixture of reporting and silent calls sums only what was measured.
     */
    private static final class ModelAgg {
        private int calls;
        private long prompt;
        private long completion;
        private long cacheRead;
        private long millis;
        private int measured;
        private boolean anyTokens;
        private boolean anyCache;

        void add(ModelCallLog.Call c) {
            calls++;
            if (c.promptTokens() >= 0) {
                anyTokens = true;
                prompt += c.promptTokens();
                completion += Math.max(0, c.completionTokens());
            }
            if (c.hasCacheAccounting()) {
                anyCache = true;
                cacheRead += Math.max(0, c.cacheReadTokens());
            }
            if (c.durationMillis() >= 0) {
                millis += c.durationMillis();
                measured++;
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(calls).append(" calls · ");
            if (!anyTokens) {
                sb.append("tokens not reported");
            } else {
                sb.append(tokens(prompt)).append(" in / ").append(tokens(completion)).append(" out · ")
                        .append(cacheShare(!anyCache || prompt <= 0 ? -1
                                : Math.min(1, (double) cacheRead / prompt)));
            }
            sb.append(" · avg ").append(time(measured == 0 ? -1 : millis / measured));
            return sb.toString();
        }
    }

    private static String tokens(long n) {
        return n < 0 ? "not reported" : String.format("%,d", n);
    }

    private static String cacheShare(double share) {
        if (share < 0) {
            return "cache not reported";
        }
        return Math.round(share * 100) + "% of input served from cache";
    }

    private static String time(long millis) {
        if (millis < 0) {
            return "not measured";
        }
        String d = ToolCardText.duration(millis);
        return d == null ? "not measured" : d;
    }
}
