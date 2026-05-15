package net.thevpc.naru.impl.registry.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruToolRegistry;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

import java.util.Map;

/**
 * Runs {@code mvn test} (optionally limited to a single test class).
 */
public class MavenTestTool implements NaruTool {

    private final NaruToolDefinition definition;

    public MavenTestTool() {
        this.definition = NaruToolRegistry.buildDefinition(
                getName(), getDescription(),
                NaruToolParameter.string("project_dir", "Path to the Maven project (defaults to agent project dir)", false),
                NaruToolParameter.string("test_class", "Specific test class to run, e.g. 'com.example.MyTest' (optional)", false)
        );
    }

    @Override
    public String getName() {
        return "maven_test";
    }

    @Override
    public String getDescription() {
        return "Run Maven tests using 'mvn test'. Optionally run a single test class.";
    }

    @Override
    public NaruToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String projectDirArg = context.stringArg("project_dir").orNull();
        String testClass = context.stringArg("test_class").orNull();

        NPath projectDir = NBlankable.isNonBlank(projectDirArg)
                ? context.session().resolve(projectDirArg)
                : context.session().projectDir();

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
