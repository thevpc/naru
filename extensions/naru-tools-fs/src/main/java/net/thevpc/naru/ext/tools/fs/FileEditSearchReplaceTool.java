package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;

public class FileEditSearchReplaceTool extends DefaultNaruTool {

    public FileEditSearchReplaceTool() {
        super("file_edit_search_replace", new String[]{NaruToolTags.FILE_SYSTEM, NaruToolTags.WRITE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Search for an exact string in a text file and replace it with new content. " +
                "Optionally specify occurrence number (1-based, default 1). " +
                "Use dry=true to preview changes without writing.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("path", "File path to edit", true).build(),
                NaruToolParameter.string("search", "Exact text string to find", true).build(),
                NaruToolParameter.string("replace", "Replacement text string", true).build(),
                NaruToolParameter.integer("occurrence", "Which occurrence to replace (1-based, default 1)", false).build(),
                NaruToolParameter.bool("dry", "If true, preview changes without modifying the file", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return FileToolHelper.fileEditSearchReplace(
                context.task(),
                context.stringArg("path").onBlankEmpty().orNull(),
                context.stringArg("search").orNull(),
                context.stringArg("replace").orNull(),
                context.longArg("occurrence").orNull(),
                context.booleanArg("dry").orElse(false)
        );
    }
}
