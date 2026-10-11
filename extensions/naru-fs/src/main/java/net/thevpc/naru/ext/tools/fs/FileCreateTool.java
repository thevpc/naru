package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;

public class FileCreateTool extends DefaultNaruTool {

    public FileCreateTool() {
        super("file_create", new String[]{NaruToolTags.FILE_SYSTEM, NaruToolTags.WRITE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Create a new file with specified content. Fails if file already exists unless overwrite=true.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("path", "File path to create", true).build(),
                NaruToolParameter.string("content", "Initial file content", false).build(),
                NaruToolParameter.bool("overwrite", "If true, overwrite existing file", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return FileToolHelper.fileCreate(
                context.task(),
                context.stringArg("path").onBlankEmpty().orNull(),
                context.stringArg("content").orNull(),
                context.booleanArg("overwrite").orElse(false)
        );
    }
}
