package net.thevpc.naru.impl.registry.tools;

import net.thevpc.naru.api.agent.NaruSessionContext;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruToolRegistry;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/**
 * Runs {@code mvn compile} in a Maven project directory.
 */
public class SetWorkingDirTool implements NaruTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;
    private final NaruToolDefinition definition;

    public SetWorkingDirTool() {
        this.definition = NaruToolRegistry.buildDefinition(
                getName(), getDescription(),
                NaruToolParameter.string("work_dir", "Path to the change to, can be relative (to session workdir) or absolute. when empty, return to project dir", false)
        );
    }

    @Override
    public String getName() {
        return "set_working_directory";
    }

    @Override
    public String getDescription() {
        return "update working dir and return the absolute path. when no argument, switches to project directory";
    }

    @Override
    public NaruToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String workingDirArg = context.stringArg("work_dir").orNull();
        NPath neworkDir = NBlankable.isNonBlank(workingDirArg)
                ? context.session().resolve(workingDirArg)
                : context.session().projectDir();

        context.session().setWorkingDir(neworkDir);
        return context.session().workingDir().toString();
    }

}
