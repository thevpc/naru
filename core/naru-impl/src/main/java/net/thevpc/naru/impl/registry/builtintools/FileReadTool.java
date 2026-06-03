package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.impl.util.ToolHelper;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathPermission;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads a text file from disk and returns its content.
 */
public class FileReadTool implements NaruTool {

    public FileReadTool() {
    }

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Read contents of a text file. If line constraints are provided, it performs a strict logical 'AND' " +
                "to slice the exact line range (0-indexed or 1-indexed depending on preference, standardizing on 1-based here). " +
                "Omitting bounds reads from line 1 up to a safe threshold (" + ToolHelper.DEFAULT_MAX_LINES + " lines).";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(session),
                NaruToolParameter.string("path", "File path to read", true).build(),
                NaruToolParameter.integer("line_start", "First line to read (1-based, optional, defaults to 1)", false, 1).build(),
                NaruToolParameter.integer("line_end", "Last line to read (inclusive, optional, defaults to start + " + ToolHelper.DEFAULT_MAX_LINES + ")", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return ToolHelper.fileRead(context.task(),context.stringArg("path").orNull(),
                context.longArg("line_start").orNull(),
                context.longArg("line_end").orNull()
                );

    }


}
