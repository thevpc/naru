package net.thevpc.naru.ext.tools.git;

import net.thevpc.naru.api.task.NaruTask;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GitHelper {

    public static Git openGit(NaruTask task) throws IOException {
        Path projectPath = Paths.get(task.projectDir().toString());
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Repository repository = builder.findGitDir(projectPath.toFile())
                .build();
        if (repository == null || repository.getDirectory() == null) {
            throw new IOException("Not a git repository (or any parent directory): " + projectPath);
        }
        return new Git(repository);
    }
}
