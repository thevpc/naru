package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathPermission;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIterator;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FolderGrepTool implements NaruTool {

    private static final int MAX_TOTAL_MATCHES = 100;
    private static final int MAX_OUTPUT_CHARS = 10_000;
    private static final int DEFAULT_CONTEXT = 2;
    private static final int DEFAULT_MAX_FILES = 50;
    private static final int DEFAULT_MAX_MATCHES = 50;
    private static final Set<String> BINARY_EXTENSIONS = new HashSet<>(
            Arrays.asList(".class", ".jar", ".zip", ".tar", ".gz", ".png", ".jpg", ".jpeg",
                    ".gif", ".pdf", ".exe", ".dll", ".so", ".dylib", ".bin", ".dat")
    );

    @Override
    public String getName() { return "folder_grep"; }

    @Override
    public String getDescription(NaruSession session) {
        return "Recursively search files in a directory for a pattern. Streams files lazily to avoid OOM.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return NaruRegistry.buildDefinition(
                getName(), getDescription(session),
                NaruToolParameter.string("path", "Directory to search", true),
                NaruToolParameter.string("pattern", "Search pattern", true),
                NaruToolParameter.bool("regex", "Treat pattern as regex", false, false),
                NaruToolParameter.bool("case_sensitive", "Case-sensitive match", false, false),
                NaruToolParameter.integer("context_lines", "Lines of context before/after match", false, DEFAULT_CONTEXT),
                NaruToolParameter.integer("max_matches", "Max matches per file", false, DEFAULT_MAX_MATCHES),
                NaruToolParameter.integer("max_files", "Max files to scan", false, DEFAULT_MAX_FILES),
                NaruToolParameter.string("include", "Glob patterns to include (comma-separated, e.g. '*.java,*.xml')", false),
                NaruToolParameter.string("exclude", "Glob patterns to exclude (comma-separated)", false),
                NaruToolParameter.bool("recursive", "Search subdirectories", false, true)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String pathStr = context.stringArg("path").onBlankEmpty().orNull();
        String pattern = context.stringArg("pattern").onBlankEmpty().orNull();
        boolean regex = context.booleanArg("regex").orFalse();
        boolean caseSensitive = context.booleanArg("case_sensitive").orFalse();
        int contextLines = (int) Math.max(0, context.intArg("context_lines").orElse(DEFAULT_CONTEXT));
        int maxMatches = (int) Math.max(1, context.intArg("max_matches").orElse(DEFAULT_MAX_MATCHES));
        int maxFiles = (int) Math.max(1, context.intArg("max_files").orElse(DEFAULT_MAX_FILES));
        boolean recursive = context.booleanArg("recursive").orTrue();
        String includeGlob = context.stringArg("include").orNull();
        String excludeGlob = context.stringArg("exclude").orNull();

        if (pathStr == null) return "ERROR: 'path' is required.";
        if (pattern == null) return "ERROR: 'pattern' is required.";

        NPath root = context.session().resolve(pathStr);
        if (!root.exists()) return "ERROR: Path not found: " + root;
        if (!root.isDirectory()) return "ERROR: Path is not a directory: " + root;
        if (!root.permissions().contains(NPathPermission.CAN_READ)) return "ERROR: Directory is not readable: " + root;

        Pattern compiledPattern = null;
        if (regex) {
            try {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                compiledPattern = Pattern.compile(pattern, flags);
            } catch (PatternSyntaxException e) {
                return "ERROR: Invalid regex pattern: " + e.getMessage();
            }
        }

        Predicate<NPath> includeMatcher = createMatcher(includeGlob);
        Predicate<NPath> excludeMatcher = createMatcher(excludeGlob);

        try {
            List<NPath> files = collectFiles(root, recursive, includeMatcher, excludeMatcher, maxFiles);
            if (files.isEmpty()) return "No readable files found in: " + root;

            StringBuilder out = new StringBuilder();
            int totalMatches = 0;
            boolean truncated = false;

            for (NPath file : files) {
                if (!file.exists() || !file.isRegularFile()) continue;
                if (isBinary(file)) continue;
                if (totalMatches >= MAX_TOTAL_MATCHES) {
                    truncated = true;
                    break;
                }

                String fileResult = searchFile(file, pattern, compiledPattern, caseSensitive, regex,
                        contextLines, maxMatches, totalMatches,root);
                if (fileResult != null) {
                    totalMatches += countMatchesInResult(fileResult);
                    out.append(fileResult).append("\n");
                }

                if (out.length() > MAX_OUTPUT_CHARS) {
                    truncated = true;
                    break;
                }
            }

            if (totalMatches == 0) return "No matches found in directory: " + root;

            String result = out.toString();
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS) + "\n... [output truncated]";
            }
            if (truncated) result += "\n[WARNING: Results limited. Refine pattern or add include filters.]";

            return String.format("Found %d match(es) across %d file(s) in %s:\n%s",
                    totalMatches, files.size(), root.name(), result.trim());

        } catch (Exception e) {
            return "ERROR searching directory: " + e.getMessage();
        }
    }

    /** Reuse streaming deque logic per file */
    private String searchFile(NPath file, String pattern, Pattern compiled, boolean caseSensitive,
                              boolean regex, int contextLines, int maxMatches, int globalOffset,NPath root) {
        StringBuilder sb = new StringBuilder();
        int fileMatches = 0;

        try {
            NIterator<String> it = file.lines().iterator();
            Deque<String> beforeBuffer = new ArrayDeque<>(contextLines + 1);
            int afterContextRemaining = 0;
            int lineNum = 1;

            while (it.hasNext() && fileMatches < maxMatches) {
                String line = it.next();
                boolean isMatch = matches(line, pattern, compiled, caseSensitive, regex);

                if (isMatch) {
                    if (fileMatches == 0) {
                        sb.append("=== ").append(file.relativize(root)).append(" ===\n");
                    }
                    int bufferStart = lineNum - beforeBuffer.size();
                    for (String ctx : beforeBuffer) {
                        sb.append(formatLine(bufferStart++, ctx, false)).append("\n");
                    }
                    sb.append(formatLine(lineNum, line, true)).append("\n");
                    fileMatches++;
                    afterContextRemaining = contextLines;
                    beforeBuffer.clear();
                } else if (afterContextRemaining > 0) {
                    sb.append(formatLine(lineNum, line, false)).append("\n");
                    afterContextRemaining--;
                } else {
                    beforeBuffer.add(line);
                    if (beforeBuffer.size() > contextLines) beforeBuffer.pollFirst();
                }
                lineNum++;
            }
        } catch (Exception ignored) {
            // Skip unreadable files gracefully
        }

        return fileMatches > 0 ? sb.toString() : null;
    }

    private boolean matches(String line, String pattern, Pattern compiled, boolean caseSensitive, boolean regex) {
        if (regex) return compiled.matcher(line).find();
        if (caseSensitive) return line.contains(pattern);
        return line.toLowerCase().contains(pattern.toLowerCase());
    }

    private String formatLine(int lineNum, String content, boolean isMatch) {
        return String.format("L%04d:%s%s", lineNum, content, isMatch ? " [MATCH]" : "");
    }

    private List<NPath> collectFiles(NPath dir, boolean recursive, Predicate<NPath> include, Predicate<NPath> exclude, int limit) {
        List<NPath> result = new ArrayList<>();
        Queue<NPath> queue = new ArrayDeque<>();
        queue.add(dir);

        while (!queue.isEmpty() && result.size() < limit) {
            NPath current = queue.poll();
            if (!current.exists() || !current.permissions().contains(NPathPermission.CAN_READ)) continue;

            if (current.isDirectory()) {
                if (current.isHidden() && current.name().startsWith(".")) continue; // skip dot dirs
                if (recursive) {
                    for (NPath child : safeList(current)) {
                        if (child.isHidden() && child.name().startsWith(".")) continue;
                        if (exclude != null && exclude.test(child)) continue;
                        if (include == null || include.test(child)) {
                            queue.add(child);
                        }
                    }
                }
            } else {
                if (exclude != null && exclude.test(current)) continue;
                if (include != null && !include.test(current)) continue;
                result.add(current);
            }
        }
        return result;
    }

    private Predicate<NPath> createMatcher(String glob) {
        if (NBlankable.isBlank(glob)) return null;
        String[] parts = glob.split(",");
        // Simple composite matcher
        return path -> Arrays.stream(parts).anyMatch(p -> path.toString().matches(globToRegex(p.trim())));
    }

    private String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append(".");
            else if ("+(){}[].^$|".indexOf(c) >= 0) sb.append("\\").append(c);
            else sb.append(c);
        }
        return sb.toString();
    }

    private boolean isBinary(NPath file) {
        String ext = file.nameParts().getExtension().toLowerCase();
        return BINARY_EXTENSIONS.contains(ext);
    }

    private List<NPath> safeList(NPath dir) {
        try { return dir.list(); }
        catch (Exception e) { return Collections.emptyList(); }
    }

    private int countMatchesInResult(String result) {
        int count = 0, idx = 0;
        String marker = "[MATCH]";
        while ((idx = result.indexOf(marker, idx)) != -1) {
            count++;
            idx += marker.length();
        }
        return count;
    }
}