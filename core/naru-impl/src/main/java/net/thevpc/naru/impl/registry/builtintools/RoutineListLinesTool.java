package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;
import net.thevpc.naru.impl.util.ToolHelper;


public class RoutineListLinesTool extends DefaultNaruTool {

    @Override
    public String name() {
        return "routine_list_lines";
    }

    public RoutineListLinesTool() {
        super("routine_list_lines", new String[]{NaruToolTags.ROUTINE});
    }


    @Override
    public String getDescription(NaruTask task) {
        return "Lists the numbered lines of a naru routine (internal REPL buffer). " +
                "This is NOT a filesystem directory listing or shell command output. " +
                "Returns lines sorted by line_number in 'NN content' format. " +
                "If routine_name is empty, lists the currently active routine. " +
                "Use line_start/line_end to filter a range (e.g., show lines 10-30).";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                // ✅ Optional: target a specific routine by name
                NaruToolParameter.string("routine_name",
                        "Name of the routine to list. If empty, uses the currently active routine.",
                        false).build(),
                // ✅ Optional: filter by line range (useful for long routines)
                NaruToolParameter.integer("line_start",
                        "Optional: start line number for range filter (inclusive).",
                        false).build(),
                NaruToolParameter.integer("line_end",
                        "Optional: end line number for range filter (inclusive).",
                        false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return ToolHelper.listLines(context.task(), context.stringArg("script_name").orNull(), context.numberArg("line_start").orNull(), context.numberArg("line_end").orNull());
    }
}
