package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/**
 * Runs {@code mvn compile} in a Maven project directory.
 */
public class SetWorkingDirTool implements NaruTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    public SetWorkingDirTool() {
    }

    @Override
    public String getName() {
        return "set_working_directory";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "update working dir and return the absolute path. when no argument, switches to project directory";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return NaruRegistry.buildDefinition(
                getName(), getDescription(session),
                NaruToolParameter.string("work_dir", "Path to the change to, can be relative (to session workdir) or absolute. when empty, return to project dir", false)
        );
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
