package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.routine.NaruRoutineManager;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;

public class RoutineAddLineTool implements NaruTool {

    @Override
    public String name() {
        return "routine_add_line";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Adds or updates a numbered line in a naru routine (internal REPL buffer). " +
                "This is NOT a shell command or external script. " +
                "Supports comments: use '#', '//', or 'REM' at the start (e.g., line=15, content='# Validate input'). " +
                "Comments get line numbers for ordering but are skipped during execution. " +
                "If routine_name is empty, uses the currently active routine.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(session),
                NaruToolParameter.string("routine_name", "Name of the routine to modify. If empty, uses the currently active routine.", false),
                NaruToolParameter.integer("line_number", "Line number to write (e.g. 10, 20,100). Supports gaps for easy insertion.", true),
                NaruToolParameter.string("command", "The instruction, condition, or loop directive to store at this line.", true)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String scriptName = context.stringArg("routine_name")
                .onBlankEmpty()
                .orElseGet(() -> context.session().routineManager().getCurrentRoutineName());

        Number lineNumObj = context.numberArg("line_number").orNull();
        if (lineNumObj == null) {
            return "Error: line_number is required";
        }
        int lineNum = lineNumObj.intValue();

        String command = context.stringArg("command").orNull();
        if (command == null) {
            return "Error: command is required";
        }

        NaruRoutineManager sm = context.session().routineManager();
        String oldName = sm.getCurrentRoutineName();
        sm.switchRoutine(scriptName);
        sm.putLine(lineNum, command);
        sm.switchRoutine(oldName);

        return "Successfully wrote line " + lineNum + " to script '" + scriptName + "'";
    }
}
