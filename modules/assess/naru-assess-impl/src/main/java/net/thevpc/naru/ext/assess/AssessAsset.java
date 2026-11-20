package net.thevpc.naru.ext.assess;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Something a model is assessed against: a reference project plus the tasks to perform on
 * it. Assets are registered in {@link AssessAssets}.
 */
public interface AssessAsset {
    String id();

    String description();

    List<AssessTask> tasks();

    /** Materialises the starting state of {@code task} into the empty directory {@code dir}. */
    void prepare(AssessTask task, Path dir) throws IOException;

    /** Hidden acceptance material and build tooling for the task's checks. */
    AssessBuild build();
}
