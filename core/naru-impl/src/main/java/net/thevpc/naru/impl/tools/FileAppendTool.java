package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathOption;

/**
 * Reads a text file from disk and returns its content.
 */
public class FileAppendTool implements NaruTool {

    private static final int MAX_CHARS = 32_000;

    public FileAppendTool() {
    }

    @Override
    public String getName() {
        return "file_append";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Append to the end of the file. If the file (and its path) does not exist, it will be created.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return NaruRegistry.buildDefinition(
                getName(), getDescription(session),
                NaruToolParameter.string("path", "Path of the file to append to", true),
                NaruToolParameter.string("content", "content to append", true)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String path = context.stringArg("path").onBlankEmpty().orNull();
        if (path == null) return "ERROR: 'path' argument is required.";
        String content = context.stringArg("content").onBlankEmpty().orNull();
        if (content == null) return "ERROR: 'content' argument is required.";

        try {
            NPath p = context.session().resolve(path);
            p.mkParentDirs().writeString(content, NPathOption.APPEND);
            return "content appended successfully.";
        } catch (Exception e) {
            return "ERROR reading file: " + e.getMessage();
        }
    }


}
