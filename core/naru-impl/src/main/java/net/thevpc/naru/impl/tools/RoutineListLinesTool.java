package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;

import java.util.Map;
import java.util.TreeMap;

public class RoutineListLinesTool implements NaruTool {

    @Override
    public String getName() {
        return "routine_list_lines";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Lists the numbered lines of a naru routine (internal REPL buffer). " +
                "This is NOT a filesystem directory listing or shell command output. " +
                "Returns lines sorted by line_number in 'NN content' format. " +
                "If routine_name is empty, lists the currently active routine. " +
                "Use line_start/line_end to filter a range (e.g., show lines 10-30).";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                getName(),
                getDescription(session),
                // ✅ Optional: target a specific routine by name
                NaruToolParameter.string("routine_name",
                        "Name of the routine to list. If empty, uses the currently active routine.",
                        false),
                // ✅ Optional: filter by line range (useful for long routines)
                NaruToolParameter.integer("line_start",
                        "Optional: start line number for range filter (inclusive).",
                        false),
                NaruToolParameter.integer("line_end",
                        "Optional: end line number for range filter (inclusive).",
                        false)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String scriptName = context.stringArg("script_name")
                .onBlankEmpty()
                .orElseGet(() -> context.session().routineManager().getCurrentRoutineName());

        Number startNumObj = context.numberArg("line_start").orNull();
        Number endNumObj = context.numberArg("line_end").orNull();

        NaruRoutineManager sm = context.session().routineManager();
        // Temporarily switch context, put line, switch back
        String oldName = sm.getCurrentRoutineName();
        sm.switchRoutine(scriptName);
        TreeMap<Integer, String> lines = sm.getCurrentRoutine().getLines(x -> {
            if (startNumObj != null && x < startNumObj.intValue()) return false;
            return endNumObj == null || x <= endNumObj.intValue();
        });
        sm.switchRoutine(oldName);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : lines.entrySet()) {
            sb.append(e.getKey()).append(" ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }
}
