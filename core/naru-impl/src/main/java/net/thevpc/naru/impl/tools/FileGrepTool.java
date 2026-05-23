package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathPermission;
import net.thevpc.nuts.util.NIterator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FileGrepTool implements NaruTool {

    private static final int MAX_OUTPUT_CHARS = 6_000;
    private static final int DEFAULT_CONTEXT = 2;
    private static final int DEFAULT_MAX_MATCHES = 50;

    @Override
    public String getName() { return "file_grep"; }

    @Override
    public String getDescription(NaruSession session) {
        return "Search for lines in a text file matching a pattern. Supports literal and regex search with surrounding context.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                getName(), getDescription(session),
                NaruToolParameter.string("path", "File to search", true),
                NaruToolParameter.string("pattern", "Search pattern", true),
                NaruToolParameter.bool("regex", "Treat pattern as regex", false, false),
                NaruToolParameter.bool("case_sensitive", "Case-sensitive match", false, false),
                NaruToolParameter.integer("context_lines", "Lines of context before/after match", false, DEFAULT_CONTEXT),
                NaruToolParameter.integer("max_matches", "Maximum matches to return", false, DEFAULT_MAX_MATCHES)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String path = context.stringArg("path").onBlankEmpty().orNull();
        String pattern = context.stringArg("pattern").onBlankEmpty().orNull();
        boolean regex = context.booleanArg("regex").orFalse();
        boolean caseSensitive = context.booleanArg("case_sensitive").orFalse();
        int contextLines = Math.max(0, context.intArg("context_lines").orElse(DEFAULT_CONTEXT));
        int maxMatches = Math.max(1, context.intArg("max_matches").orElse(DEFAULT_MAX_MATCHES));

        if (path == null) return "ERROR: 'path' is required.";
        if (pattern == null) return "ERROR: 'pattern' is required.";

        NPath p = context.session().resolve(path);
        if (!p.exists()) return "ERROR: File not found: " + p;
        if (!p.permissions().contains(NPathPermission.CAN_READ)) return "ERROR: File is not readable: " + p;

        Pattern compiledPattern = null;
        if (regex) {
            try {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
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
                boolean isMatch = matches(line, pattern, compiledPattern, caseSensitive, regex);

                if (isMatch) {
                    // 1. Output "before" context from deque
                    int bufferStart = lineNum - beforeBuffer.size();
                    for (String ctx : beforeBuffer) {
                        outputLines.add(formatLine(bufferStart++, ctx, false));
                    }
                    // 2. Output match
                    outputLines.add(formatLine(lineNum, line, true));
                    matchCount++;

                    // 3. Prepare for "after" context
                    afterContextRemaining = contextLines;
                    beforeBuffer.clear(); // reset buffer for next search block
                } else if (afterContextRemaining > 0) {
                    // Part of "after" context from previous match
                    outputLines.add(formatLine(lineNum, line, false));
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

    private boolean matches(String line, String pattern, Pattern compiled, boolean caseSensitive, boolean regex) {
        if (regex) return compiled.matcher(line).find();
        if (caseSensitive) return line.contains(pattern);
        return line.toLowerCase().contains(pattern.toLowerCase());
    }

    private String formatLine(int lineNum, String content, boolean isMatch) {
        return String.format("L%04d:%s%s", lineNum, content, isMatch ? " [MATCH]" : "");
    }
}