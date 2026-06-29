package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.mode.NaruStandardMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;

public class RoutineRunTool extends DefaultNaruTool {

    public RoutineRunTool() {
        super("routine_run", new String[]{NaruToolTags.EXECUTE, NaruToolTags.ROUTINE});
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Requests the agent to start executing a specified script. The script will run sequentially line by line.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.string("routine_name", "Name of the routine to run.", true).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String scriptName = context.stringArg("script_name").onBlankEmpty().orNull();
        if (scriptName == null) {
            return "Error: routine_name is required";
        }

        String r = "running routine " + scriptName;
        context.task().invokeRoutine(scriptName);
        return r;
    }


}
