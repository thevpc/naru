package net.thevpc.naru.ext.tools.index.tools;

import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.DefaultNaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.naru.api.registry.NaruToolTags;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class ProjectMapTool extends DefaultNaruTool {
    public ProjectMapTool() {
        super("project_map", new String[]{NaruToolTags.DEV, NaruToolTags.INDEX});
    }

    @Override
    public NText getDescription(NaruTask task) {
        return NText.ofPlain("Walks the project directory tree and returns a compact textual tree view.");
    }

    @Override
    public NaruToolDefinition getDefinition(NaruTask task) {
        return new NaruToolDefinitionFunction(name(), getDescription(task),
                NaruToolParameter.string("path", "Project path to scan", false).build(),
                NaruToolParameter.integer("depth", "Maximum depth", false).build(),
                NaruToolParameter.bool("include_hidden", "Include hidden files", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String pathArg = context.stringArg("path").orNull();
        Integer depth = context.intArg("depth").orNull();
        if (depth == null) {
            depth = 3;
        }
        boolean includeHidden = context.booleanArg("include_hidden").orElse(false);

        NaruTask task = context.task();
        NPath projectDir = NBlankable.isNonBlank(pathArg)
            ? context.task().resolve(pathArg)
            : context.task().projectDir();
        Path rootPath = Paths.get(projectDir.toString());

        StringBuilder sb = new StringBuilder();
        sb.append(rootPath.getFileName()).append("/\n");
        buildTree(rootPath, rootPath, "", 0, depth, includeHidden, sb);

        return sb.toString();
    }

    private void buildTree(Path rootPath, Path currentPath, String prefix, int currentDepth, int maxDepth, boolean includeHidden, StringBuilder sb) {
        if (currentDepth >= maxDepth) {
            sb.append(prefix).append("└── ... (truncated)\n");
            return;
        }

        try {
            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(currentPath)) {
                for (Path p : stream) {
                    children.add(p);
                }
            }
            
            children.sort(Comparator.comparing(p -> p.getFileName().toString()));

            List<Path> filtered = new ArrayList<>();
            for (Path p : children) {
                if (!includeHidden && p.getFileName().toString().startsWith(".")) {
                    continue;
                }
                filtered.add(p);
            }

            for (int i = 0; i < filtered.size(); i++) {
                Path p = filtered.get(i);
                boolean isLast = (i == filtered.size() - 1);
                sb.append(prefix).append(isLast ? "└── " : "├── ").append(p.getFileName());

                if (Files.isDirectory(p)) {
                    sb.append("/\n");
                    buildTree(rootPath, p, prefix + (isLast ? "    " : "│   "), currentDepth + 1, maxDepth, includeHidden, sb);
                } else {
                    long size = Files.size(p);
                    sb.append(" (").append(formatSize(size)).append(")\n");
                }
            }
        } catch (IOException e) {
            sb.append(prefix).append("└── [Error reading directory]\n");
        }
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        return (size / 1024) + " KB";
    }
}
