package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathPermission;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads a text file from disk and returns its content.
 */
public class FileReadTool implements NaruTool {
    private static final int DEFAULT_MAX_LINES = 1000;
    private static final int MAX_OUTPUT_CHARS = 15_000;

    public FileReadTool() {
    }

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Read contents of a text file. If line constraints are provided, it performs a strict logical 'AND' " +
                "to slice the exact line range (0-indexed or 1-indexed depending on preference, standardizing on 1-based here). " +
                "Omitting bounds reads from line 1 up to a safe threshold (" + DEFAULT_MAX_LINES + " lines).";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(), getDescription(session),
                NaruToolParameter.string("path", "File path to read", true),
                NaruToolParameter.integer("line_start", "First line to read (1-based, optional, defaults to 1)", false, 1),
                NaruToolParameter.integer("line_end", "Last line to read (inclusive, optional, defaults to start + " + DEFAULT_MAX_LINES + ")", false)        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String pathStr = context.stringArg("path").onBlankEmpty().orNull();
        if (pathStr == null) return "ERROR: 'path' is required.";

        NPath file = context.session().resolve(pathStr);
        if (!file.exists()) return "ERROR: File not found: " + file;
        if (!file.isRegularFile()) return "ERROR: Path is not a regular file: " + file;
        if (!file.permissions().contains(NPathPermission.CAN_READ)) return "ERROR: File is not readable: " + file;

        // Model inputs are 1-based
        int start1 = Math.max(1, context.intArg("line_start").orElse(1));
        int end1 = context.intArg("line_end").orElse(start1 + DEFAULT_MAX_LINES - 1);

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


}
