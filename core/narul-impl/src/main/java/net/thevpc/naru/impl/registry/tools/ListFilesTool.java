package net.thevpc.naru.impl.registry.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruToolRegistry;
import net.thevpc.nuts.expr.NGlob;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lists files in a directory, optionally filtered by a glob pattern.
 */
public class ListFilesTool implements NaruTool {

    private static final int MAX_RESULTS = 200;
    private final NaruToolDefinition definition;

    public ListFilesTool() {
        this.definition = NaruToolRegistry.buildDefinition(
                getName(), getDescription(),
                NaruToolParameter.string("directory", "Directory to list (absolute or relative to project dir)", true),
                NaruToolParameter.string("pattern", "Glob pattern, e.g. '**/*.java' (optional, default: '*')", false),
                NaruToolParameter.bool("recursive", "If true, search sub-directories (default: false)", false)
        );
    }

    @Override
    public String getName() {
        return "list_files";
    }

    @Override
    public String getDescription() {
        return "List files in a directory. Supports glob patterns and recursive search.";
    }

    @Override
    public NaruToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String dir = context.stringArg("directory").orNull();
        String pattern = context.stringArg("pattern").orNull();
        boolean recursive = context.booleanArg("recursive").orElse(false);
        if (NBlankable.isBlank(dir)) dir = ".";
        if (NBlankable.isBlank(pattern)) pattern = recursive ? "**/*" : "*";

        NPath root = context.session().resolve(dir);
        if (!root.isDirectory()) return "ERROR: Not a directory: " + root;

        List<String> results = new ArrayList<>();
        Pattern matcher = NGlob.of().toPattern(pattern);

        int maxDepth = recursive ? Integer.MAX_VALUE : 1;
        try (NStream<NPath> walk = root.walk(maxDepth)) {
            walk.filter(NPath::isRegularFile)
                    .filter(p -> matcher.matcher(root.relativize(p).orElse("")).matches())
                    .sorted()
                    .limit(MAX_RESULTS)
                    .forEach(p -> results.add(root.relativize(p).orElse("")));
        } catch (Exception e) {
            return "ERROR listing files: " + e.getMessage();
        }

        if (results.isEmpty()) return "No files found matching '" + pattern + "' in " + root;
        return String.join("\n", results) + (results.size() == MAX_RESULTS ? "\n... (truncated)" : "");
    }

    private static String getString(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : v.toString();
    }

    private static boolean getBoolean(Map<String, Object> args, String key, boolean def) {
        Object v = args == null ? null : args.get(key);
        if (v == null) return def;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(v.toString());
    }
}
