package net.thevpc.naru.desktop.diff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The compact working-tree change counts shown beside the live activity verb.
 * <p>
 * The Changes tab parses full unified diffs; this is the much smaller read the activity strip can
 * afford while a turn runs: {@code git diff --numstat} for the working tree and the index. It is
 * intentionally detached from {@link DiffModel} — a failed or slow git read here must only hide the
 * mini summary, never disturb the full diff view.
 */
public final class GitChangeSummary {

    private static final long GIT_TIMEOUT_SECONDS = 10;

    /** One file and the line counts git reported for it, across the index and working tree. */
    public record FileChange(String path, int added, int deleted, boolean binary) {
        FileChange plus(FileChange other) {
            return new FileChange(path, added + other.added, deleted + other.deleted,
                    binary || other.binary);
        }
    }

    /** An immutable view for the strip; {@code files} is empty when there is nothing to show. */
    public record Snapshot(List<FileChange> files, int added, int deleted) {
        public Snapshot {
            files = List.copyOf(files);
        }

        public static Snapshot empty() {
            return new Snapshot(List.of(), 0, 0);
        }

        public int fileCount() {
            return files.size();
        }

        public boolean hasChanges() {
            return !files.isEmpty();
        }
    }

    private GitChangeSummary() {
    }

    /**
     * Read the repository that contains {@code projectDir}. The read is bounded and quiet: a
     * missing git executable, a directory outside a repository, or a slow process all produce an
     * empty snapshot rather than an exception in the UI.
     */
    public static Snapshot read(Path projectDir) {
        if (projectDir == null || !Files.isDirectory(projectDir)) {
            return Snapshot.empty();
        }
        String top = git(projectDir, "rev-parse", "--show-toplevel");
        if (top == null || top.isBlank()) {
            return Snapshot.empty();
        }
        Path repo;
        try {
            repo = Paths.get(top.strip().lines().findFirst().orElse("")).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return Snapshot.empty();
        }
        if (!Files.isDirectory(repo)) {
            return Snapshot.empty();
        }
        String unstaged = git(repo, "diff", "--numstat", "--no-ext-diff", "--no-color", "--");
        String staged = git(repo, "diff", "--cached", "--numstat", "--no-ext-diff", "--no-color");
        if (unstaged == null || staged == null) {
            return Snapshot.empty();
        }
        return merge(unstaged, staged);
    }

    /** Merge two numstat streams; staged and working-tree edits to the same file add together. */
    public static Snapshot merge(String unstaged, String staged) {
        Map<String, FileChange> byPath = new LinkedHashMap<>();
        parseInto(byPath, unstaged);
        parseInto(byPath, staged);
        List<FileChange> files = new ArrayList<>(byPath.values());
        files.sort((a, b) -> a.path().compareToIgnoreCase(b.path()));
        int added = 0;
        int deleted = 0;
        for (FileChange file : files) {
            added += file.added();
            deleted += file.deleted();
        }
        return new Snapshot(files, added, deleted);
    }

    private static void parseInto(Map<String, FileChange> byPath, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] columns = line.split("\\t", 3);
            if (columns.length < 3) {
                continue;
            }
            String added = columns[0].strip();
            String deleted = columns[1].strip();
            String path = columns[2].strip();
            if (path.isEmpty()) {
                continue;
            }
            boolean binary = "-".equals(added) || "-".equals(deleted);
            FileChange change = new FileChange(path, number(added), number(deleted), binary);
            byPath.merge(path, change, FileChange::plus);
        }
    }

    private static int number(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String git(Path repo, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repo.toString());
        command.addAll(List.of(args));
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return new String(output, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
