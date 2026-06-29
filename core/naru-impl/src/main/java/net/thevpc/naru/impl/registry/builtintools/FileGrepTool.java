package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;
import net.thevpc.naru.impl.util.ToolHelper;

public class FileGrepTool extends DefaultNaruTool {

    private static final int MAX_OUTPUT_CHARS = 6_000;
    private static final int DEFAULT_CONTEXT = 2;
    private static final int DEFAULT_MAX_MATCHES = 50;

    public FileGrepTool() {
        super("file_grep", new String[]{NaruToolTags.FILE_SYSTEM});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Search for lines in a text file matching a pattern. Supports literal and regex search with surrounding context.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("path", "File to search", true).build(),
                NaruToolParameter.string("content_pattern", "Search pattern", true).build(),
                NaruToolParameter.bool("regex", "Treat pattern as regex", false, false).build(),
                NaruToolParameter.bool("case_sensitive", "Case-sensitive match", false, false).build(),
                NaruToolParameter.integer("context_lines", "Lines of context before/after match", false, DEFAULT_CONTEXT).build(),
                NaruToolParameter.integer("max_matches", "Maximum matches to return", false, DEFAULT_MAX_MATCHES).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return ToolHelper.fileGrep(
                context.task(), context.stringArg("path").onBlankEmpty().orNull(),
                context.stringArg("pattern").onBlankEmpty().orNull(),
                context.booleanArg("regex").orFalse(),
                context.booleanArg("case_sensitive").orFalse(),
                context.intArg("context_lines").orNull(),
                context.intArg("max_matches").orNull()
                );
    }


}