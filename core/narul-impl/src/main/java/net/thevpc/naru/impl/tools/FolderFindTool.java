package net.thevpc.naru.impl.tools;

import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruToolDefinition;
import net.thevpc.naru.api.tool.NaruRegistry;
import net.thevpc.naru.api.tool.NaruTool;
import net.thevpc.naru.api.tool.NaruToolCallContext;
import net.thevpc.naru.api.tool.NaruToolParameter;
import net.thevpc.nuts.expr.NGlob;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathPermission;
import net.thevpc.nuts.io.NPathType;
import net.thevpc.nuts.util.NBlankable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class FolderFindTool implements NaruTool {

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_OUTPUT_CHARS = 8_000;
    private static final DateTimeFormatter OUT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Set<String> FILE_TYPES = new HashSet<>(Arrays.asList("file", "directory", "dir", "symlink"));

    @Override
    public String getName() {
        return "folder_find";
    }

    @Override
    public String getDescription(NaruSession session) {
        return "Find files/dirs by name, glob, modification date, size, or type. " +
                "Supports relative dates (-1d, -2h) and human sizes (1k, 5m, 1g).";
    }

    @Override
    public NaruToolDefinition getDefinition(NaruSession session) {
        return NaruRegistry.buildDefinition(
                getName(), getDescription(session),
                NaruToolParameter.string("path", "Directory to search", true),
                NaruToolParameter.string("name", "Exact filename match", false),
                NaruToolParameter.string("glob", "Glob pattern (e.g. *.java, test_*.py)", false),
                NaruToolParameter.string("type", "Filter by type: file, directory, symlink", false),
                NaruToolParameter.string("modified_after", "ISO-8601 or relative (-1d, -2h, -30m)", false),
                NaruToolParameter.string("modified_before", "ISO-8601 or relative (-1d, -2h)", false),
                NaruToolParameter.string("min_size", "Minimum size (e.g. 1k, 5m, 100)", false),
                NaruToolParameter.string("max_size", "Maximum size (e.g. 10m, 1g)", false),
                NaruToolParameter.bool("recursive", "Search subdirectories", false, true),
                NaruToolParameter.integer("max_depth", "Max directory depth (1=root only)", false, 1),
                NaruToolParameter.integer("max_results", "Maximum results to return", false, DEFAULT_MAX_RESULTS),
                NaruToolParameter.string("sort", "Sort results: none, name, size, modified", false, "none"),
                NaruToolParameter.bool("include_hidden", "Include dotfiles/dirs", false, false)
        );
    }

    @Override
    public String execute(NaruToolCallContext context) {
        String pathStr = context.stringArg("path").onBlankEmpty().orNull();
        if (pathStr == null) return "ERROR: 'path' is required.";

        NPath root = context.session().resolve(pathStr);
        if (!root.exists()) return "ERROR: Path not found: " + root;
        if (!root.isDirectory()) return "ERROR: Path is not a directory: " + root;

        String nameFilter = context.stringArg("name").orNull();
        String globFilter = context.stringArg("glob").orNull();
        String typeFilter = context.stringArg("type").orNull();
        Instant after = parseDate(context.stringArg("modified_after").orNull());
        Instant before = parseDate(context.stringArg("modified_before").orNull());
        Long minSize = parseSize(context.stringArg("min_size").orNull());
        Long maxSize = parseSize(context.stringArg("max_size").orNull());
        boolean recursive = context.booleanArg("recursive").orTrue();
        int maxDepth = Math.max(1, context.intArg("max_depth").orElse(recursive ? Integer.MAX_VALUE : 1));
        int maxResults = Math.max(1, Math.min(context.intArg("max_results").orElse(DEFAULT_MAX_RESULTS), 500));
        String sortBy = context.stringArg("sort").orElse("none").toLowerCase();
        boolean includeHidden = context.booleanArg("include_hidden").orFalse();

        Predicate<NPath> globMatcher = (globFilter != null)
                ? new Predicate<NPath>() {
            final Pattern p = NGlob.of().toPattern(globFilter);

            @Override
            public boolean test(NPath path) {
                return p.matcher(path.toString()).matches();
            }
        } : null;
        String typeNorm = (typeFilter != null) ? typeFilter.toLowerCase().trim() : null;
        if (typeNorm != null && !FILE_TYPES.contains(typeNorm)) {
            return "ERROR: Invalid 'type'. Must be: file, directory, symlink";
        }

        // ── Traversal ──────────────────────────────────────────────────────
        List<FoundEntry> results = new ArrayList<>(Math.min(maxResults, 200));
        Deque<PathState> stack = new ArrayDeque<>();
        stack.push(new PathState(root, 1));

        while (!stack.isEmpty() && results.size() < maxResults) {
            PathState state = stack.pop();
            NPath current = state.path;
            int depth = state.depth;

            if (!current.exists() || !current.permissions().contains(NPathPermission.CAN_READ)) continue;
            String fname = current.name();
            if (!includeHidden && fname != null && fname.startsWith(".")) {
                if (current.isDirectory()) continue; // skip hidden dirs entirely
                // skip hidden files unless explicitly allowed
            }

            // Apply filters
            if (matches(current, fname, nameFilter, globMatcher, typeNorm, after, before, minSize, maxSize)) {
                Instant modTime = safeLastModified(current);
                long size = safeSize(current);
                results.add(new FoundEntry(current, fname, size, modTime, current.isDirectory() ? "dir" : (current.isSymlink() ? "link" : "file")));
            }

            // Traverse children
            if (current.isDirectory() && recursive && depth < maxDepth) {
                try {
                    List<NPath> children = current.list();
                    // Reverse to maintain natural order when using stack (DFS)
                    for (int i = children.size() - 1; i >= 0; i--) {
                        stack.push(new PathState(children.get(i), depth + 1));
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (results.isEmpty()) return "No matching files found in: " + root;

        // ── Sort (only if requested & collected < 500 to avoid OOM) ────────
        if (!sortBy.equals("none") && results.size() < 500) {
            Comparator<FoundEntry> comp ;
            switch (sortBy) {
                case "name" :{
                    comp = Comparator.comparing(e -> e.name);
                    break;
                }
                case "size" :{
                    comp =Comparator.comparingLong(e -> e.size);
                    break;
                }
                case "modified" : {
                    comp =Comparator.comparing(e -> e.modified, Comparator.nullsFirst(Comparator.naturalOrder()));
                    break;
                }
                default :{
                    comp =null;
                }
            };
            if (comp != null) results.sort(comp);
        }

        // ── Format Output ──────────────────────────────────────────────────
        StringBuilder sb = new StringBuilder();
        for (FoundEntry e : results) {
            String dateStr = (e.modified != null) ? e.modified.atZone(ZoneId.systemDefault()).format(OUT_FMT) : "unknown";
            String sizeStr = formatSize(e.size);
            sb.append(String.format("%s | %s | %s | %s%n", e.path, sizeStr, dateStr, e.type));
        }

        String out = sb.toString();
        if (out.length() > MAX_OUTPUT_CHARS) {
            out = out.substring(0, MAX_OUTPUT_CHARS) + "\n... [truncated]";
        }

        String sortNote = sortBy.equals("none") ? "" : " (sorted by " + sortBy + ")";
        return String.format("Found %d result(s) in %s%s:\n%s", results.size(), root.name(), sortNote, out.trim());
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private boolean matches(NPath p, String fname, String name, Predicate<NPath> glob, String type,
                            Instant after, Instant before, Long minSize, Long maxSize) {
        if (name != null && !fname.equals(name)) return false;
        if (glob != null) {
            try {
                if (!glob.test(p)) return false;
            } catch (Exception ignored) {
                return false;
            }
        }
        if (type != null) {
            boolean isFile = p.isFile(), isDir = p.isDirectory(), isLink = p.type() == NPathType.SYMBOLIC_LINK;
            if (type.equals("file") && !isFile) return false;
            if ((type.equals("directory") || type.equals("dir")) && !isDir) return false;
            if (type.equals("symlink") && !isLink) return false;
        }
        Instant mod = safeLastModified(p);
        if (after != null && mod != null && mod.isBefore(after)) return false;
        if (before != null && mod != null && mod.isAfter(before)) return false;
        long size = safeSize(p);
        if (minSize != null && size < minSize) return false;
        return maxSize == null || size <= maxSize;
    }

    private Instant parseDate(String s) {
        if (NBlankable.isBlank(s)) return null;
        if (s.startsWith("-")) {
            try {
                char unit = Character.toLowerCase(s.charAt(s.length() - 1));
                long val = Long.parseLong(s.substring(0, s.length() - 1));
                Instant now = Instant.now();
                switch (unit) {
                    case 'd' : return now.minus(val, ChronoUnit.DAYS);
                    case 'h' : return now.minus(val, ChronoUnit.HOURS);
                    case 'm' : return now.minus(val, ChronoUnit.MINUTES);
                    case 's' : return now.minus(val, ChronoUnit.SECONDS);
                    case 'y' : return now.minus(val, ChronoUnit.YEARS);
                    default : throw new IllegalArgumentException("Invalid date unit");
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid relative date: " + s);
            }
        }
        return Instant.parse(s); // ISO-8601
    }

    private Long parseSize(String s) {
        if (NBlankable.isBlank(s)) return null;
        try {
            String lower = s.trim().toLowerCase();
            long multiplier = 1;
            if (lower.endsWith("k")) {
                multiplier = 1024;
                s = lower.substring(0, lower.length() - 1);
            } else if (lower.endsWith("m")) {
                multiplier = 1024 * 1024;
                s = lower.substring(0, lower.length() - 1);
            } else if (lower.endsWith("g")) {
                multiplier = 1024L * 1024 * 1024;
                s = lower.substring(0, lower.length() - 1);
            }
            return (long) (Double.parseDouble(s.trim()) * multiplier);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid size format: " + s);
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "b";
        if (bytes < 1024 * 1024) return String.format("%.1fk", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1fm", bytes / (1024.0 * 1024.0));
        return String.format("%.1fg", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private Instant safeLastModified(NPath p) {
        try {
            return p.lastModifiedInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private long safeSize(NPath p) {
        try {
            return p.contentLength();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static class PathState {
        NPath path;
        int depth;

        PathState(NPath p, int d) {
            path = p;
            depth = d;
        }
    }

    private static class FoundEntry {
        NPath path;
        String name, type;
        long size;
        Instant modified;

        FoundEntry(NPath p, String n, long s, Instant m, String t) {
            path = p;
            name = n;
            size = s;
            modified = m;
            type = t;
        }
    }
}