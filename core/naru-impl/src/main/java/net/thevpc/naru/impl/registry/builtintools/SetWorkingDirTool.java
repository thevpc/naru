package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/**
 * Runs {@code mvn compile} in a Maven project directory.
 */
public class SetWorkingDirTool extends DefaultNaruTool {

    private static final int MAX_OUTPUT_CHARS = 8_000;

    public SetWorkingDirTool() {
        super("cd", new String[]{NaruToolTags.FILE_SYSTEM});
    }

    @Override
    public String name() {
        return "cd";
    }

    @Override
    public String getDescription(NaruTask task) {
        return "update working dir and return the absolute path. when no argument, switches to project directory";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("work_dir", "Path to the change to, can be relative (to session workdir) or absolute. when empty, return to project dir", false)
                        .build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String workingDirArg = context.stringArg("work_dir").orNull();
        NPath neworkDir = NBlankable.isNonBlank(workingDirArg)
                ? context.task().resolve(workingDirArg)
                : context.task().projectDir();

        context.task().setWorkingDir(neworkDir);
        return context.task().workingDir().toString();
    }

}
