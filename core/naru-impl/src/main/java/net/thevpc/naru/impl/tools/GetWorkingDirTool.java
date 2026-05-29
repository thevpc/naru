package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;

/**
 * Runs {@code mvn compile} in a Maven project directory.
 */
public class GetWorkingDirTool implements NaruTool {

    public GetWorkingDirTool() {
    }

    @Override
    public String name() {
        return "pwd";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "return current working dir";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(session)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        return context.session().workingDir().toString();
    }

}
