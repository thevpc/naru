package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.agent.NaruTaskConfig;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathOption;
import net.thevpc.nuts.io.NPathPermission;
import net.thevpc.nuts.pipeline.NIterator;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NBooleanRef;
import net.thevpc.nuts.util.NIntRef;
import net.thevpc.nuts.util.NOptional;
import net.thevpc.nuts.util.NUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

public class FileToolHelper {
    public static final int MAX_PREVIEW_CHARS = 8_000;
    public static final int DEFAULT_CONTEXT = 2;
    public static final int DEFAULT_MAX_MATCHES = 50;
    public static final int DEFAULT_MAX_LINES = 2000;
    public static final int MAX_OUTPUT_CHARS = 15_000;

    public static final int MAX_TOTAL_MATCHES = 100;
    public static final int DEFAULT_MAX_FILES = 50;
    public static final Set<String> BINARY_EXTENSIONS = new HashSet<>(
            Arrays.asList(".class", ".jar", ".zip", ".tar", ".gz", ".png", ".jpg", ".jpeg",
                    ".gif", ".pdf", ".exe", ".dll", ".so", ".dylib", ".bin", ".dat")
    );

    // ── settings ──────────────────────────────────────────────────────────────
    // Every cap above lives behind a validated env read (NaruTaskConfig's chain:
    // task → session → agent → settings), so a project can raise or lower a limit
    // per task, session, agent or config file without touching code. A value that is
    // not a strictly positive integer never reaches the truncating code below — it
    // falls back to the built-in default instead, so a typo cannot turn a preview
    // into a balloon or a listing into a freeze.

    private static int previewChars(NaruTask task) {
        return NaruTaskConfig.getPositiveInt(task, "naru.fs.read.previewChars", MAX_PREVIEW_CHARS);
    }

    private static int readMaxLines(NaruTask task) {
        return NaruTaskConfig.getPositiveInt(task, "naru.fs.read.maxLines", DEFAULT_MAX_LINES);
    }

    private static int findContextLines(NaruTask task) {
        return NaruTaskConfig.getPositiveInt(task, "naru.fs.find.contextLines", DEFAULT_CONTEXT);
    }

    private static int findMaxMatches(NaruTask task) {
        return NaruTaskConfig.getPositiveInt(task, "naru.fs.find.maxMatches", DEFAULT_MAX_MATCHES);
    }

    private static int findMaxFiles(NaruTask task) {
        return NaruTaskConfig.getPositiveInt(task, "naru.fs.find.maxFiles", DEFAULT_MAX_FILES);
    }

    private static int grepMaxTotalMatches(NaruTask task) {
        return NaruTaskConfig.getPositiveInt(task, "naru.fs.grep.maxTotalMatches", MAX_TOTAL_MATCHES);
    }

    private static int grepMaxOutputChars(NaruTask task) {
        return NaruTaskConfig.getPositiveInt(task, "naru.fs.grep.maxOutputChars", MAX_OUTPUT_CHARS);
    }

    public static String filesDiff(NaruTask task, String path1, String path2, Number number) {
        if (NBlankable.isBlank(path1)) return "Error: file1 is required.";
        if (NBlankable.isBlank(path2)) return "Error: file2 is required.";
        int contextLines = number == null ? 3 : number.intValue();
        if (contextLines <= 0) {
            contextLines = 3;
        }
        try {
            List<String> lines1 = task.resolve(path1).lines().toList();
            List<String> lines2 = task.resolve(path2).lines().toList();
            return computeUnifiedDiff(path1, path2, lines1, lines2, contextLines);
        } catch (Exception e) {
            return "Error reading files: " + e.getMessage();
        }
    }

    private static String computeUnifiedDiff(String name1, String name2,
                                             List<String> lines1, List<String> lines2,
                                             int context) {
        // Simple LCS-based unified diff
        int[][] lcs = lcsTable(lines1, lines2);
        List<String> hunks = buildHunks(lines1, lines2, lcs, context);

        if (hunks.isEmpty()) return "Files are identical.";

        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(name1).append("\n");
        sb.append("+++ ").append(name2).append("\n");
        for (String h : hunks) sb.append(h);
        return sb.toString();
    }

    private static int[][] lcsTable(List<String> a, List<String> b) {
        int m = a.size(), n = b.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++)
            for (int j = 1; j <= n; j++)
                dp[i][j] = a.get(i - 1).equals(b.get(j - 1))
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
        return dp;
    }

    private static List<String> buildHunks(List<String> a, List<String> b,
                                           int[][] lcs, int ctx) {
        // Walk edit script
        List<int[]> edits = new ArrayList<>(); // [type, lineA, lineB] 0=ctx,1=del,2=add
        walkEdits(a, b, lcs, a.size(), b.size(), edits);

        // Group into hunks with context
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < edits.size()) {
            if (edits.get(i)[0] == 0) {
                i++;
                continue;
            } // skip pure context
            // find hunk boundaries
            int start = Math.max(0, i - ctx);
            int end = Math.min(edits.size(), i + ctx + 1);
            // extend to cover all changes in range
            while (end < edits.size() && edits.get(end)[0] != 0) end++;
            end = Math.min(edits.size(), end + ctx);

            StringBuilder hunk = new StringBuilder();
            hunk.append("@@ hunk @@\n");
            for (int k = start; k < end; k++) {
                int[] e = edits.get(k);
                if (e[0] == 0) hunk.append(" ").append(a.get(e[1])).append("\n");
                if (e[0] == 1) hunk.append("-").append(a.get(e[1])).append("\n");
                if (e[0] == 2) hunk.append("+").append(b.get(e[2])).append("\n");
            }
            result.add(hunk.toString());
            i = end;
        }
        return result;
    }

    private static void walkEdits(List<String> a, List<String> b, int[][] lcs,
                                  int i, int j, List<int[]> out) {
        if (i == 0 && j == 0) return;
        if (i > 0 && j > 0 && a.get(i - 1).equals(b.get(j - 1))) {
            walkEdits(a, b, lcs, i - 1, j - 1, out);
            out.add(new int[]{0, i - 1, j - 1}); // context
        } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
            walkEdits(a, b, lcs, i, j - 1, out);
            out.add(new int[]{2, i - 1, j - 1}); // add
        } else {
            walkEdits(a, b, lcs, i - 1, j, out);
            out.add(new int[]{1, i - 1, j - 1}); // delete
        }
    }

    public static String fileAppend(NaruTask task, String path, String content,Boolean dry) {
        if (NBlankable.isBlank(path)) {
            return "ERROR: 'path' argument is required.";
        }
        if (content == null || content.isEmpty()) {
            return "WARNING: 'content' was empty. ignored";
        }
        try {
            NPath p = task.resolve(path);
            if (Boolean.TRUE.equals(dry)) {
                String preview = content.length() > previewChars(task)
                        ? content.substring(0, previewChars(task)) + "\n... [preview truncated]"
                        : content;
                return String.format("DRY RUN SUCCESS: Would %s content. Preview:\n```\n%s\n```",
                        "append", preview, p.name(), preview);
            }

            p.mkParentDirs().writeString(content, NPathOption.APPEND);
            return "content appended successfully.";
        } catch (Exception e) {
            return "ERROR reading file: " + e.getMessage();
        }
    }

    public static String fileEdit(NaruTask task, String path, Long from, Long to, String content, Boolean dry) {
        if (NBlankable.isBlank(path)) return "ERROR: 'path' is required.";

        NPath p = task.resolve(path);
        if (!p.exists()) return "ERROR: File not found: " + p;
        if (!p.permissions().contains(NPathPermission.CAN_WRITE)) return "ERROR: File is not writable: " + p;

        if (from == null) return "ERROR: 'from' is required.";

        try {
            List<String> lines = p.lines().toList();
            int total = lines.size();

            int start = resolveIndex(from, total);
            int end = (to == null) ? start : resolveIndex(to, total);

            // Auto-swap if reversed (LLM-friendly)
            if (end < start) {
                int t = start;
                start = end;
                end = t;
            }

            // Clamp to valid bounds [0, total]
            start = Math.max(0, Math.min(start, total));
            end = Math.max(0, Math.min(end, total));

            List<String> newLines = (NBlankable.isBlank(content))
                    ? Collections.emptyList()
                    : Arrays.asList(content.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1));

            // Build result
            List<String> result = new ArrayList<>(total + newLines.size() - (end - start));
            result.addAll(lines.subList(0, start));
            result.addAll(newLines);
            if (end < total) result.addAll(lines.subList(end, total));

            // Reconstruct content (preserve trailing newline if original had one)
            String finalContent = String.join("\n", result);
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                finalContent += "\n";
            }

            String action = newLines.isEmpty() ? "delete" : (start == end ? "insert" : "replace");

            if (Boolean.TRUE.equals(dry)) {
                String preview = finalContent.length() > previewChars(task)
                        ? finalContent.substring(0, previewChars(task)) + "\n... [preview truncated]"
                        : finalContent;
                return String.format("DRY RUN SUCCESS: Would %s lines %d-%d in %s. Preview:\n```\n%s\n```",
                        action, start, end, p.name(), preview);
            }

            // Atomic write
            p.writeString(finalContent, StandardCharsets.UTF_8);
            return String.format("SUCCESS: Applied %s at lines %d-%d in %s.", action, start, end, p.name());

        } catch (Exception e) {
            return "ERROR editing file: " + e.getMessage();
        }
    }

    public static String fileEditSearchReplace(NaruTask task, String path, String search, String replace, Long occurrence, Boolean replaceAll, Boolean dry) {
        if (NBlankable.isBlank(path)) return "ERROR: 'path' is required.";
        if (search == null || search.isEmpty()) return "ERROR: 'search' string cannot be empty.";
        if (replace == null) replace = "";

        if (search.equals(replace)) {
            return "No changes to apply: oldString and newString are identical.";
        }

        NPath p = task.resolve(path);
        if (!p.exists()) return "File not found: " + p;
        if (!p.permissions().contains(NPathPermission.CAN_WRITE)) return "ERROR: File is not writable: " + p;

        try {
            String fullText = p.readString();
            int idx = 0;
            List<Integer> matchIndices = new ArrayList<>();
            while ((idx = fullText.indexOf(search, idx)) != -1) {
                matchIndices.add(idx);
                idx += search.length();
            }

            if (matchIndices.isEmpty()) {
                return "Could not find oldString in the file. It must match exactly, including whitespace, indentation, and line endings.";
            }

            if (Boolean.TRUE.equals(replaceAll)) {
                String newText = fullText.replace(search, replace);
                if (Boolean.TRUE.equals(dry)) {
                    String preview = newText.length() > previewChars(task)
                            ? newText.substring(0, previewChars(task)) + "\n... [preview truncated]"
                            : newText;
                    return String.format("DRY RUN SUCCESS: Replaced %d occurrences of search string in %s. Preview:\n```\n%s\n```",
                            matchIndices.size(), p.name(), preview);
                }
                p.writeString(newText, StandardCharsets.UTF_8);
                return "Edit applied successfully.";
            }

            if (occurrence == null) {
                if (matchIndices.size() > 1) {
                    return "Found multiple matches for oldString. Provide more surrounding context to make the match unique.";
                }
                // Exactly 1 match
                int matchIdx = matchIndices.get(0);
                String newText = fullText.substring(0, matchIdx) + replace + fullText.substring(matchIdx + search.length());
                if (Boolean.TRUE.equals(dry)) {
                    String preview = newText.length() > previewChars(task)
                            ? newText.substring(0, previewChars(task)) + "\n... [preview truncated]"
                            : newText;
                    return String.format("DRY RUN SUCCESS: Replaced occurrence 1 of search string in %s. Preview:\n```\n%s\n```",
                            p.name(), preview);
                }
                p.writeString(newText, StandardCharsets.UTF_8);
                return "Edit applied successfully.";
            }

            int targetOcc = occurrence.intValue();
            if (targetOcc <= 0 || targetOcc > matchIndices.size()) {
                return String.format("ERROR: Requested occurrence %d, but only found %d occurrence(s).", targetOcc, matchIndices.size());
            }

            int matchIdx = matchIndices.get(targetOcc - 1);
            String newText = fullText.substring(0, matchIdx) + replace + fullText.substring(matchIdx + search.length());

            if (Boolean.TRUE.equals(dry)) {
                String preview = newText.length() > previewChars(task)
                        ? newText.substring(0, previewChars(task)) + "\n... [preview truncated]"
                        : newText;
                return String.format("DRY RUN SUCCESS: Replaced occurrence %d of search string in %s. Preview:\n```\n%s\n```",
                        targetOcc, p.name(), preview);
            }

            p.writeString(newText, StandardCharsets.UTF_8);
            return "Edit applied successfully.";

        } catch (Exception e) {
            return "ERROR search-replace in file: " + e.getMessage();
        }
    }

    public static String fileEditSearchReplace(NaruTask task, String path, String search, String replace, Long occurrence, Boolean dry) {
        return fileEditSearchReplace(task, path, search, replace, occurrence, false, dry);
    }

    public static String fileCreate(NaruTask task, String path, String content, Boolean overwrite) {
        if (NBlankable.isBlank(path)) return "ERROR: 'path' is required.";
        if (content == null) content = "";

        NPath p = task.resolve(path);
        if (p.exists() && (overwrite == null || !overwrite)) {
            return "ERROR: File already exists at " + p + ". Set overwrite=true to overwrite.";
        }

        try {
            p.mkParentDirs().writeString(content, StandardCharsets.UTF_8);
            return "SUCCESS: Created file at " + p + " (" + content.length() + " bytes).";
        } catch (Exception e) {
            return "ERROR creating file: " + e.getMessage();
        }
    }

    private static int resolveIndex(Long idx, long total) {
        if (idx == null) return 0;
        if (idx < 0) return (int) Math.max(0, total + idx); // -1 → total-1, -2 → total-2
        return (int) Math.min(idx, total);                  // clamp large positives to total
    }

    public static String fileGrep(NaruTask task, String path, String pattern, String include, Boolean regex, Boolean caseSensitive, Integer contextLines0, Integer maxMatches0) {
        if (NBlankable.isBlank(path)) {
            path = task.workingDir().toString();
        }
        if (NBlankable.isBlank(pattern)) return "ERROR: 'pattern' is required.";

        NPath targetPath = task.resolve(path);
        if (targetPath == null || !targetPath.exists()) {
            return "File not found: " + path;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("-H");
        cmd.add("--no-heading");
        cmd.add("--line-number");
        cmd.add("--color=never");
        if (contextLines0 != null && contextLines0 > 0) {
            cmd.add("-C");
            cmd.add(String.valueOf(contextLines0));
        }
        if (NBlankable.isNonBlank(include)) {
            cmd.add("-g");
            cmd.add(include);
        }
        if (caseSensitive != null && !caseSensitive) {
            cmd.add("-i");
        }
        if (regex != null && !regex) {
            cmd.add("-F");
        }
        cmd.add("-e");
        cmd.add(pattern);
        cmd.add(targetPath.toString());

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = proc.waitFor();

            if (exitCode == 2) {
                return stderr.trim();
            }
            if (exitCode == 1 || stdout.trim().isEmpty()) {
                return "No files found";
            }

            Map<String, List<String[]>> fileMatches = new LinkedHashMap<>();
            int totalMatches = 0;
            String[] lines = stdout.split("\n");
            for (String line : lines) {
                if (line.isEmpty()) continue;
                int firstColon = line.indexOf(':');
                if (firstColon > 0) {
                    int secondColon = line.indexOf(':', firstColon + 1);
                    if (secondColon > firstColon) {
                        String f = line.substring(0, firstColon);
                        String lnum = line.substring(firstColon + 1, secondColon);
                        String lcontent = line.substring(secondColon + 1);
                        fileMatches.computeIfAbsent(f, k -> new ArrayList<>()).add(new String[]{lnum, lcontent});
                        totalMatches++;
                    }
                }
            }

            if (totalMatches == 0) {
                return "No files found";
            }

            List<String> blocks = new ArrayList<>();
            for (Map.Entry<String, List<String[]>> entry : fileMatches.entrySet()) {
                StringBuilder b = new StringBuilder();
                b.append(entry.getKey()).append(":\n");
                List<String[]> matches = entry.getValue();
                for (int i = 0; i < matches.size(); i++) {
                    if (i > 0) b.append("\n");
                    b.append("  Line ").append(matches.get(i)[0]).append(": ").append(matches.get(i)[1]);
                }
                blocks.add(b.toString());
            }

            return "Found " + totalMatches + " matches\n" + String.join("\n\n\n", blocks) + "\n";

        } catch (Exception e) {
            return "ERROR executing grep: " + e.getMessage();
        }
    }

    public static String fileGrep(NaruTask task, String path, String pattern, Boolean regex, Boolean caseSensitive, Integer contextLines0, Integer maxMatches0) {
        return fileGrep(task, path, pattern, null, regex, caseSensitive, contextLines0, maxMatches0);
    }

    private static boolean _grep_matches(String line, String pattern, Pattern compiled, boolean caseSensitive, boolean regex) {
        if (regex) return compiled.matcher(line).find();
        if (caseSensitive) return line.contains(pattern);
        return line.toLowerCase().contains(pattern.toLowerCase());
    }

    private static String _grep_formatLine(int lineNum, String content, boolean isMatch) {
        return String.format("L%04d:%s%s", lineNum, content, isMatch ? " [MATCH]" : "");
    }

    public static String fileRead(NaruTask task, String pathStr, Long lineStart, Long lineEnd) {
        if (NBlankable.isBlank(pathStr)) return "ERROR: 'path' is required.";

        NPath file = task.resolve(pathStr);
        if (!file.exists()) return "File not found: " + file;

        if (file.isDirectory()) {
            StringBuilder sb = new StringBuilder();
            sb.append("<path>").append(file).append("</path>\n");
            sb.append("<type>directory</type>\n");
            sb.append("<entries>\n");
            List<NPath> children = safeList(file);
            List<String> names = new ArrayList<>();
            for (NPath child : children) {
                if (child.isDirectory()) {
                    names.add(child.name() + "/");
                } else {
                    names.add(child.name());
                }
            }
            Collections.sort(names);
            for (String name : names) {
                sb.append(name).append("\n");
            }
            sb.append("\n(").append(names.size()).append(" entries)\n");
            sb.append("</entries>");
            return sb.toString();
        }

        if (!file.isRegularFile()) return "ERROR: Path is not a regular file: " + file;
        if (!file.permissions().contains(NPathPermission.CAN_READ)) return "ERROR: File is not readable: " + file;
        if (isBinary(file)) return "Cannot read binary file: " + file;

        boolean lineNumbers = NaruTaskConfig.getBoolean(task, "naru.fs.read.lineNumbers", true);

        try {
            List<String> allLines = file.lines().toList();
            int totalLines = allLines.size();
            int maxLines = readMaxLines(task);

            long start1 = (lineStart == null || lineStart <= 0) ? 1L : lineStart;
            long end1 = (lineEnd != null) ? lineEnd : (lineStart != null ? (start1 + maxLines - 1) : Math.min(totalLines, maxLines));
            if (end1 > totalLines) {
                end1 = totalLines;
            }

            if (end1 < start1 && totalLines > 0) {
                return "ERROR: 'line_end' (" + end1 + ") cannot be less than 'line_start' (" + start1 + ").";
            }

            StringBuilder out = new StringBuilder();
            if (lineNumbers) {
                out.append("<path>").append(file).append("</path>\n");
                out.append("<type>file</type>\n");
                out.append("<content>\n");
            }

            int fromIdx = (int) Math.max(0, start1 - 1);
            int toIdx = (int) Math.min(totalLines, end1);

            for (int i = fromIdx; i < toIdx; i++) {
                String line = allLines.get(i);
                int lineNum = i + 1;
                if (line.length() > 2000) {
                    line = line.substring(0, 2000) + "... (line truncated to 2000 chars)";
                }
                if (lineNumbers) {
                    out.append(lineNum).append(": ").append(line).append("\n");
                } else {
                    out.append(line).append("\n");
                }
            }

            if (lineNumbers) {
                out.append("\n");
                if (toIdx >= totalLines) {
                    out.append("(End of file - total ").append(totalLines).append(" lines)\n");
                } else {
                    out.append("(Showing lines ").append(start1).append("-").append(toIdx)
                            .append(" of ").append(totalLines)
                            .append(". Use offset=").append(toIdx + 1).append(" to continue.)\n");
                }
                out.append("</content>");
            }

            return out.toString();

        } catch (Exception e) {
            return "ERROR reading file: " + e.getMessage();
        }
    }

    public static String fileWrite(NaruTask task, String path, String content,Boolean dry) {
        if (NBlankable.isBlank(path)) return "ERROR: 'path' is required.";
        if (content == null) content = "";

        NPath p = task.resolve(path);
        try {
            if (Boolean.TRUE.equals(dry)) {
                String preview = content.length() > previewChars(task)
                        ? content.substring(0, previewChars(task)) + "\n... [preview truncated]"
                        : content;
                return String.format("DRY RUN SUCCESS: Would %s content. Preview:\n```\n%s\n```",
                        "replace all", preview, p.name(), preview);
            }
            p.mkParentDirs().writeString(content);
            return "Wrote file successfully.";
        } catch (Exception e) {
            return "ERROR writing file: " + e.getMessage();
        }
    }

    public static String folderFind(NaruTask task, String path, String pattern, Boolean regex, Boolean caseSensitive, Integer contextLines, Integer maxMatches,
                                    Integer maxFiles, Boolean recursive, String includeGlob, String excludeGlob,String modified_after0,String modified_before0) {
        if (path == null) return "ERROR: 'path' is required.";
        boolean regexOk = NUtils.firstNonNull(regex, false);
        boolean caseSensitiveOk = NUtils.firstNonNull(caseSensitive, false);
        int contextLinesOk = contextLines != null ? Math.max(0, contextLines) : findContextLines(task);
        int maxMatchesOk = maxMatches != null ? Math.max(1, maxMatches) : findMaxMatches(task);
        int maxFilesOk = maxFiles != null ? Math.max(1, maxFiles) : findMaxFiles(task);
        boolean recursiveOk = NUtils.firstNonNull(recursive, true);


        // Parse the modification date boundaries if present
        Instant modifiedAfter = null;
        Instant modifiedBefore = null;
        try {
            modifiedAfter = parseDate(modified_after0);
            modifiedBefore = parseDate(modified_before0);
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        }
        NBooleanRef truncated = NBooleanRef.of(false);
        NIntRef totalMatches = NIntRef.of(0);

        NPath root = task.resolve(path);
        if (!root.exists()) return "ERROR: Path not found: " + root;
        if (!root.isDirectory()) return "ERROR: Path is not a directory: " + root;
        if (!root.permissions().contains(NPathPermission.CAN_READ)) return "ERROR: Directory is not readable: " + root;

        Pattern compiledPattern = null;
        if (pattern != null && regexOk) {
            try {
                int flags = caseSensitiveOk ? 0 : Pattern.CASE_INSENSITIVE;
                compiledPattern = Pattern.compile(pattern, flags);
            } catch (PatternSyntaxException e) {
                return "ERROR: Invalid regex pattern: " + e.getMessage();
            }
        }

        Predicate<NPath> includeMatcher = createMatcher(includeGlob, root);
        Predicate<NPath> excludeMatcher = createMatcher(excludeGlob, root);

        try {
            // Collect matching files applying names, globs, and historical time gates

            StringBuilder out = new StringBuilder();
            List<NPath> files = collectFiles(task, root, recursiveOk, includeMatcher, excludeMatcher, modifiedAfter, modifiedBefore, maxFilesOk, out, pattern, compiledPattern, root, truncated, totalMatches, contextLinesOk, maxMatchesOk);
            if (files.isEmpty()) {
                return "No files found";
            }

            if (pattern == null) {
                // Opencode glob format: newline-delimited paths
                StringBuilder res = new StringBuilder();
                for (int i = 0; i < files.size(); i++) {
                    if (i > 0) res.append("\n");
                    res.append(files.get(i).toString());
                }
                return res.toString();
            }

            if (truncated.get()) {
                out.append("\n[WARNING: Results limited. Refine search criteria.]\n");
            }
            out.append(String.format("Found %d file(s) in %s:\n%s", files.size(), root.name(), out.toString().trim()));

            return out.toString();
        } catch (Exception e) {
            return "ERROR searching directory: " + e.getMessage();
        }
    }

    /**
     * First file under {@code path} whose NAME matches {@code include} (a glob),
     * or {@code null} when none / when the path is not a readable directory.
     * The returned path is RELATIVE to the searched root when it lies inside it
     * (absolute otherwise), so scripts can hand it straight to maven/java
     * invoked with the project directory as cwd ({@code mvn -o -q -f pom.xml},
     * {@code java -cp proj/target/classes ...}).
     */
    public static String folderFindFirst(NaruTask task, String path, String includeGlob, Boolean recursive) {
        if (NBlankable.isBlank(path) || NBlankable.isBlank(includeGlob)) {
            return null;
        }
        boolean recursiveOk = NUtils.firstNonNull(recursive, true);
        NPath root = task.resolve(path);
        if (!root.exists() || !root.isDirectory()) {
            return null;
        }
        Predicate<NPath> includeMatcher = createMatcher(includeGlob);
        List<NPath> files = collectFiles(task, root, recursiveOk, includeMatcher, null, null, null, 1,
                new StringBuilder(), null, null, root,
                NBooleanRef.of(false), NIntRef.of(0), 0, 1);
        if (files.isEmpty()) {
            return null;
        }
        NPath f = files.get(0);
        NOptional<String> rel = root.relativize(f);
        return rel.isPresent() ? rel.get() : f.toString();
    }

    private static boolean fileContentMatches(NaruTask task, String pattern, NPath file, Pattern compiledPattern, boolean caseSensitive, boolean regex, NBooleanRef truncated, NIntRef totalMatches, StringBuilder out, int contextLines, int maxMatches, NPath root) {
        if (!file.exists() || !file.isRegularFile()) return false;
        if (isBinary(file)) return false;
        if (totalMatches.get() >= grepMaxTotalMatches(task)) {
            truncated.set(true);
            return false;
        }

        String fileResult = searchFile(file, pattern, compiledPattern, caseSensitive, regex, contextLines, maxMatches, root);
        if (fileResult != null) {
            totalMatches.add(countMatchesInResult(fileResult));
            out.append(fileResult).append("\n");
            int outputMax = grepMaxOutputChars(task);
            if (out.length() > outputMax) {
                truncated.set(true);
                String u = out.substring(0, outputMax) + "\n... [output truncated]";
                out.delete(0, out.length());
                out.append(u);
            }
            return true;
        }
        return false;
    }

    private static String searchFile(NPath file, String pattern, Pattern compiled, boolean caseSensitive,
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
                        sb.append("=== ").append(file).append(" ===\n");
//                        sb.append("=== ").append(root.relativize(file)).append(" ===\n");
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

    private static boolean matches(String line, String pattern, Pattern compiled, boolean caseSensitive, boolean regex) {
        if (regex) return compiled.matcher(line).find();
        if (caseSensitive) return line.contains(pattern);
        return line.toLowerCase().contains(pattern.toLowerCase());
    }

    private static String formatLine(int lineNum, String content, boolean isMatch) {
        return String.format("L%04d:%s%s", lineNum, content, isMatch ? " [MATCH]" : "");
    }

    private static List<NPath> collectFiles(NaruTask task, NPath dir, boolean recursive, Predicate<NPath> include, Predicate<NPath> exclude,
                                            Instant after, Instant before, int limit, StringBuilder out, String pattern,
                                            Pattern compiledPattern,
                                            NPath root,
                                            NBooleanRef truncated,
                                            NIntRef totalMatches,
                                            int contextLines,
                                            int maxMatches
    ) {
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
                    if (pattern == null) {
                        out.append(current).append("\n");
//                        out.append(root.relativize(current).orElse(current.toString())).append("\n");
                        result.add(current);
                    } else {
                        boolean matches = fileContentMatches(task, pattern, current, compiledPattern, true, true, truncated, totalMatches, out, contextLines, maxMatches, root);
                        if (matches) {
                            result.add(current);
                            if (truncated.get()) {
                                return result;
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static boolean matchesTimeBounds(NPath file, Instant after, Instant before) {
        try {
            Instant modTime = file.lastModifiedInstant();
            if (after != null && modTime.isBefore(after)) return false;
            return before == null || !modTime.isAfter(before);
        } catch (Exception e) {
            // If we can't extract structural metadata, skip safely or exclude depending on strictness
            return false;
        }
    }

    private static Instant parseDate(String input) {
        if (NBlankable.isBlank(input)) return null;
        String val = input.trim();
        try {
            // Handle human relative offsets like "-24h" or "-7d"
            if (val.startsWith("-")) {
                long magnitude = Long.parseLong(val.substring(1, val.length() - 1));
                char unit = Character.toLowerCase(val.charAt(val.length() - 1));
                switch (unit) {
                    case 'h':
                        return Instant.now().minusSeconds(magnitude * 3600);
                    case 'd':
                        return Instant.now().minusSeconds(magnitude * 86400);
                    case 'm':
                        return Instant.now().minusSeconds(magnitude * 60);
                    default:
                        throw new IllegalArgumentException("Unknown relative duration metric unit: " + unit);
                }
            }
            return Instant.parse(val);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid timestamp value '" + input + "'. Use ISO-8601 strings or offsets like '-48h'.");
        }
    }

    private static Predicate<NPath> createMatcher(String glob) {
        return createMatcher(glob, null);
    }

    private static Predicate<NPath> createMatcher(String glob, NPath root) {
        if (NBlankable.isBlank(glob)) return null;
        String[] parts = glob.split(",");
        List<Pattern> patterns = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                patterns.add(Pattern.compile(globToRegex(trimmed)));
            }
        }
        if (patterns.isEmpty()) return null;
        return path -> {
            String name = path.name();
            for (Pattern pattern : patterns) {
                if (pattern.matcher(name).matches()) return true;
            }
            if (root != null) {
                NOptional<String> rel = root.relativize(path);
                if (rel.isPresent()) {
                    String relStr = rel.get();
                    for (Pattern pattern : patterns) {
                        if (pattern.matcher(relStr).matches()) return true;
                    }
                }
            }
            return false;
        };
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = glob.length();
        while (i < len) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < len && glob.charAt(i + 1) == '*') {
                    if (i + 2 < len && glob.charAt(i + 2) == '/') {
                        sb.append("(?:.*/)?");
                        i += 3;
                    } else {
                        sb.append(".*");
                        i += 2;
                    }
                } else {
                    sb.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else if ("+(){}[].^$|\\".indexOf(c) >= 0) {
                sb.append('\\').append(c);
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean isBinary(NPath file) {
        String ext = file.nameParts().extension().toLowerCase();
        if (BINARY_EXTENSIONS.contains(ext) || BINARY_EXTENSIONS.contains("." + ext)) {
            return true;
        }
        try (java.io.InputStream in = file.getInputStream()) {
            byte[] buf = new byte[512];
            int n = in.read(buf);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static List<NPath> safeList(NPath dir) {
        try {
            return dir.list();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static int countMatchesInResult(String result) {
        int count = 0, idx = 0;
        String marker = "[MATCH]";
        while ((idx = result.indexOf(marker, idx)) != -1) {
            count++;
            idx += marker.length();
        }
        return count;
    }

    public static String snippet(String content) {
        if (NBlankable.isBlank(content)) {
            return "";
        }
        String cc = content.substring(0, Math.min(content.length(), 40));
        return cc.replace("\r\n", " ").replace("\n", " ");
    }

}
