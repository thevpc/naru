package net.thevpc.naru.ext.tools.routines;

import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

import java.util.Map;
import java.util.TreeMap;


public class RoutineListLinesTool extends DefaultNaruTool {

    @Override
    public String name() {
        return "routine_list_lines";
    }

    public RoutineListLinesTool() {
        super("routine_list_lines", new String[]{NaruToolTags.ROUTINE});
    }


    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Lists the numbered lines of a naru routine (internal REPL buffer). " +
                "This is NOT a filesystem directory listing or shell command output. " +
                "Returns lines sorted by line_number in 'NN content' format. " +
                "If routine_name is empty, lists the currently active routine. " +
                "Use line_start/line_end to filter a range (e.g., show lines 10-30).");
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
        return listLines(context.task(), context.stringArg("script_name").orNull(), context.numberArg("line_start").orNull(), context.numberArg("line_end").orNull());
    }


    public static String listLines(NaruTask task, String scriptName, Number startNumObj, Number endNumObj) {
        if (NBlankable.isBlank(scriptName)) {
            scriptName = task.editRoutineName();
        }

        // Temporarily switch context, put line, switch back
        String oldName = task.editRoutineName();
        task.useRoutine(scriptName).get();
        TreeMap<Integer, String> lines = task.editRoutine().get().getLinesSet(x -> {
            if (startNumObj != null && x < startNumObj.intValue()) return false;
            return endNumObj == null || x <= endNumObj.intValue();
        });
        task.useRoutine(oldName).get();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : lines.entrySet()) {
            sb.append(e.getKey()).append(" ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }

}
