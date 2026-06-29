package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;
import net.thevpc.naru.impl.util.ToolHelper;

public class FolderFindTool extends DefaultNaruTool {


    public FolderFindTool() {
        super("folder_find", new String[]{NaruToolTags.FILE_SYSTEM});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Find files by name/glob patterns and optionally search their contents. " +
                "CRITICAL: A strict logical 'AND' is performed across ALL filters. " +
                "Files must simultaneously match: Location Path AND Glob Filters (include/exclude) " +
                "AND Time Window Constraints (modified_after/before) AND Content Text Pattern (if provided). " +
                "Only files satisfying EVERY layer of this constraint chain are processed.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("path", "Directory to search", true).build(),
                NaruToolParameter.string("content_pattern", "Search text pattern inside file contents (optional)", false).build(),
                NaruToolParameter.bool("regex", "Treat pattern as regex", false, false).build(),
                NaruToolParameter.bool("case_sensitive", "Case-sensitive match", false, false).build(),
                NaruToolParameter.integer("context_lines", "Lines of context before/after match", false, ToolHelper.DEFAULT_CONTEXT).build(),
                NaruToolParameter.integer("max_matches", "Max matches per file", false, ToolHelper.DEFAULT_MAX_MATCHES).build(),
                NaruToolParameter.integer("max_files", "Max files to scan", false, ToolHelper.DEFAULT_MAX_FILES).build(),
                NaruToolParameter.string("include_filename", "Glob patterns to include file by name (comma-separated, e.g. '*.java,*.xml')", false).build(),
                NaruToolParameter.string("exclude_filename", "Glob patterns to exclude file by name (comma-separated, e.g. '*.java,*.xml')", false).build(),
                NaruToolParameter.bool("recursive", "Search subdirectories", false, true).build(),
                NaruToolParameter.string("modified_after", "ISO-8601 timestamp (e.g. 2026-05-01T00:00:00Z) or relative duration (e.g. -24h, -7d)", false).build(),
                NaruToolParameter.string("modified_before", "ISO-8601 timestamp or relative duration", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return ToolHelper.folderFind(
                context.task(),
                context.stringArg("path").orNull(),
                context.stringArg("pattern").orNull(),
                context.booleanArg("regex").orFalse(),
                context.booleanArg("case_sensitive").orNull(),
                context.intArg("context_lines").orNull(),
                context.intArg("max_matches").orNull(),
                context.intArg("max_files").orNull(),
                context.booleanArg("recursive").orNull(),
                context.stringArg("include").orNull(),
                context.stringArg("exclude").orNull(),
                context.stringArg("modified_after").orNull(),
                context.stringArg("modified_before").orNull()
        );
    }

}
