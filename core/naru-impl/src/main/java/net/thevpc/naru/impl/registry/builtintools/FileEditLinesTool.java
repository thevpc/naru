package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.impl.util.ToolHelper;

/**
 * Reads a text file from disk and returns its content.
 */
public class FileEditLinesTool implements NaruTool {


    public FileEditLinesTool() {
    }

    @Override
    public String name() {
        return "file_edit_lines";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Insert, replace, or delete lines in a text file. " +
                "Supports 0-based indexing and negative indices (-1 = last line, -2 = second-to-last). " +
                "Use dry=true to preview changes without writing.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(session),
                NaruToolParameter.string("path", "File path to edit", true).build(),
                NaruToolParameter.integer("from", "Start line index (0-based inclusive, supports negatives)", true).build(),
                NaruToolParameter.integer("to", "End line index (0-based exclusive). Omit or equal to 'from' to insert.", false).build(),
                NaruToolParameter.string("content", "New lines (\\n separated). Leave empty to delete.", false).build(),
                NaruToolParameter.bool("dry", "If true, preview changes without modifying the file", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return ToolHelper.fileEdit(context.task(), context.stringArg("path").onBlankEmpty().orNull(),
                context.longArg("from").orNull(),
                context.longArg("to").orNull(),
                context.stringArg("content").orNull(),
                context.booleanArg("dry").orElse(false)
                );

    }

    public boolean acceptMode(NaruPromptMode mode) {
        NaruStandardMode m = mode.asStandardMode().orNull();
        if (m != null) {
            switch (m) {
                case IMPLEMENT:
                    return true;
            }
        }
        return false;
    }


}
