package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;
import net.thevpc.naru.impl.util.ToolHelper;

/**
 * Reads a text file from disk and returns its content.
 */
public class FileAppendTool extends DefaultNaruTool {

    private static final int MAX_CHARS = 32_000;

    public FileAppendTool() {
        super("file_append", new String[]{NaruToolTags.FILE_SYSTEM,NaruToolTags.WRITE});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Append to the end of the file. If the file (and its path) does not exist, it will be created.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("path", "Path of the file to append to", true).build(),
                NaruToolParameter.string("content", "content to append", true).build(),
                NaruToolParameter.bool("dry", "If true, preview changes without modifying the file", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return ToolHelper.fileAppend(context.task(), context.stringArg("path").onBlankEmpty().orNull(),
                context.stringArg("content").onBlankEmpty().orNull(),
                context.booleanArg("dry").onBlankEmpty().orNull()
        );
    }

}
