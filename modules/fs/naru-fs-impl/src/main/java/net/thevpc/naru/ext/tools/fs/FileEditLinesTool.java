package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.nuts.text.NText;

/**
 * Reads a text file from disk and returns its content.
 */
public class FileEditLinesTool extends DefaultNaruTool {

    public FileEditLinesTool() {
        super("file_edit_lines", new String[]{NaruToolTags.FILE_SYSTEM,NaruToolTags.WRITE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Insert, replace, or delete lines in a text file. " +
                "Line numbers are the ones file_read shows: 1-based, 'to' is the last line affected. " +
                "Negative numbers count from the end (-1 = last line). " +
                "Use dry=true to preview changes without writing.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("path", "File path to edit", true).build(),
                NaruToolParameter.integer("from", "First line to replace or delete, as numbered by file_read (1-based; negatives count from the end). With no 'to', content is inserted before this line (use last line + 1 to append)", true).build(),
                NaruToolParameter.integer("to", "Last line to replace or delete (1-based, inclusive). Same as 'from' for a single line. Omit to insert instead of replace.", false).build(),
                NaruToolParameter.string("content", "New lines (\\n separated). Leave empty to delete.", false).build(),
                NaruToolParameter.bool("dry", "If true, preview changes without modifying the file", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return FileToolHelper.fileEdit(context.task(), context.stringArg("path").onBlankEmpty().orNull(),
                context.longArg("from").orNull(),
                context.longArg("to").orNull(),
                context.stringArg("content").orNull(),
                context.booleanArg("dry").orElse(false)
                );

    }

}
