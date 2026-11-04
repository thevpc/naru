package net.thevpc.naru.ext.tools.git.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.tools.git.GitHelper;
import net.thevpc.nuts.text.NText;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;

public class GitCommitTool extends DefaultNaruTool {

    public GitCommitTool() {
        super("git_commit", new String[]{NaruToolTags.DEV, NaruToolTags.GIT, NaruToolTags.WRITE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Stage modified/new files and record changes to the repository.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task),
                NaruToolParameter.string("message", "Commit message", true).build(),
                NaruToolParameter.bool("all", "Stage all modified and deleted files before committing (-a)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String message = context.stringArg("message").onBlankEmpty().orNull();
        if (message == null) return "ERROR: 'message' is required.";
        boolean all = context.booleanArg("all").orElse(false);

        try (Git git = GitHelper.openGit(context.task())) {
            if (all) {
                git.add().addFilepattern(".").call();
            }

            RevCommit commit = git.commit().setMessage(message).call();
            return "SUCCESS: Committed " + commit.getName().substring(0, 8) + " - " + commit.getShortMessage();
        } catch (Exception e) {
            return "ERROR running git_commit: " + e.getMessage();
        }
    }
}
