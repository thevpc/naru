package net.thevpc.naru.impl.registry.builtindirectives.fs;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.io.NPathPermission;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NBooleanRef;
import net.thevpc.nuts.util.NMemorySize;
import net.thevpc.nuts.util.NMemoryUnit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class NaruLsDirective extends AbstractDirective {
    public NaruLsDirective() {
        super("ls", "fs", "list directory");
        register(new AbstractSubCommand() {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();

                NBooleanRef optAll = NBooleanRef.of(false); // -a
                NBooleanRef optAlmost = NBooleanRef.of(false); // -A (no . and ..)
                NBooleanRef optLong = NBooleanRef.of(false); // -l
                NBooleanRef optDirOnly = NBooleanRef.of(false); // -d
                NBooleanRef optHuman = NBooleanRef.of(false); // -h
                NBooleanRef optReverse = NBooleanRef.of(false); // -r
                NBooleanRef optSortTime = NBooleanRef.of(false); // -t
                NBooleanRef optSortSize = NBooleanRef.of(false); // -S
                NBooleanRef optNoSort = NBooleanRef.of(false); // -U
                NBooleanRef optRecursive = NBooleanRef.of(false); // -R
                NBooleanRef optClassify = NBooleanRef.of(false); // -F
                List<String> targets = new ArrayList<>();

                NCmdLine args = NCmdLine.of(NBlankable.isBlank(context.argument())
                        ? new String[0]
                        : NCmdLine.parse(context.argument()).get().toStringArray());

                args.matcher()
                        .with("-a").matchTrueFlag(a -> optAll.set())
                        .with("-A").matchTrueFlag(a -> optAlmost.set())
                        .with("-l").matchTrueFlag(a -> optLong.set())
                        .with("-d").matchTrueFlag(a -> optDirOnly.set())
                        .with("-h").matchTrueFlag(a -> optHuman.set())
                        .with("-r").matchTrueFlag(a -> optReverse.set())
                        .with("-t").matchTrueFlag(a -> optSortTime.set())
                        .with("-S").matchTrueFlag(a -> optSortSize.set())
                        .with("-U").matchTrueFlag(a -> optNoSort.set())
                        .with("-R").matchTrueFlag(a -> optRecursive.set())
                        .with("-F").matchTrueFlag(a -> optClassify.set())
                        .withNonOption().matchAny(a -> targets.add(a.image()))
                        .requireAll();

                NPath baseDir = task.workingDir();
                List<NPath> targetPaths = targets.isEmpty()
                        ? Collections.singletonList(baseDir)
                        : targets.stream()
                          .map(t -> {
                              NPath p = NPath.of(t);
                              return p.isAbsolute() ? p : baseDir.resolve(t);
                          })
                          .collect(Collectors.toList());

                NTextBuilder result = NTextBuilder.of();
                for (NPath target : targetPaths) {
                    if (targetPaths.size() > 1) {
                        result.append(target).append(":\n");
                    }
                    renderPath(target, optAll.get(), optAlmost.get(), optLong.get(), optDirOnly.get(),
                            optHuman.get(), optReverse.get(), optSortTime.get(), optSortSize.get(),
                            optNoSort.get(), optRecursive.get(), optClassify.get(), result);
                    if (targetPaths.size() > 1) {
                        result.append("\n");
                    }
                }

                NText resultStr = result.build();
                task.addHistory(NaruMessage.user(NMsg.ofC("call   : ls %s", context.argument()).toString()));
                task.addHistory(NaruMessage.user(NMsg.ofC("result :\n%s", resultStr).toString()));
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", resultStr));
            }

            private void renderPath(NPath path,
                                    boolean optAll, boolean optAlmost, boolean optLong,
                                    boolean optDirOnly, boolean optHuman, boolean optReverse,
                                    boolean optSortTime, boolean optSortSize, boolean optNoSort,
                                    boolean optRecursive, boolean optClassify,
                                    NTextBuilder result) {
                if (!path.exists()) {
                    result.append("ls: cannot access '").append(path).append("': No such file or directory\n");
                    return;
                }

                // -d: show the directory itself, not its contents
                if (optDirOnly || !path.isDirectory()) {
                    result.append(formatEntry(path, optLong, optHuman, optClassify)).append("\n");
                    return;
                }

                List<NPath> children = path.list();

                // Filter hidden
                if (!optAll && !optAlmost) {
                    children = children.stream()
                            .filter(p -> !p.name().startsWith("."))
                            .collect(Collectors.toList());
                }

                // Sort
                if (!optNoSort) {
                    Comparator<NPath> cmp;
                    if (optSortTime) {
                        cmp = Comparator.comparing(p -> p.lastModifiedInstant());
                        cmp = cmp.reversed(); // newest first like real ls
                    } else if (optSortSize) {
                        cmp = Comparator.comparingLong(NPath::contentLength).reversed();
                    } else {
                        cmp = Comparator.comparing(p -> p.name().toLowerCase());
                    }
                    if (optReverse) cmp = cmp.reversed();
                    children.sort(cmp);
                }

                // . and .. for -a
                if (optAll) {
                    result.append(formatEntry(path, optLong, optHuman, optClassify, ".")).append("\n");
                    NPath parent = path.parent();
                    if (parent != null) {
                        result.append(formatEntry(parent, optLong, optHuman, optClassify, "..")).append("\n");
                    }
                }

                if (optLong) {
                    long total = children.stream().mapToLong(NPath::contentLength).sum() / 512;
                    result.append("total ").append(total).append("\n");
                }

                for (NPath child : children) {
                    result.append(formatEntry(child, optLong, optHuman, optClassify)).append("\n");
                }

                // -R: recurse into subdirectories
                if (optRecursive) {
                    for (NPath child : children) {
                        if (child.isDirectory()) {
                            result.append("\n").append(child).append(":\n");
                            renderPath(child, optAll, optAlmost, optLong, false, optHuman,
                                    optReverse, optSortTime, optSortSize, optNoSort, true, optClassify, result);
                        }
                    }
                }
            }

            private NMsg formatEntry(NPath p, boolean longFmt, boolean human, boolean classify) {
                return formatEntry(p, longFmt, human, classify, null);
            }

            private NMsg formatEntry(NPath p, boolean longFmt, boolean human, boolean classify, String nameOverride) {
                Set<NPathPermission> permissions = p.permissions();
                NMsg colored = coloredName(p, classify, permissions, nameOverride);
                if (!longFmt) return colored;

                // Long format: permissions  size  date  name
                String perms = p.isDirectory() ? "d" : "-";
                perms += permissions.contains(NPathPermission.OWNER_READ) ? "r" : "-";
                perms += permissions.contains(NPathPermission.OWNER_WRITE) ? "w" : "-";
                perms += permissions.contains(NPathPermission.OWNER_EXECUTE) ? "x" : "-";
                perms += permissions.contains(NPathPermission.GROUP_READ) ? "r" : "-";
                perms += permissions.contains(NPathPermission.GROUP_WRITE) ? "w" : "-";
                perms += permissions.contains(NPathPermission.GROUP_EXECUTE) ? "x" : "-";
                perms += permissions.contains(NPathPermission.OTHERS_READ) ? "r" : "-";
                perms += permissions.contains(NPathPermission.OTHERS_WRITE) ? "w" : "-";
                perms += permissions.contains(NPathPermission.OTHERS_EXECUTE) ? "x" : "-";

                long size = p.contentLength();
                String sizeStr = human ? humanSize(size) : String.valueOf(size);

                Instant modified = p.lastModifiedInstant();
                String dateStr = modified != null
                        ? DateTimeFormatter.ofPattern("MMM dd HH:mm")
                          .withZone(ZoneId.systemDefault())
                          .format(modified)
                        : "??? ?? ??:??";

                return NMsg.ofC("%-10s %8s %s %s", perms, sizeStr, dateStr, colored);
            }

            private String humanSize(long bytes) {
                return NMemorySize.ofBytes(bytes).normalize().canonicalize().toString();
            }
        });
    }

    private NMsg coloredName(NPath p, boolean classify, Set<NPathPermission> permissions, String nameOverride) {
        String name = nameOverride != null ? nameOverride : p.name();
        boolean isExec = permissions.contains(NPathPermission.OWNER_EXECUTE)
                || permissions.contains(NPathPermission.GROUP_EXECUTE)
                || permissions.contains(NPathPermission.OTHERS_EXECUTE);

        String suffix = "";
        if (classify) {
            if (p.isDirectory()) suffix = "/";
            else if (isExec) suffix = "*";
        }

        if (p.isDirectory()) return NMsg.ofStyledPrimary1(name + suffix);
        if (p.isSymbolicLink()) return NMsg.ofStyledPrimary2(name + suffix);
        if (isArchive(name)) return NMsg.ofStyledDanger(name + suffix);
        if (isImage(name)) return NMsg.ofStyledWarn(name + suffix);
        if (isMedia(name)) return NMsg.ofStyledSuccess(name + suffix);
        if (isDoc(name)) return NMsg.ofStyledPrimary4(name + suffix);
        if (isCode(name)) return NMsg.ofStyledPrimary3(name + suffix);
        if (isExec) return NMsg.ofStyledSuccess(name + suffix);
        return NMsg.ofC("%s%s", name, suffix);
    }

    private boolean isArchive(String name) {
        return matchesExt(name, "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "tgz", "tbz2", "zst", "lz4", "jar", "war", "ear");
    }

    private boolean isImage(String name) {
        return matchesExt(name, "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "ico", "tiff", "tif", "psd", "xcf");
    }

    private boolean isMedia(String name) {
        return matchesExt(name, "mp3", "mp4", "mkv", "avi", "mov", "flac", "ogg", "wav", "aac", "webm", "m4a", "m4v");
    }

    private boolean isDoc(String name) {
        return matchesExt(name, "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "md", "rst", "txt", "csv");
    }

    private boolean isCode(String name) {
        return matchesExt(name, "java", "py", "js", "ts", "c", "cpp", "h", "hpp", "rs", "go", "rb", "sh", "bash", "zsh",
                "json", "xml", "yaml", "yml", "toml", "properties", "sql", "html", "css", "kt", "scala", "lua");
    }

    private boolean matchesExt(String name, String... exts) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase();
        for (String e : exts) if (e.equals(ext)) return true;
        return false;
    }
}