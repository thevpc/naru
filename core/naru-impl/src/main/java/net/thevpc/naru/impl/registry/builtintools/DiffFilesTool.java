package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;
import net.thevpc.naru.impl.util.ToolHelper;

/**
 * Writes (or overwrites) a file on disk.
 */
public class DiffFilesTool extends DefaultNaruTool {
    public DiffFilesTool() {
        super("diff", new String[]{NaruToolTags.FILE_SYSTEM});
    }


    @Override
    public String getDescription(NaruTask task) {
        return "Compare two text files and return their differences. " +
                "Useful for comparing .ntx source files or checking what changed between versions.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("file1", "Path to the first file (original).", true).build(),
                NaruToolParameter.string("file2", "Path to the second file (modified).", true).build(),
                NaruToolParameter.integer("context_lines", "Number of context lines around each change (default: 3).", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return ToolHelper.filesDiff(context.task(), context.stringArg("file1").orNull(), context.stringArg("file2").orNull(), context.numberArg("context_lines").orNull());
    }
}
