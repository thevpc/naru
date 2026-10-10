package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.nuts.text.NText;

/**
 * Reads a text file from disk and returns its content.
 */
public class FileReadTool extends DefaultNaruTool {

    public FileReadTool() {
        super("file_read", new String[]{NaruToolTags.FILE_SYSTEM});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Read contents of a text file. If line constraints are provided, it performs a strict logical 'AND' " +
                "to slice the exact line range (0-indexed or 1-indexed depending on preference, standardizing on 1-based here). " +
                "Omitting bounds reads from line 1 up to a safe threshold (" + FileToolHelper.DEFAULT_MAX_LINES + " lines).");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("path", "File path to read", true).build(),
                NaruToolParameter.integer("line_start", "First line to read (1-based, optional, defaults to 1)", false, 1).build(),
                NaruToolParameter.integer("line_end", "Last line to read (inclusive, optional, defaults to start + " + FileToolHelper.DEFAULT_MAX_LINES + ")", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return FileToolHelper.fileRead(context.task(),context.stringArg("path").orNull(),
                context.longArg("line_start").orNull(),
                context.longArg("line_end").orNull()
                );

    }


}
