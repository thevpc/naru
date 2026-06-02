package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
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
    public String name() {
        return "file_append";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Append to the end of the file. If the file (and its path) does not exist, it will be created.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(session),
                NaruToolParameter.string("path", "Path of the file to append to", true).build(),
                NaruToolParameter.string("content", "content to append", true).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String path = context.stringArg("path").onBlankEmpty().orNull();
        if (path == null) return "ERROR: 'path' argument is required.";
        String content = context.stringArg("content").onBlankEmpty().orNull();
        if (content == null) return "ERROR: 'content' argument is required.";

        try {
            NPath p = context.task().resolve(path);
            p.mkParentDirs().writeString(content, NPathOption.APPEND);
            return "content appended successfully.";
        } catch (Exception e) {
            return "ERROR reading file: " + e.getMessage();
        }
    }


}
