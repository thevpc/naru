package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.mode.NaruMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;

public class RoutineRunTool implements NaruTool {

    @Override
    public String name() {
        return "routine_run";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Requests the agent to start executing a specified script. The script will run sequentially line by line.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(session),
                NaruToolParameter.string("routine_name", "Name of the routine to run.", true)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String scriptName = context.stringArg("script_name").onBlankEmpty().orNull();
        if (scriptName == null) {
            return "Error: routine_name is required";
        }

        String r= "running routine " + scriptName;
        context.session().agent().invokeRoutine(context.session(), scriptName);
        return r;
    }

    public boolean acceptMode(NaruMode mode) {
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
