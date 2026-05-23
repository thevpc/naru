package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.util.NBlankable;

import java.util.Map;

/**
 * Runs {@code mvn test} (optionally limited to a single test class).
 */
public class MavenTestTool implements NaruTool {


    public MavenTestTool() {
    }

    @Override
    public String getName() {
        return "maven_test";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Run Maven tests using 'mvn test'. Optionally run a single test class.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                getName(), getDescription(session),
                NaruToolParameter.string("project_dir", "Path to the Maven project (defaults to agent project dir)", false),
                NaruToolParameter.string("test_class", "Specific test class to run, e.g. 'com.example.MyTest' (optional)", false)
        );
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
