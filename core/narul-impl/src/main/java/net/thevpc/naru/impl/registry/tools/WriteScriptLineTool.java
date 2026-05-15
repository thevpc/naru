package net.thevpc.naru.impl.registry.tools;

import net.thevpc.naru.api.agent.NaruScriptManager;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruToolRegistry;

public class WriteScriptLineTool implements NaruTool {

    @Override
    public String getName() {
        return "write_script_line";
    }

    @Override
    public String getDescription() {
        return "Writes or overwrites a specific line number in a script. Used to build scripts incrementally.";
    }

    @Override
    public NaruToolDefinition getDefinition() {
        return NaruToolRegistry.buildDefinition(
                getName(),
                getDescription(),
                NaruToolParameter.string("script_name", "Name of the script to modify. If empty, uses the current default script.", true),
                NaruToolParameter.integer("line_number", "Line number to write (e.g. 10, 20).", true),
                NaruToolParameter.string("command", "The command to write at this line number.", true)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String scriptName = context.stringArg("script_name")
                .onBlankEmpty()
                .orElseGet(()->context.session().scriptManager().getCurrentScriptName());

        Number lineNumObj = context.numberArg("line_number").orNull();
        if(lineNumObj==null){
            return "Error: line_number is required";
        }
        int lineNum = lineNumObj.intValue();
        
        String command = context.stringArg("command").orNull();
        if (command == null) {
            return "Error: command is required";
        }

        NaruScriptManager sm = context.session().scriptManager();
        // Temporarily switch context, put line, switch back
        String oldName = sm.getCurrentScriptName();
        sm.switchScript(scriptName);
        sm.putLine(lineNum, command);
        sm.switchScript(oldName);

        return "Successfully wrote line " + lineNum + " to script '" + scriptName + "'";
    }
}
