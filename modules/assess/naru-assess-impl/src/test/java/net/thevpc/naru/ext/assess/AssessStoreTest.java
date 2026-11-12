package net.thevpc.naru.ext.assess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssessStoreTest {
    @Test
    void runSurvivesAJsonRoundTrip(@TempDir Path tmp) {
        net.thevpc.nuts.Nuts.require();
        AssessRun run = new AssessRun();
        run.id = "20260101-000000-demo";
        run.model = "ollama/demo:1b";
        run.assetId = "java-ref";
        run.startedAt = Instant.parse("2026-01-01T00:00:00Z");
        run.endedAt = Instant.parse("2026-01-01T00:10:00Z");
        AssessTaskResult r = new AssessTaskResult();
        r.taskId = "simple-fix-divide";
        r.tier = "simple";
        r.title = "Fix \"quoted\"\nnewline";
        r.status = AssessTaskResult.Status.COMPLETED;
        r.promptTokens = 1234;
        r.completionTokens = 56;
        r.toolCalls = 7;
        r.toolsByName.put("run_shell", 4);
        r.finalAnswer = "done: é \t tab";
        r.checks.add(new AssessTaskResult.CheckResult("builds", "compiles", 15, 1.0, "ok"));
        r.checks.add(new AssessTaskResult.CheckResult("hidden", "hidden", 35, 0.5, "1/2 failed"));
        run.results.add(r);

        AssessStore store = new AssessStore(tmp);
        store.save(run);
        AssessRun back = store.find("last").orElseThrow();

        assertEquals(run.id, back.id);
        assertEquals(run.model, back.model);
        assertEquals(run.startedAt, back.startedAt);
        AssessTaskResult b = back.results.get(0);
        assertEquals(r.title, b.title);
        assertEquals(1234, b.promptTokens);
        assertEquals(4, b.toolsByName.get("run_shell"));
        assertEquals(r.finalAnswer, b.finalAnswer);
        assertEquals(15 + 17.5, b.score(), 0.001);
        assertEquals(50, b.maxScore());
        assertTrue(store.find("2026").isPresent(), "unambiguous id prefix");
        assertTrue(AssessReports.run(back, true).stream().anyMatch(l -> l.contains("TOTAL")));
    }
}
