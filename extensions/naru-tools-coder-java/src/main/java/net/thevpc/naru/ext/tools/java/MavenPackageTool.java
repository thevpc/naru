package net.thevpc.naru.ext.tools.java;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

/**
 * Runs {@code mvn package} in a Maven project directory.
 */
public class MavenPackageTool extends DefaultNaruTool {

    public MavenPackageTool() {
        // exec, like maven_compile: packaging runs the build lifecycle.
        super("maven_package", new String[]{NaruToolTags.DEV, NaruToolTags.JAVA, NaruToolTags.EXECUTE});
    }

    @Override
    public String name() {
        return "maven_package";
    }

    @Override
    public String getDescription(NaruTask task) {
        return "Package a Maven project using 'mvn package -DskipTests'. Returns build output and exit code.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("project_dir", "Path to the Maven project (defaults to agent project dir)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String projectDirArg = context.stringArg("project_dir").orNull();
        NPath projectDir = NBlankable.isNonBlank(projectDirArg)
                ? context.task().resolve(projectDirArg)
                : context.task().projectDir();

        return MavenCompileTool.runMaven(projectDir, "package", "-DskipTests");
    }
}
