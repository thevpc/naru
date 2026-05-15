package net.thevpc.naru.impl.registry.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruToolRegistry;

public class RunScriptTool implements NaruTool {

    @Override
    public String getName() {
        return "run_script";
    }

    @Override
    public String getDescription() {
        return "Requests the agent to start executing a specified script. The script will run sequentially line by line.";
    }

    @Override
    public NaruToolDefinition getDefinition() {
        return NaruToolRegistry.buildDefinition(
                getName(),
                getDescription(),
                NaruToolParameter.string("script_name", "Name of the script to run.", true)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String scriptName = context.stringArg("script_name").onBlankEmpty().orNull();
        if (scriptName == null) {
            return "Error: script_name is required";
        }

        String r= "running script " + scriptName;
        context.session().runner().invokeScript(context.session(), scriptName);
        return r;
    }
}
