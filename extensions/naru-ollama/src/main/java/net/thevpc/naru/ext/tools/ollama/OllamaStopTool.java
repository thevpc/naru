package net.thevpc.naru.ext.tools.ollama;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.text.NText;

public class OllamaStopTool extends DefaultNaruTool {

    public OllamaStopTool() {
        super("ollama_stop", new String[]{NaruToolTags.AI, NaruToolTags.EXECUTE});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Stop the Ollama server process.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(task),
                NaruToolParameter.bool("force_all", "Force stop all Ollama instances even if not spawned by NARU (default: false)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        boolean forceAll = context.booleanArg("force_all").orElse(false);
        NaruTask task = context.task();
        boolean ok = OllamaService.of().stop(task.session(), forceAll, msg -> task.log(NaruLogMode.TRACE, msg));
        if (ok) {
            return "SUCCESS: Ollama server stopped.";
        } else {
            return "Ollama was not stopped or was not started by NARU. Use force_all=true to stop external instances.";
        }
    }
}
