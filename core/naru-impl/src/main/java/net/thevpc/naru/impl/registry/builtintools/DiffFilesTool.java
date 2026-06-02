package net.thevpc.naru.impl.registry.builtintools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.model.NaruToolDefinitionFunction;
import net.thevpc.naru.api.registry.NaruTool;
import net.thevpc.naru.api.registry.NaruToolCallContext;
import net.thevpc.naru.api.registry.NaruToolParameter;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes (or overwrites) a file on disk.
 */
public class DiffFilesTool implements NaruTool {
    @Override
    public String name() {
        return "diff";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Compare two text files and return their differences. " +
                "Useful for comparing .ntx source files or checking what changed between versions.";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return new NaruToolDefinitionFunction(
                name(),
                getDescription(session),
                NaruToolParameter.string("file1", "Path to the first file (original).", true).build(),
                NaruToolParameter.string("file2", "Path to the second file (modified).", true).build(),
                NaruToolParameter.integer("context_lines", "Number of context lines around each change (default: 3).", false).build()
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String path1 = context.stringArg("file1").orNull();
        String path2 = context.stringArg("file2").orNull();
        int contextLines = context.numberArg("context_lines").orElse(3).intValue();

        if (NBlankable.isBlank(path1)) return "Error: file1 is required.";
        if (NBlankable.isBlank(path2)) return "Error: file2 is required.";

        try {
            List<String> lines1 = context.task().resolve(path1).lines().toList();
            List<String> lines2 = context.task().resolve(path2).lines().toList();
            return computeUnifiedDiff(path1, path2, lines1, lines2, contextLines);
        } catch (Exception e) {
            return "Error reading files: " + e.getMessage();
        }
    }

    private String computeUnifiedDiff(String name1, String name2,
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

    private int[][] lcsTable(List<String> a, List<String> b) {
        int m = a.size(), n = b.size();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++)
            for (int j = 1; j <= n; j++)
                dp[i][j] = a.get(i - 1).equals(b.get(j - 1))
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
        return dp;
    }

    private List<String> buildHunks(List<String> a, List<String> b,
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

    private void walkEdits(List<String> a, List<String> b, int[][] lcs,
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
}
