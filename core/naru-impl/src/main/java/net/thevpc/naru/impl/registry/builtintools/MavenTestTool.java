package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.DefaultNaruTool;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

import java.util.Map;

/**
 * Runs {@code mvn test} (optionally limited to a single test class).
 */
public class MavenTestTool extends DefaultNaruTool {


    public MavenTestTool() {
        super("maven_test", new String[]{NaruToolTags.DEV});
    }


    @Override
    public String getDescription(NaruTask task) {
        return "Run Maven tests using 'mvn test'. Optionally run a single test class.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(task),
                NaruToolParameter.string("project_dir", "Path to the Maven project (defaults to agent project dir)", false).build(),
                NaruToolParameter.string("test_class", "Specific test class to run, e.g. 'com.example.MyTest' (optional)", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String projectDirArg = context.stringArg("project_dir").orNull();
        String testClass = context.stringArg("test_class").orNull();

        NPath projectDir = NBlankable.isNonBlank(projectDirArg)
                ? context.task().resolve(projectDirArg)
                : context.task().projectDir();

        if (!NBlankable.isBlank(testClass)) {
            return MavenCompileTool.runMaven(projectDir, "test", "-Dtest=" + testClass, "-DfailIfNoTests=false");
        }
        return MavenCompileTool.runMaven(projectDir, "test");
    }

    private static String getString(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : v.toString();
    }
}
