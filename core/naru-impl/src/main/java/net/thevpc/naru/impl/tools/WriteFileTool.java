package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/** Writes (or overwrites) a file on disk. */
public class WriteFileTool implements NaruTool {

    public WriteFileTool() {
    }

    @Override public String getName() { return "write_file"; }
    @Override public String getDescription(NaruSession session) { return "Write content to a file, creating it (and any parent directories) if necessary. Overwrites existing content."; }
    @Override public NaruToolDefinition getDefinition(NaruSession session) { return
            new NaruToolDefinitionFunction(
                    getName(), getDescription(session),
                    NaruToolParameter.string("path", "Destination file path (absolute or relative to project dir)", true),
                    NaruToolParameter.string("content", "Full text content to write", true)
            );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String path    = context.stringArg("path").orNull();
        String content = context.stringArg("content").orNull();
        if (NBlankable.isBlank(path)) return "ERROR: 'path' is required.";
        if (content == null) content = "";

        NPath p = context.session().resolve(path);
        try {
            p.mkParentDirs().writeString(content);
            return "OK: wrote " + content.length() + " chars to " + p;
        } catch (Exception e) {
            return "ERROR writing file: " + e.getMessage();
        }
    }

}
