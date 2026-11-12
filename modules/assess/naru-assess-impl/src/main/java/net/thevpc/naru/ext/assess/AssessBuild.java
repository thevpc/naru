package net.thevpc.naru.ext.assess;

import java.nio.file.Path;

/**
 * Build tooling of an asset, so checks stay independent of the technology: compile, run the
 * visible tests, and run the hidden acceptance tests of a task.
 */
public interface AssessBuild {
    AssessProcess.Result compile(Path dir);

    AssessProcess.Result test(Path dir);

    /** Runs the task's hidden tests on top of the model's work. Never modifies the model's own files. */
    HiddenOutcome hiddenTests(AssessTask task, Path dir);

    /** Hidden test results: how many ran, how many passed, and the first failure lines. */
    record HiddenOutcome(int total, int passed, String detail) {
    }
}
