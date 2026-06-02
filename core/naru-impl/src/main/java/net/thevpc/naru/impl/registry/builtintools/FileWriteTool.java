package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/** Writes (or overwrites) a file on disk. */
public class FileWriteTool implements NaruTool {

    public FileWriteTool() {
    }

    @Override public String name() { return "file_write"; }
    @Override public String getDescription(NaruSession session) { return "Write content to a file, creating it (and any parent directories) if necessary. Overwrites existing content."; }
    @Override public NaruToolDefinition getDefinition(NaruSession session) { return
            new NaruToolDefinitionFunction(
                    name(), getDescription(session),
                    NaruToolParameter.string("path", "Destination file path (absolute or relative to project dir)", true).build(),
                    NaruToolParameter.string("content", "Full text content to write", true).build()
            );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String path    = context.stringArg("path").orNull();
        String content = context.stringArg("content").orNull();
        if (NBlankable.isBlank(path)) return "ERROR: 'path' is required.";
        if (content == null) content = "";

        NPath p = context.task().resolve(path);
        try {
            p.mkParentDirs().writeString(content);
            return "OK: wrote " + content.length() + " chars to " + p;
        } catch (Exception e) {
            return "ERROR writing file: " + e.getMessage();
        }
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
