package net.thevpc.naru.ext.assess;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All task results of one model against one asset. */
public final class AssessRun {
    public String id;
    public String model;
    public String assetId;
    public Instant startedAt;
    public Instant endedAt;
    public String options = "";
    public final List<AssessTaskResult> results = new ArrayList<>();

    public double score() {
        return results.stream().mapToDouble(AssessTaskResult::score).sum();
    }

    public int maxScore() {
        return results.stream().mapToInt(AssessTaskResult::maxScore).sum();
    }

    public long promptTokens() {
        return results.stream().mapToLong(r -> r.promptTokens).sum();
    }

    public long completionTokens() {
        return results.stream().mapToLong(r -> r.completionTokens).sum();
    }

    public long wallMillis() {
        return results.stream().mapToLong(r -> r.wallMillis).sum();
    }

    public long perfectCount() {
        return results.stream().filter(AssessTaskResult::perfect).count();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("model", model);
        m.put("asset", assetId);
        m.put("startedAt", String.valueOf(startedAt));
        m.put("endedAt", String.valueOf(endedAt));
        m.put("options", options);
        m.put("score", score());
        m.put("maxScore", maxScore());
        m.put("promptTokens", promptTokens());
        m.put("completionTokens", completionTokens());
        m.put("wallMillis", wallMillis());
        List<Object> rs = new ArrayList<>();
        results.forEach(r -> rs.add(r.toMap()));
        m.put("results", rs);
        return m;
    }

    public static AssessRun ofMap(Map<?, ?> m) {
        AssessRun r = new AssessRun();
        r.id = AssessJson.str(m, "id");
        r.model = AssessJson.str(m, "model");
        r.assetId = AssessJson.str(m, "asset");
        r.options = AssessJson.str(m, "options");
        try {
            r.startedAt = Instant.parse(AssessJson.str(m, "startedAt"));
            r.endedAt = Instant.parse(AssessJson.str(m, "endedAt"));
        } catch (RuntimeException e) {
            // unfinished or damaged run keeps null times
        }
        if (m.get("results") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> rm) {
                    r.results.add(AssessTaskResult.ofMap(rm));
                }
            }
        }
        return r;
    }
}
