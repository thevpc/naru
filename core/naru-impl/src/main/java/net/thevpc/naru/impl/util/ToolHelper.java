package net.thevpc.naru.impl.util;

import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.model.NaruModelConfig;
import net.thevpc.naru.api.model.NaruModelRequest;
import net.thevpc.naru.api.model.NaruResponse;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathOption;
import net.thevpc.nuts.io.NPathPermission;
import net.thevpc.nuts.net.NWebCli;
import net.thevpc.nuts.net.NWebResponse;
import net.thevpc.nuts.time.NDuration;
import net.thevpc.nuts.util.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

public class ToolHelper {
    public static final int MAX_PREVIEW_CHARS = 8_000;
    public static final int DEFAULT_CONTEXT = 2;
    public static final int DEFAULT_MAX_MATCHES = 50;
    public static final int DEFAULT_MAX_LINES = 1000;
    public static final int MAX_OUTPUT_CHARS = 15_000;

    public static final int MAX_TOTAL_MATCHES = 100;
    public static final int DEFAULT_MAX_FILES = 50;
    public static final Set<String> BINARY_EXTENSIONS = new HashSet<>(
            Arrays.asList(".class", ".jar", ".zip", ".tar", ".gz", ".png", ".jpg", ".jpeg",
                    ".gif", ".pdf", ".exe", ".dll", ".so", ".dylib", ".bin", ".dat")
    );

    public static String searchWeb(NaruTask task, String query) {
        if (query == null) {
            return "Error: query is required";
        }

        String r = "searching for " + query;

        NWebCli http = NWebCli.of()
                .connectTimeout(NDuration.ofSeconds(30));
        NWebResponse response = http.GET("https://html.duckduckgo.com/html/")
                .parameter("q", query)
                .header("User-Agent", "Mozilla/5.0")
                .run();
        String contentAsString = response.contentAsString();
        String contentAsString2 = contentAsString.replaceAll("<[^>]*>", "").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#x27;", "'");
        List<String> sb = new ArrayList<>();
        int maxLines = 300;
        boolean stripped = false;
        for (String s : contentAsString2.split("\n")) {
            s = s.trim();
            if (!s.isEmpty()) {
                if (stripped) {
                    sb.add(s);
                } else {
                    if (sb.size() > 3 && s.equals("Past Year") && sb.get(sb.size() - 1).equals("Past Month") && sb.get(sb.size() - 2).equals("Past Week")) {
                        sb.clear();
                        stripped = true;
                    } else {
                        sb.add(s);
                    }
                }
            }
        }
        return sb.stream().limit(maxLines).collect(Collectors.joining("\n"));
    }

    public static String listLines(NaruTask task, String scriptName, Number startNumObj, Number endNumObj) {
        if (NBlankable.isBlank(scriptName)) {
            scriptName = task.currentRoutineName();
        }

        // Temporarily switch context, put line, switch back
        String oldName = task.currentRoutineName();
        task.useRoutine(scriptName);
        TreeMap<Integer, String> lines = task.currentRoutine().get().getLinesSet(x -> {
            if (startNumObj != null && x < startNumObj.intValue()) return false;
            return endNumObj == null || x <= endNumObj.intValue();
        });
        task.useRoutine(oldName);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : lines.entrySet()) {
            sb.append(e.getKey()).append(" ").append(e.getValue()).append("\n");
        }
        return sb.toString();
    }


    public static String routineAddLine(NaruTask task, String scriptName, Number lineNumObj, String command) {
        if (NBlankable.isBlank(scriptName)) {
            scriptName = task.currentRoutineName();
        }

        if (lineNumObj == null) {
            return "Error: line_number is required";
        }
        int lineNum = lineNumObj.intValue();

        if (command == null) {
            return "Error: command is required";
        }

        String oldName = task.currentRoutineName();
        task.useRoutine(scriptName);
        task.setRoutineLine(lineNum, command);
        task.useRoutine(oldName);

        return "Successfully wrote line " + lineNum + " to script '" + scriptName + "'";
    }

    public static String callModel(NaruTask task, String modelName, String prompt, String imagePath) {

        if (NBlankable.isBlank(modelName)) return "Error: model_name is required.";
        if (NBlankable.isBlank(prompt)) return "Error: prompt is required.";

        List<NaruMessage> messages = new ArrayList<>();
        NaruMessage msg = NaruMessage.user(prompt);

        if (!NBlankable.isBlank(imagePath)) {
            try {
                String base64 = ImageUtil.toBase64(task.resolve(imagePath).toString());
                msg.setImages(Collections.singletonList(base64));
            } catch (Exception e) {
                return "Error loading image: " + e.getMessage();
            }
        }


        messages.add(msg);
        NaruModelConfig model = task.session().findModel(modelName).orNull();
        if (model == null) {
            return "Error: Model not found : " + modelName;
        }
        NaruModelConfig oldModel = task.model();
        task.setModel(model);
        Map<String, NElement> env = task.context(NaruSource.values()).env();
        try {
            NaruResponse response = task.chat(model,
                    new NaruModelRequest(messages,
                            env
                    )
            );
            if (response.getMessage() != null) {
                return response.getMessage().getContent();
            }
            return "Error: Model returned empty response.";
        } catch (Exception e) {
            return "Error calling model " + modelName + ": " + e.getMessage();
        } finally {
            task.setModel(oldModel);
        }
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
            if (dry) {
                String preview = content.length() > MAX_PREVIEW_CHARS
                        ? content.substring(0, MAX_PREVIEW_CHARS) + "\n... [preview truncated]"
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
            List<String> lines = p.lines().stream().collect(Collectors.toList());
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

            if (dry) {
                String preview = finalContent.length() > MAX_PREVIEW_CHARS
                        ? finalContent.substring(0, MAX_PREVIEW_CHARS) + "\n... [preview truncated]"
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

    private static int resolveIndex(Long idx, long total) {
        if (idx == null) return 0;
        if (idx < 0) return (int) Math.max(0, total + idx); // -1 → total-1, -2 → total-2
        return (int) Math.min(idx, total);                  // clamp large positives to total
    }

    public static String fileGrep(NaruTask task, String path, String pattern, Boolean regex, Boolean caseSensitive, Integer contextLines0, Integer maxMatches0) {
        int contextLines = Math.max(0, NUtils.firstNonNull(contextLines0, DEFAULT_CONTEXT));
        int maxMatches = Math.max(1, NUtils.firstNonNull(maxMatches0, DEFAULT_MAX_MATCHES));

        if (NBlankable.isBlank(path)) return "ERROR: 'path' is required.";
        if (NBlankable.isBlank(pattern)) return "ERROR: 'pattern' is required.";

        NPath p = task.resolve(path);
        if (!p.exists()) return "ERROR: File not found: " + p;
        if (!p.permissions().contains(NPathPermission.CAN_READ)) return "ERROR: File is not readable: " + p;

        Pattern compiledPattern = null;
        if (regex!=null && regex) {
            try {
                int flags = (!(caseSensitive!=null && !caseSensitive)) ? 0 : Pattern.CASE_INSENSITIVE;
                compiledPattern = Pattern.compile(pattern, flags);
            } catch (PatternSyntaxException e) {
                return "ERROR: Invalid regex pattern: " + e.getMessage();
            }
        }

        try {
            NIterator<String> it = p.lines().iterator();
            Deque<String> beforeBuffer = new ArrayDeque<>(contextLines + 1);
            int afterContextRemaining = 0;
            int matchCount = 0;
            int lineNum = 1;
            List<String> outputLines = new ArrayList<>();

            while (it.hasNext() && matchCount < maxMatches) {
                String line = it.next();
                boolean isMatch = _grep_matches(line, pattern, compiledPattern, caseSensitive, regex);

                if (isMatch) {
                    // 1. Output "before" context from deque
                    int bufferStart = lineNum - beforeBuffer.size();
                    for (String ctx : beforeBuffer) {
                        outputLines.add(_grep_formatLine(bufferStart++, ctx, false));
                    }
                    // 2. Output match
                    outputLines.add(_grep_formatLine(lineNum, line, true));
                    matchCount++;

                    // 3. Prepare for "after" context
                    afterContextRemaining = contextLines;
                    beforeBuffer.clear(); // reset buffer for next search block
                } else if (afterContextRemaining > 0) {
                    // Part of "after" context from previous match
                    outputLines.add(_grep_formatLine(lineNum, line, false));
                    afterContextRemaining--;
                } else {
                    // Normal line: keep in sliding window for future "before" context
                    beforeBuffer.add(line);
                    if (beforeBuffer.size() > contextLines) {
                        beforeBuffer.pollFirst();
                    }
                }
                lineNum++;
            }

            if (matchCount == 0) {
                return "No matches found for pattern: " + pattern;
            }

            String result = String.join("\n", outputLines);
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS) + "\n... [output truncated]";
            }

            return String.format("Found %d match(es) in %s:\n%s", matchCount, p.name(), result);
        } catch (Exception e) {
            return "ERROR searching file: " + e.getMessage();
        }
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
        if (!file.exists()) return "ERROR: File not found: " + file;
        if (!file.isRegularFile()) return "ERROR: Path is not a regular file: " + file;
        if (!file.permissions().contains(NPathPermission.CAN_READ)) return "ERROR: File is not readable: " + file;

        // Model inputs are 1-based
        long start1 = Math.max(1, NUtils.firstNonNull(lineStart, 1L));
        long end1 = NUtils.firstNonNull(lineEnd, start1 + DEFAULT_MAX_LINES - 1);

        if (end1 < start1) {
            return "ERROR: 'line_end' (" + end1 + ") cannot be less than 'line_start' (" + start1 + ").";
        }

        // Convert to framework 0-based indices
        // Assuming your lines(from, to) method works like standard sublists or streams (from inclusive, to exclusive)
        long from0 = start1 - 1;
        long to0 = end1;

        StringBuilder out = new StringBuilder();
        boolean truncated = false;

        try {
            List<String> targetedLines;
            // Leverage native framework 0-based stream windowing
            try (java.util.stream.Stream<String> stream = file.lines(from0, to0).stream()) {
                targetedLines = stream.collect(Collectors.toList());
            }

            if (targetedLines.isEmpty()) {
                return "File is empty or specified range [" + start1 + " to " + end1 + "] is out of file bounds.";
            }

            for (String line : targetedLines) {
                out.append(line).append("\n");
                if (out.length() > MAX_OUTPUT_CHARS) {
                    truncated = true;
                    break;
                }
            }

        } catch (Exception e) {
            return "ERROR streaming file lines via framework: " + e.getMessage();
        }

        String result = out.toString();
        if (truncated) {
            result += "\n... [Output Truncated for Context Safety]";
        }

        return result;
    }

    public static String fileWrite(NaruTask task, String path, String content,Boolean dry) {
        if (NBlankable.isBlank(path)) return "ERROR: 'path' is required.";
        if (content == null) content = "";

        NPath p = task.resolve(path);
        try {
            if (dry) {
                String preview = content.length() > MAX_PREVIEW_CHARS
                        ? content.substring(0, MAX_PREVIEW_CHARS) + "\n... [preview truncated]"
                        : content;
                return String.format("DRY RUN SUCCESS: Would %s content. Preview:\n```\n%s\n```",
                        "replace all", preview, p.name(), preview);
            }
            p.mkParentDirs().writeString(content);
            return "OK: wrote " + content.length() + " chars to " + p;
        } catch (Exception e) {
            return "ERROR writing file: " + e.getMessage();
        }
    }

    public static String folderFind(NaruTask task, String path, String pattern, Boolean regex, Boolean caseSensitive, Integer contextLines, Integer maxMatches,
                                    Integer maxFiles, Boolean recursive, String includeGlob, String excludeGlob,String modified_after0,String modified_before0) {
        if (path == null) return "ERROR: 'path' is required.";
        boolean regexOk = NUtils.firstNonNull(regex, false);
        boolean caseSensitiveOk = NUtils.firstNonNull(caseSensitive, false);
        int contextLinesOk = Math.max(0, NUtils.firstNonNull(contextLines, DEFAULT_CONTEXT));
        int maxMatchesOk = Math.max(1, NUtils.firstNonNull(maxMatches, DEFAULT_MAX_MATCHES));
        int maxFilesOk = Math.max(1, NUtils.firstNonNull(maxFiles, DEFAULT_MAX_FILES));
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

        Predicate<NPath> includeMatcher = createMatcher(includeGlob);
        Predicate<NPath> excludeMatcher = createMatcher(excludeGlob);

        try {
            // Collect matching files applying names, globs, and historical time gates

            StringBuilder out = new StringBuilder();
            List<NPath> files = collectFiles(root, recursiveOk, includeMatcher, excludeMatcher, modifiedAfter, modifiedBefore, maxFilesOk, out, pattern, compiledPattern, root, truncated, totalMatches, contextLinesOk, maxMatchesOk);
            if (files.isEmpty()) {
                return "No files found matching specified filters in: " + root;
            }

            // --- BRANCH A: Pure File Finder Operation ---
            if (truncated.get()) {
                out.append("\n[WARNING: Results limited. Refine search criteria.]\n");
            }
            out.append(String.format("Found %d file(s) in %s:\n%s", files.size(), root.name(), out.toString().trim()));

            return out.toString();
        } catch (Exception e) {
            return "ERROR searching directory: " + e.getMessage();
        }
    }

    private static boolean fileContentMatches(String pattern, NPath file, Pattern compiledPattern, boolean caseSensitive, boolean regex, NBooleanRef truncated, NIntRef totalMatches, StringBuilder out, int contextLines, int maxMatches, NPath root) {
        if (!file.exists() || !file.isRegularFile()) return false;
        if (isBinary(file)) return false;
        if (totalMatches.get() >= MAX_TOTAL_MATCHES) {
            truncated.set(true);
            return false;
        }

        String fileResult = searchFile(file, pattern, compiledPattern, caseSensitive, regex, contextLines, maxMatches, root);
        if (fileResult != null) {
            totalMatches.add(countMatchesInResult(fileResult));
            out.append(fileResult).append("\n");
            if (out.length() > MAX_OUTPUT_CHARS) {
                truncated.set(true);
                String u = out.substring(0, MAX_OUTPUT_CHARS) + "\n... [output truncated]";
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

    private static List<NPath> collectFiles(NPath dir, boolean recursive, Predicate<NPath> include, Predicate<NPath> exclude,
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
                        boolean matches = fileContentMatches(pattern, current, compiledPattern, true, true, truncated, totalMatches, out, contextLines, maxMatches, root);
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
        if (NBlankable.isBlank(glob)) return null;
        String[] parts = glob.split(",");
        return path -> Arrays.stream(parts).anyMatch(p -> path.name().matches(globToRegex(p.trim())));
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append(".");
            else if ("+(){}[].^$|".indexOf(c) >= 0) sb.append("\\").append(c);
            else sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isBinary(NPath file) {
        String ext = file.nameParts().extension().toLowerCase();
        return BINARY_EXTENSIONS.contains(ext);
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
}
