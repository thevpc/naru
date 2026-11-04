package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

public class FileGrepTool extends DefaultNaruTool {

    private static final int MAX_OUTPUT_CHARS = 6_000;
    private static final int DEFAULT_CONTEXT = 0;
    private static final int DEFAULT_MAX_MATCHES = 50;

    public FileGrepTool() {
        super("file_grep", new String[]{NaruToolTags.FILE_SYSTEM});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Search for lines matching a pattern in a text file or directory. Supports literal and regex search with surrounding context.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("pattern", "Search pattern", true).build(),
                NaruToolParameter.string("path", "File or directory to search (optional, defaults to current working directory)", false).build(),
                NaruToolParameter.string("include", "File pattern to include in the search (e.g. *.js, *.java)", false).build(),
                NaruToolParameter.bool("regex", "Treat pattern as regex", false, true).build(),
                NaruToolParameter.bool("case_sensitive", "Case-sensitive match", false, true).build(),
                NaruToolParameter.integer("context_lines", "Lines of context before/after match", false, DEFAULT_CONTEXT).build(),
                NaruToolParameter.integer("max_matches", "Maximum matches to return", false, DEFAULT_MAX_MATCHES).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String pattern = context.stringArg("pattern")
                .orElseGet(() -> context.stringArg("content_pattern").orNull());
        String path = context.stringArg("path").orNull();
        if (NBlankable.isBlank(path)) {
            path = context.task().workingDir().toString();
        }
        String include = context.stringArg("include").orNull();
        return FileToolHelper.fileGrep(
                context.task(),
                path,
                pattern,
                include,
                context.booleanArg("regex").orTrue(),
                context.booleanArg("case_sensitive").orTrue(),
                context.intArg("context_lines").orNull(),
                context.intArg("max_matches").orNull()
        );
    }
}