package net.thevpc.naru.ext.assess;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Plain-text rendering of runs; shared by the console and the desktop app. */
public final class AssessReports {
    private AssessReports() {
    }

    public static String duration(long ms) {
        long s = ms / 1000;
        return s >= 60 ? (s / 60) + "m" + String.format("%02d", s % 60) + "s" : s + "s";
    }

    public static String tokens(long n) {
        return n >= 10_000 ? String.format(Locale.ROOT, "%.1fk", n / 1000.0) : String.valueOf(n);
    }

    public static String scoreText(double score, int max) {
        return String.format(Locale.ROOT, "%.0f/%d", score, max);
    }

    public static List<String> taskLine(AssessTaskResult r) {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT, "%-24s %-8s %-9s %7s  %7s  in %-7s out %-7s calls %-3d tools %-3d (err %d)",
                r.taskId, r.tier, r.status.name().toLowerCase(Locale.ROOT), scoreText(r.score(), r.maxScore()),
                duration(r.wallMillis), tokens(r.promptTokens), tokens(r.completionTokens), r.modelCalls, r.toolCalls, r.toolErrors));
        return out;
    }

    /** Header, one line per task, totals; with {@code detail} the failed checks under each task. */
    public static List<String> run(AssessRun run, boolean detail) {
        List<String> out = new ArrayList<>();
        out.add("run " + run.id + "  model " + run.model + "  asset " + run.assetId
                + (run.options == null || run.options.isBlank() ? "" : "  [" + run.options + "]"));
        for (AssessTaskResult r : run.results) {
            out.addAll(taskLine(r));
            if (detail) {
                if (r.error != null && !r.error.isBlank()) {
                    out.add("    error: " + oneLine(r.error));
                }
                for (AssessTaskResult.CheckResult c : r.checks) {
                    out.add(String.format(Locale.ROOT, "    %-4s %-14s %4.0f/%-3d %s", c.earned() >= 1.0 ? "ok" : "FAIL", c.id(),
                            c.earned() * c.points(), c.points(), c.earned() >= 1.0 ? "" : oneLine(c.detail())));
                }
                if (!r.toolsByName.isEmpty()) {
                    out.add("    tools: " + r.toolsByName.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining(", ")));
                }
                if (r.finalAnswer != null && !r.finalAnswer.isBlank()) {
                    out.add("    answer: " + truncate(oneLine(r.finalAnswer), 200));
                }
            }
        }
        out.add(String.format(Locale.ROOT, "TOTAL %s (%d/%d tasks perfect)  time %s  tokens in %s out %s",
                scoreText(run.score(), run.maxScore()), run.perfectCount(), run.results.size(), duration(run.wallMillis()),
                tokens(run.promptTokens()), tokens(run.completionTokens())));
        return out;
    }

    public static List<String> runs(List<AssessRun> runs) {
        List<String> out = new ArrayList<>();
        if (runs.isEmpty()) {
            out.add("no assessment runs yet (try: /assess run <model>)");
        }
        for (AssessRun r : runs) {
            out.add(String.format(Locale.ROOT, "%-18s %-32s %-9s %8s  %s  in %s out %s", r.id, r.model, r.assetId,
                    scoreText(r.score(), r.maxScore()), duration(r.wallMillis()), tokens(r.promptTokens()),
                    tokens(r.completionTokens())));
        }
        return out;
    }

    public static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    public static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
