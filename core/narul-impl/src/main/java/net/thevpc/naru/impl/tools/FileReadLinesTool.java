package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathPermission;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads a text file from disk and returns its content.
 */
public class FileReadLinesTool implements NaruTool {

    private static final int MAX_CHARS = 32_000;

    public FileReadLinesTool() {
    }

    @Override
    public String getName() {
        return "file_read_lines";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Read lines of a text file by providing the path and line numbers.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return NaruRegistry.buildDefinition(
                getName(), getDescription(session),
                NaruToolParameter.string("path", "Path of the file to read (absolute or relative to project dir)", true),
                NaruToolParameter.string("from", "first line index inclusive, when negative will do a tail (-1 is the last line)", false),
                NaruToolParameter.string("to", "last line index inclusive, when negative will do a tail", false)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String path = context.stringArg("path").onBlankEmpty().orNull();
        if (path == null) return "ERROR: 'path' argument is required.";

        NPath p = context.session().resolve(path);
        if (!p.exists()) return "ERROR: File not found: " + p;
        if (!p.permissions().contains(NPathPermission.CAN_READ)) return "ERROR: File is not readable: " + p;

        Long from = context.longArg("from").orNull();
        Long to = context.longArg("to").orNull();

        try {
            String content=p.lines(from,to).stream().collect(Collectors.joining("\n"));
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
