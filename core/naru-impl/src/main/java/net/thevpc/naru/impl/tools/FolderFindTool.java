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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FolderFindTool implements NaruTool {

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
    public String getName() { return "folder_search"; }

    @Override
    public String getDescription(NaruSession session) {
        return "Find files by name/glob patterns and optionally search their contents. " +
                "CRITICAL: A strict logical 'AND' is performed across ALL filters. " +
                "Files must simultaneously match: Location Path AND Glob Filters (include/exclude) " +
                "AND Time Window Constraints (modified_after/before) AND Content Text Pattern (if provided). " +
                "Only files satisfying EVERY layer of this constraint chain are processed.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return NaruRegistry.buildDefinition(
                getName(), getDescription(session),
                NaruToolParameter.string("path", "Directory to search", true),
                NaruToolParameter.string("pattern", "Search text pattern inside file contents (optional)", false),
                NaruToolParameter.bool("regex", "Treat pattern as regex", false, false),
                NaruToolParameter.bool("case_sensitive", "Case-sensitive match", false, false),
                NaruToolParameter.integer("context_lines", "Lines of context before/after match", false, DEFAULT_CONTEXT),
                NaruToolParameter.integer("max_matches", "Max matches per file", false, DEFAULT_MAX_MATCHES),
                NaruToolParameter.integer("max_files", "Max files to scan", false, DEFAULT_MAX_FILES),
                NaruToolParameter.string("include", "Glob patterns to include (comma-separated, e.g. '*.java,*.xml')", false),
                NaruToolParameter.string("exclude", "Glob patterns to exclude (comma-separated)", false),
                NaruToolParameter.bool("recursive", "Search subdirectories", false, true),
                NaruToolParameter.string("modified_after", "ISO-8601 timestamp (e.g. 2026-05-01T00:00:00Z) or relative duration (e.g. -24h, -7d)", false),
                NaruToolParameter.string("modified_before", "ISO-8601 timestamp or relative duration", false)
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

        // Parse the modification date boundaries if present
        Instant modifiedAfter = null;
        Instant modifiedBefore = null;
        try {
            modifiedAfter = parseDate(context.stringArg("modified_after").orNull());
            modifiedBefore = parseDate(context.stringArg("modified_before").orNull());
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }

        NPath root = context.session().resolve(pathStr);
        if (!root.exists()) return "ERROR: Path not found: " + root;
        if (!root.isDirectory()) return "ERROR: Path is not a directory: " + root;
        if (!root.permissions().contains(NPathPermission.CAN_READ)) return "ERROR: Directory is not readable: " + root;

        Pattern compiledPattern = null;
        if (pattern != null && regex) {
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
            // Collect matching files applying names, globs, and historical time gates
            List<NPath> files = collectFiles(root, recursive, includeMatcher, excludeMatcher, modifiedAfter, modifiedBefore, maxFiles);
            if (files.isEmpty()) return "No files found matching specified filters in: " + root;

            // --- BRANCH A: Pure File Finder Operation ---
            if (pattern == null) {
                StringBuilder out = new StringBuilder();
                for (NPath file : files) {
                    out.append(root.relativize(file)).append("\n");
                }
                return String.format("Found %d file(s) in %s:\n%s", files.size(), root.name(), out.toString().trim());
            }

            // --- BRANCH B: Content Grepping Operation ---
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

                String fileResult = searchFile(file, pattern, compiledPattern, caseSensitive, regex, contextLines, maxMatches, root);
                if (fileResult != null) {
                    totalMatches += countMatchesInResult(fileResult);
                    out.append(fileResult).append("\n");
                }

                if (out.length() > MAX_OUTPUT_CHARS) {
                    truncated = true;
                    break;
                }
            }

            if (totalMatches == 0) return "No content matches found for pattern '" + pattern + "' inside filtered files.";

            String result = out.toString();
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS) + "\n... [output truncated]";
            }
            if (truncated) result += "\n[WARNING: Results limited. Refine search criteria.]";

            return String.format("Found %d match(es) across %d file(s) in %s:\n%s",
                    totalMatches, files.size(), root.name(), result.trim());

        } catch (Exception e) {
            return "ERROR searching directory: " + e.getMessage();
        }
    }

    private String searchFile(NPath file, String pattern, Pattern compiled, boolean caseSensitive,
                              boolean regex, int contextLines, int maxMatches, NPath root) {
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
                        sb.append("=== ").append(root.relativize(file)).append(" ===\n");
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
            // Gracefully ignore reading issues
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

    private List<NPath> collectFiles(NPath dir, boolean recursive, Predicate<NPath> include, Predicate<NPath> exclude,
                                     Instant after, Instant before, int limit) {
        List<NPath> result = new ArrayList<>();
        Queue<NPath> queue = new ArrayDeque<>();
        queue.add(dir);

        while (!queue.isEmpty() && result.size() < limit) {
            NPath current = queue.poll();
            if (!current.exists() || !current.permissions().contains(NPathPermission.CAN_READ)) continue;

            if (current.isDirectory()) {
                if (current.isHidden() && current.name().startsWith(".")) continue;
                if (recursive || current.equals(dir)) {
                    for (NPath child : safeList(current)) {
                        if (child.isHidden() && child.name().startsWith(".")) continue;
                        if (exclude != null && exclude.test(child)) continue;

                        if (child.isDirectory()) {
                            queue.add(child);
                        } else {
                            if (include == null || include.test(child)) {
                                // Match the time window filter criteria
                                if (matchesTimeBounds(child, after, before)) {
                                    queue.add(child);
                                }
                            }
                        }
                    }
                }
            } else {
                if (matchesTimeBounds(current, after, before)) {
                    result.add(current);
                }
            }
        }
        return result;
    }

    private boolean matchesTimeBounds(NPath file, Instant after, Instant before) {
        try {
            Instant modTime = file.lastModifiedInstant();
            if (after != null && modTime.isBefore(after)) return false;
            if (before != null && modTime.isAfter(before)) return false;
            return true;
        } catch (Exception e) {
            // If we can't extract structural metadata, skip safely or exclude depending on strictness
            return false;
        }
    }

    private Instant parseDate(String input) {
        if (NBlankable.isBlank(input)) return null;
        String val = input.trim();
        try {
            // Handle human relative offsets like "-24h" or "-7d"
            if (val.startsWith("-")) {
                long magnitude = Long.parseLong(val.substring(1, val.length() - 1));
                char unit = Character.toLowerCase(val.charAt(val.length() - 1));
                switch (unit) {
                    case 'h': return Instant.now().minusSeconds(magnitude * 3600);
                    case 'd': return Instant.now().minusSeconds(magnitude * 86400);
                    case 'm': return Instant.now().minusSeconds(magnitude * 60);
                    default: throw new IllegalArgumentException("Unknown relative duration metric unit: " + unit);
                }
            }
            return Instant.parse(val);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid timestamp value '" + input + "'. Use ISO-8601 strings or offsets like '-48h'.");
        }
    }

    private Predicate<NPath> createMatcher(String glob) {
        if (NBlankable.isBlank(glob)) return null;
        String[] parts = glob.split(",");
        return path -> Arrays.stream(parts).anyMatch(p -> path.name().matches(globToRegex(p.trim())));
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
        String ext = file.nameParts().extension().toLowerCase();
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
