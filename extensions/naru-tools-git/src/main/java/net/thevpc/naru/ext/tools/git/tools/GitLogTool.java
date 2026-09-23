package net.thevpc.naru.ext.tools.git.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.ext.tools.git.GitHelper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;

import java.text.SimpleDateFormat;

public class GitLogTool extends DefaultNaruTool {

    public GitLogTool() {
        super("git_log", new String[]{NaruToolTags.DEV, NaruToolTags.GIT});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Show commit logs.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task),
                NaruToolParameter.integer("max_count", "Max commits to show (default 10)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        Integer max = context.intArg("max_count").orNull();
        if (max == null || max <= 0) max = 10;

        try (Git git = GitHelper.openGit(context.task())) {
            Iterable<RevCommit> commits = git.log().setMaxCount(max).call();
            StringBuilder sb = new StringBuilder();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

            for (RevCommit c : commits) {
                sb.append("commit ").append(c.getName()).append("\n");
                sb.append("Author: ").append(c.getAuthorIdent().getName()).append(" <").append(c.getAuthorIdent().getEmailAddress()).append(">\n");
                sb.append("Date:   ").append(sdf.format(c.getAuthorIdent().getWhenAsInstant())).append("\n\n");
                sb.append("    ").append(c.getShortMessage()).append("\n\n");
            }

            if (sb.length() == 0) return "No commits found.";
            return sb.toString();
        } catch (Exception e) {
            return "ERROR running git_log: " + e.getMessage();
        }
    }
}
