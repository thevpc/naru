package net.thevpc.naru.ext.tools.git.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.tools.git.GitHelper;
import net.thevpc.nuts.text.NText;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;

public class GitStatusTool extends DefaultNaruTool {

    public GitStatusTool() {
        super("git_status", new String[]{NaruToolTags.DEV, NaruToolTags.GIT});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Show working tree status (staged, unstaged, untracked files, current branch).");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task));
    }

    @Override
    public String execute(NaruToolCallContext context) {
        try (Git git = GitHelper.openGit(context.task())) {
            Status status = git.status().call();
            String branch = git.getRepository().getBranch();

            StringBuilder sb = new StringBuilder();
            sb.append("On branch ").append(branch).append("\n");

            if (status.isClean()) {
                sb.append("Nothing to commit, working tree clean.\n");
                return sb.toString();
            }

            if (!status.getAdded().isEmpty() || !status.getChanged().isEmpty() || !status.getRemoved().isEmpty()) {
                sb.append("\nChanges to be committed:\n");
                for (String f : status.getAdded()) sb.append("  new file:   ").append(f).append("\n");
                for (String f : status.getChanged()) sb.append("  modified:   ").append(f).append("\n");
                for (String f : status.getRemoved()) sb.append("  deleted:    ").append(f).append("\n");
            }

            if (!status.getModified().isEmpty() || !status.getMissing().isEmpty()) {
                sb.append("\nChanges not staged for commit:\n");
                for (String f : status.getModified()) sb.append("  modified:   ").append(f).append("\n");
                for (String f : status.getMissing()) sb.append("  deleted:    ").append(f).append("\n");
            }

            if (!status.getUntracked().isEmpty()) {
                sb.append("\nUntracked files:\n");
                for (String f : status.getUntracked()) sb.append("  ").append(f).append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "ERROR running git_status: " + e.getMessage();
        }
    }
}
