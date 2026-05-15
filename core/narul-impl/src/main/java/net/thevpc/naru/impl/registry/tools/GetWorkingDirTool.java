package net.thevpc.naru.impl.registry.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruToolRegistry;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/** Runs {@code mvn compile} in a Maven project directory. */
public class GetWorkingDirTool implements NaruTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;
    private final NaruToolDefinition definition;

    public GetWorkingDirTool() {
        this.definition = NaruToolRegistry.buildDefinition(
                getName(), getDescription()
        );
    }

    @Override public String getName() { return "get_working_directory"; }
    @Override public String getDescription() { return "return current working dir"; }
    @Override public NaruToolDefinition getDefinition() { return definition; }

    @Override
    public String execute(NaruToolCallContext context) {
        return context.session().workingDir().toString();
    }

}
