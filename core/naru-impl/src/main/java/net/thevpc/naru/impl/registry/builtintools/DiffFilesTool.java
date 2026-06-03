package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.impl.util.ToolHelper;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes (or overwrites) a file on disk.
 */
public class DiffFilesTool implements NaruTool {
    @Override
    public String name() {
        return "diff";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Compare two text files and return their differences. " +
                "Useful for comparing .ntx source files or checking what changed between versions.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(session),
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
