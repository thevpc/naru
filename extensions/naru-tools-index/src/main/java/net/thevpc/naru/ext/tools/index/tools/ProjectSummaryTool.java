package net.thevpc.naru.ext.tools.index.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import net.thevpc.nuts.text.NText;

public class ProjectSummaryTool extends DefaultNaruTool {
    public ProjectSummaryTool() {
        super("project_summary", new String[]{NaruToolTags.DEV, NaruToolTags.INDEX});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Scans project root for marker files and module structure.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task));
    }

    @Override
    public String execute(NaruToolCallContext context) {
        Path root = Paths.get(context.task().projectDir().toString());
        StringBuilder sb = new StringBuilder();
        sb.append("Project Summary for ").append(root.getFileName()).append(":\n");
        
        if (Files.exists(root.resolve("pom.xml"))) sb.append("Build System: Maven\n");
        else if (Files.exists(root.resolve("build.gradle"))) sb.append("Build System: Gradle\n");
        else if (Files.exists(root.resolve("package.json"))) sb.append("Build System: npm\n");
        else if (Files.exists(root.resolve("Cargo.toml"))) sb.append("Build System: Cargo\n");
        else if (Files.exists(root.resolve("go.mod"))) sb.append("Build System: Go\n");
        else sb.append("Build System: Unknown\n");

        sb.append("Languages found:\n");
        Map<String, Integer> extCounts = new HashMap<>();
        try {
            Files.walk(root).filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                int i = name.lastIndexOf('.');
                if (i > 0) {
                    String ext = name.substring(i);
                    extCounts.put(ext, extCounts.getOrDefault(ext, 0) + 1);
                }
            });
        } catch (IOException e) {}

        extCounts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(10)
                .forEach(e -> sb.append("- ").append(e.getKey()).append(": ").append(e.getValue()).append(" files\n"));

        return sb.toString();
    }
}
