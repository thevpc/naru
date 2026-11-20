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
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.DiffEntry;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class GitDiffTool extends DefaultNaruTool {

    public GitDiffTool() {
        super("git_diff", new String[]{NaruToolTags.DEV, NaruToolTags.GIT});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Show changes between commits, commit and working tree, etc.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task),
                NaruToolParameter.bool("staged", "If true, show staged changes (--cached)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        boolean cached = context.booleanArg("staged").orElse(false);
        try (Git git = GitHelper.openGit(context.task())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DiffFormatter formatter = new DiffFormatter(out);
            formatter.setRepository(git.getRepository());

            List<DiffEntry> diffs;
            if (cached) {
                diffs = git.diff().setCached(true).call();
            } else {
                diffs = git.diff().call();
            }

            for (DiffEntry entry : diffs) {
                formatter.format(entry);
            }

            String result = out.toString();
            if (result.isEmpty()) return "No diff output.";
            if (result.length() > 15_000) return result.substring(0, 15_000) + "\n... [diff truncated]";
            return result;
        } catch (Exception e) {
            return "ERROR running git_diff: " + e.getMessage();
        }
    }
}
