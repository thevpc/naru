package net.thevpc.naru.ext.assess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Validates the asset itself, with no model involved: every task must score full marks when
 * its reference solution is applied and clearly less on the starting state. A task that
 * fails either way is a broken benchmark, and its scores would mislead.
 */
class JavaRefAssetTest {
    private final JavaRefAsset asset = new JavaRefAsset();

    static List<String> taskIds() {
        return new JavaRefAsset().tasks().stream().map(AssessTask::id).toList();
    }

    @Test
    void indexListsExactlyTheShippedFiles() throws Exception {
        Path root = Path.of(JavaRefAsset.class.getResource("/net/thevpc/naru/ext/assess/java-ref/index.txt").toURI()).getParent();
        Set<String> onDisk;
        try (Stream<Path> s = Files.walk(root)) {
            onDisk = s.filter(Files::isRegularFile).map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> !p.equals("index.txt")).collect(Collectors.toSet());
        }
        assertEquals(onDisk, new HashSet<>(JavaRefAsset.index()), "regenerate index.txt: (cd java-ref && find . -type f ! -name index.txt | sed 's#^./##' | sort > index.txt)");
    }

    @Test
    void taskIdsAreUniqueAndCoverEveryTier() {
        List<AssessTask> tasks = asset.tasks();
        assertEquals(tasks.size(), tasks.stream().map(AssessTask::id).distinct().count());
        for (AssessTier t : AssessTier.values()) {
            assertTrue(tasks.stream().anyMatch(x -> x.tier() == t), "no task for tier " + t);
        }
        for (AssessTask t : tasks) {
            assertEquals(100, t.totalPoints(), t.id() + " should be worth 100 points");
        }
    }

    @ParameterizedTest
    @MethodSource("taskIds")
    void startingStateScoresLowAndSolutionScoresFull(String id, @TempDir Path tmp) throws Exception {
        AssessTask task = asset.tasks().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
        Path dir = tmp.resolve("work");
        Files.createDirectories(dir);
        asset.prepare(task, dir);
        var baseline = AssessContext.fingerprint(dir);

        double seed = score(task, dir, baseline);
        assertTrue(seed < 70, id + ": the untouched starting state must not pass, but scored " + seed);

        applySolution(id, dir);
        double solved = score(task, dir, baseline);
        assertEquals(100.0, solved, 0.001, id + ": the reference solution must earn every point");
    }

    private double score(AssessTask task, Path dir, java.util.Map<String, String> baseline) {
        AssessContext ctx = new AssessContext(asset, task, dir, baseline);
        double total = 0;
        for (AssessCheck c : task.checks()) {
            AssessCheck.Verdict v = c.evaluate(ctx);
            total += v.fraction() * c.points();
        }
        return total;
    }

    /** Copies the reference solution over the work dir and adds the new tests a model is asked to write. */
    private void applySolution(String id, Path dir) throws IOException, URISyntaxException {
        Path sol = Path.of(JavaRefAssetTest.class.getResource("/solutions/" + id).toURI());
        try (Stream<Path> s = Files.walk(sol)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                Path t = dir.resolve(sol.relativize(p).toString());
                Files.createDirectories(t.getParent());
                Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        StringBuilder tests = new StringBuilder("package ledger;\nimport org.junit.jupiter.api.Test;\nclass OwnAddedTest {\n");
        for (int i = 0; i < 6; i++) {
            tests.append("    @Test void t").append(i).append("() { }\n");
        }
        Files.writeString(dir.resolve("src/test/java/ledger/OwnAddedTest.java"), tests.append("}\n"));
    }
}
