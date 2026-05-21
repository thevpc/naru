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

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads a text file from disk and returns its content.
 */
public class FileEditLinesTool implements NaruTool {

    private static final int MAX_PREVIEW_CHARS = 8_000;

    public FileEditLinesTool() {
    }

    @Override
    public String getName() {
        return "file_edit_lines";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Insert, replace, or delete lines in a text file. " +
                "Supports 0-based indexing and negative indices (-1 = last line, -2 = second-to-last). " +
                "Use dry=true to preview changes without writing.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return NaruRegistry.buildDefinition(
                getName(), getDescription(session),
                NaruToolParameter.string("path", "File path to edit", true),
                NaruToolParameter.string("from", "Start line index (0-based inclusive, supports negatives)", true),
                NaruToolParameter.string("to", "End line index (0-based exclusive). Omit or equal to 'from' to insert.", false),
                NaruToolParameter.string("content", "New lines (\\n separated). Leave empty to delete.", false),
                NaruToolParameter.bool("dry", "If true, preview changes without modifying the file", false)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String path = context.stringArg("path").onBlankEmpty().orNull();
        if (path == null) return "ERROR: 'path' is required.";

        NPath p = context.session().resolve(path);
        if (!p.exists()) return "ERROR: File not found: " + p;
        if (!p.permissions().contains(NPathPermission.CAN_WRITE)) return "ERROR: File is not writable: " + p;

        Long fromArg = context.longArg("from").orNull();
        Long toArg   = context.longArg("to").orNull();
        String content = context.stringArg("content").orNull();
        boolean dry = context.booleanArg("dry").orElse(false);

        if (fromArg == null) return "ERROR: 'from' is required.";

        try {
            List<String> lines = p.lines().stream().collect(Collectors.toList());
            int total = lines.size();

            int start = resolveIndex(fromArg, total);
            int end   = (toArg == null) ? start : resolveIndex(toArg, total);

            // Auto-swap if reversed (LLM-friendly)
            if (end < start) { int t = start; start = end; end = t; }

            // Clamp to valid bounds [0, total]
            start = Math.max(0, Math.min(start, total));
            end   = Math.max(0, Math.min(end, total));

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

    private int resolveIndex(Long idx, long total) {
        if (idx == null) return 0;
        if (idx < 0) return (int) Math.max(0, total + idx); // -1 → total-1, -2 → total-2
        return (int) Math.min(idx, total);                  // clamp large positives to total
    }

}
