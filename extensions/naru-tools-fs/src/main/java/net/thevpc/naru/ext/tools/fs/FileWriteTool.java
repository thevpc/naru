package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.DefaultNaruTool;

/**
 * Writes (or overwrites) a file on disk.
 */
public class FileWriteTool extends DefaultNaruTool {
    public FileWriteTool() {
        // write, not just fs: overwriting a file is a write effect, and the plan mode
        // veto keys on exactly this tag. Wearing fs alone would let file_write through
        // a read-only mode the moment the task had been granted fs.
        super("file_write", new String[]{NaruToolTags.FILE_SYSTEM, NaruToolTags.WRITE});
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
        return FileToolHelper.fileWrite(context.task()
                , context.stringArg("path").orNull()
                , context.stringArg("content").orNull()
                , context.booleanArg("dry").orNull()
        );
    }

}
