package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;

/**
 * Runs {@code mvn compile} in a Maven project directory.
 */
public class GetWorkingDirTool extends DefaultNaruTool {

    public GetWorkingDirTool() {
        super("pwd", new String[]{NaruToolTags.FILE_SYSTEM});
    }

    @Override
    public String name() {
        return "pwd";
    }

    @Override
    public String getDescription(NaruTask task) {
        return "return current working dir";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return context.task().workingDir().toString();
    }

}
