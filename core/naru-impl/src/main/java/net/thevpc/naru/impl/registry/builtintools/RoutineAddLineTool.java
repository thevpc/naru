package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;
import net.thevpc.naru.impl.util.ToolHelper;

public class RoutineAddLineTool extends DefaultNaruTool {

    public RoutineAddLineTool() {
        super("routine_add_line", new String[]{NaruToolTags.ROUTINE,NaruToolTags.WRITE});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Adds or updates a numbered line in a naru routine (internal REPL buffer). " +
                "This is NOT a shell command or external script. " +
                "Supports comments: use '#', '//', or 'REM' at the start (e.g., line=15, content='# Validate input'). " +
                "Comments get line numbers for ordering but are skipped during execution. " +
                "If routine_name is empty, uses the currently active routine.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("routine_name", "Name of the routine to modify. If empty, uses the currently active routine.", false).build(),
                NaruToolParameter.integer("line_number", "Line number to write (e.g. 10, 20,100). Supports gaps for easy insertion.", true).build(),
                NaruToolParameter.string("command", "The instruction, condition, or loop directive to store at this line.", true).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return ToolHelper.routineAddLine(context.task(),
                context.stringArg("routine_name").orNull(),
                context.numberArg("line_number").orNull(),
                context.stringArg("command").orNull()
        );
    }
}
