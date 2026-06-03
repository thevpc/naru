package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.impl.util.ToolHelper;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/**
 * Writes (or overwrites) a file on disk.
 */
public class FileWriteTool implements NaruTool {

    public FileWriteTool() {
    }

    @Override
    public String name() {
        return "file_write";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Write content to a file, creating it (and any parent directories) if necessary. Overwrites existing content.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return
                new NaruToolDefinitionFunction(
                        name(), getDescription(session),
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

    public boolean acceptMode(NaruPromptMode mode) {
        NaruStandardMode m = mode.asStandardMode().orNull();
        if (m != null) {
            switch (m) {
                case IMPLEMENT:
                    return true;
            }
        }
        return false;
    }

}
