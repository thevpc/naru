package net.thevpc.naru.ext.assess;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The outcome of one task: score, per-check detail and what the run cost. */
public final class AssessTaskResult {
    public enum Status { COMPLETED, TIMEOUT, ERROR }

    public record CheckResult(String id, String description, int points, double earned, String detail) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("description", description);
            m.put("points", points);
            m.put("earned", earned);
            m.put("detail", detail);
            return m;
        }

        static CheckResult ofMap(Map<?, ?> m) {
            return new CheckResult(AssessJson.str(m, "id"), AssessJson.str(m, "description"),
                    (int) AssessJson.num(m, "points"), AssessJson.num(m, "earned"), AssessJson.str(m, "detail"));
        }
    }

    public String taskId;
    public String tier;
    public String title;
    public Status status = Status.ERROR;
    public String error;
    public final List<CheckResult> checks = new ArrayList<>();
    public long wallMillis;
    public long modelMillis;
    public long modelCalls;
    public long promptTokens;
    public long completionTokens;
    public long peakPromptTokens;
    public long toolCalls;
    public long toolErrors;
    public long userQuestions;
    public final Map<String, Integer> toolsByName = new LinkedHashMap<>();
    public String finalAnswer = "";
    public String workDir;
    /** Session events and tool calls, capped; for diagnosing why a run went the way it did. */
    public String transcript = "";

    public double score() {
        return checks.stream().mapToDouble(c -> c.earned() * c.points()).sum();
    }

    public int maxScore() {
        return checks.stream().mapToInt(CheckResult::points).sum();
    }

    /** True when every check earned all its points. */
    public boolean perfect() {
        return !checks.isEmpty() && checks.stream().allMatch(c -> c.earned() >= 1.0);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", taskId);
        m.put("tier", tier);
        m.put("title", title);
        m.put("status", status.name());
        m.put("error", error);
        m.put("score", score());
        m.put("maxScore", maxScore());
        m.put("wallMillis", wallMillis);
        m.put("modelMillis", modelMillis);
        m.put("modelCalls", modelCalls);
        m.put("promptTokens", promptTokens);
        m.put("completionTokens", completionTokens);
        m.put("peakPromptTokens", peakPromptTokens);
        m.put("toolCalls", toolCalls);
        m.put("toolErrors", toolErrors);
        m.put("userQuestions", userQuestions);
        m.put("toolsByName", new LinkedHashMap<>(toolsByName));
        m.put("finalAnswer", finalAnswer);
        m.put("workDir", workDir);
        m.put("transcript", transcript);
        List<Object> cs = new ArrayList<>();
        checks.forEach(c -> cs.add(c.toMap()));
        m.put("checks", cs);
        return m;
    }

    public static AssessTaskResult ofMap(Map<?, ?> m) {
        AssessTaskResult r = new AssessTaskResult();
        r.taskId = AssessJson.str(m, "taskId");
        r.tier = AssessJson.str(m, "tier");
        r.title = AssessJson.str(m, "title");
        try {
            r.status = Status.valueOf(AssessJson.str(m, "status"));
        } catch (RuntimeException e) {
            r.status = Status.ERROR;
        }
        r.error = AssessJson.str(m, "error");
        r.wallMillis = (long) AssessJson.num(m, "wallMillis");
        r.modelMillis = (long) AssessJson.num(m, "modelMillis");
        r.modelCalls = (long) AssessJson.num(m, "modelCalls");
        r.promptTokens = (long) AssessJson.num(m, "promptTokens");
        r.completionTokens = (long) AssessJson.num(m, "completionTokens");
        r.peakPromptTokens = (long) AssessJson.num(m, "peakPromptTokens");
        r.toolCalls = (long) AssessJson.num(m, "toolCalls");
        r.toolErrors = (long) AssessJson.num(m, "toolErrors");
        r.userQuestions = (long) AssessJson.num(m, "userQuestions");
        r.finalAnswer = AssessJson.str(m, "finalAnswer");
        r.workDir = AssessJson.str(m, "workDir");
        r.transcript = AssessJson.str(m, "transcript") == null ? "" : AssessJson.str(m, "transcript");
        if (m.get("toolsByName") instanceof Map<?, ?> t) {
            t.forEach((k, v) -> r.toolsByName.put(String.valueOf(k), v instanceof Number n ? n.intValue() : 0));
        }
        if (m.get("checks") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> cm) {
                    r.checks.add(CheckResult.ofMap(cm));
                }
            }
        }
        return r;
    }
}
