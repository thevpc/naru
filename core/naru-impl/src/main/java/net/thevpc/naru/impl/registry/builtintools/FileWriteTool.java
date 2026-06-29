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
public class FileWriteTool extends DefaultNaruTool {
    public FileWriteTool() {
        super("file_write", new String[]{NaruToolTags.FILE_SYSTEM});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Write content to a file, creating it (and any parent directories) if necessary. Overwrites existing content.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return
                new NaruToolDefinitionFunction(
                        name(), getDescription(task),
                        NaruToolParameter.string("path", "Destination file path (absolute or relative to project dir)", true).build(),
                        NaruToolParameter.string("content", "Full text content to write", true).build(),
                        NaruToolParameter.bool("dry", "If true, preview changes without modifying the file", false).build()
                );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return ToolHelper.fileWrite(context.task()
                , context.stringArg("path").orNull()
                , context.stringArg("content").orNull()
                , context.booleanArg("dry").orNull()
        );
    }

}
