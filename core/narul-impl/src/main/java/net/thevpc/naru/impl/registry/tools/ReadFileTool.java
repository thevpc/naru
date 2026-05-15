package net.thevpc.naru.impl.registry.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruToolRegistry;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathPermission;

import java.util.Map;

/**
 * Reads a text file from disk and returns its content.
 */
public class ReadFileTool implements NaruTool {

    private static final int MAX_CHARS = 32_000;
    private final NaruToolDefinition definition;

    public ReadFileTool() {
        this.definition = NaruToolRegistry.buildDefinition(
                getName(), getDescription(),
                NaruToolParameter.string("path", "Path of the file to read (absolute or relative to project dir)", true)
        );
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "Read the contents of a text file.";
    }

    @Override
    public NaruToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String path = context.stringArg("path").onBlankEmpty().orNull();
        if (path == null) return "ERROR: 'path' argument is required.";

        NPath p = context.session().resolve(path);
        if (!p.exists()) return "ERROR: File not found: " + p;
        if (!p.getPermissions().contains(NPathPermission.CAN_READ)) return "ERROR: File is not readable: " + p;

        try {
            String content = p.readString();
            if (content.length() > MAX_CHARS) {
                content = content.substring(0, MAX_CHARS) + "\n... [truncated at " + MAX_CHARS + " chars]";
            }
            return content;
        } catch (Exception e) {
            return "ERROR reading file: " + e.getMessage();
        }
    }

    private static String getArg(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : v.toString();
    }
}
