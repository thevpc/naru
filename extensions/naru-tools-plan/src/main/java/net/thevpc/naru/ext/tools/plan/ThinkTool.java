package net.thevpc.naru.ext.tools.plan;

import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.task.NaruTask;

/**
 * No-op scratchpad tool for models without a native thinking/reasoning channel.
 * Gives such models a legitimate place to "think out loud" before acting,
 * mirroring what native reasoning models do implicitly.
 */
public class ThinkTool extends DefaultNaruTool {

    public ThinkTool() {
        // deliberately untagged: a no-op scratchpad is not a plan mutation, and sharing
        // the PLAN tag with the structural tools would hide thinking behind plan access
        super("think", new String[0]);
    }

    @Override
    public boolean isRelevant(NaruTask task) {
        // only expose to models that lack a native thinking/reasoning channel
        NaruModelConfig mc = task.model();
        if (mc == null) {
            return true;
        }
        return task.session().registry().provider(mc.provider())
                .flatMap(p -> p.getProtocol(mc, task.session()))
                .map(p -> !p.getCapabilities().isThinking())
                .orElse(true);
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Use this tool to think through something privately before answering or acting. "
                + "It performs no action and returns no result; use it for planning, reviewing "
                + "tool output, verifying assumptions, or deciding the next step.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("thought", "Your private reasoning notes", true).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return "ok";
    }
}
