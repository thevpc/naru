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

public class FolderFindTool extends DefaultNaruTool {


    public FolderFindTool() {
        super("folder_find", new String[]{NaruToolTags.FILE_SYSTEM});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Find files by name/glob patterns and optionally search their contents. " +
                "CRITICAL: A strict logical 'AND' is performed across ALL filters. " +
                "Files must simultaneously match: Location Path AND Glob Filters (include/exclude) " +
                "AND Time Window Constraints (modified_after/before) AND Content Text Pattern (if provided). " +
                "Only files satisfying EVERY layer of this constraint chain are processed.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("pattern", "Glob pattern to match files by relative path or name (e.g. '**/*.java', 'sub/**/*.java')", false).build(),
                NaruToolParameter.string("path", "Directory to search (optional, defaults to current working directory)", false).build(),
                NaruToolParameter.string("content_pattern", "Search text pattern inside file contents (optional)", false).build(),
                NaruToolParameter.bool("regex", "Treat pattern as regex", false, false).build(),
                NaruToolParameter.bool("case_sensitive", "Case-sensitive match", false, false).build(),
                NaruToolParameter.integer("context_lines", "Lines of context before/after match", false, FileToolHelper.DEFAULT_CONTEXT).build(),
                NaruToolParameter.integer("max_matches", "Max matches per file", false, FileToolHelper.DEFAULT_MAX_MATCHES).build(),
                NaruToolParameter.integer("max_files", "Max files to scan", false, FileToolHelper.DEFAULT_MAX_FILES).build(),
                NaruToolParameter.string("include_filename", "Glob patterns to include file by name (comma-separated, e.g. '*.java,*.xml')", false).build(),
                NaruToolParameter.string("exclude_filename", "Glob patterns to exclude file by name (comma-separated, e.g. '*.java,*.xml')", false).build(),
                NaruToolParameter.bool("recursive", "Search subdirectories", false, true).build(),
                NaruToolParameter.string("modified_after", "ISO-8601 timestamp (e.g. 2026-05-01T00:00:00Z) or relative duration (e.g. -24h, -7d)", false).build(),
                NaruToolParameter.string("modified_before", "ISO-8601 timestamp or relative duration", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String path = context.stringArg("path").orNull();
        if (NBlankable.isBlank(path)) {
            path = context.task().workingDir().toString();
        }
        String contentPattern = context.stringArg("content_pattern").orNull();
        String glob = context.stringArg("pattern")
                .orElseGet(() -> context.stringArg("include")
                        .orElseGet(() -> context.stringArg("include_filename").orNull()));
        String exclude = context.stringArg("exclude")
                .orElseGet(() -> context.stringArg("exclude_filename").orNull());

        return FileToolHelper.folderFind(
                context.task(),
                path,
                contentPattern,
                context.booleanArg("regex").orFalse(),
                context.booleanArg("case_sensitive").orNull(),
                context.intArg("context_lines").orNull(),
                context.intArg("max_matches").orNull(),
                context.intArg("max_files").orNull(),
                context.booleanArg("recursive").orNull(),
                glob,
                exclude,
                context.stringArg("modified_after").orNull(),
                context.stringArg("modified_before").orNull()
        );
    }
}
