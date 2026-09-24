package net.thevpc.naru.ext.tools.fs;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NRef;

public class NaruFileDirective extends NaruDirectiveBase {
    public NaruFileDirective() {
        super("file", "fs", "manipulate a single file");
        register(new AbstractSubCommand("read", NText.ofPlain("read file content"),
                new SubCommandHelp("<path> [--from=<from>] [--to=<to>] [--save=<var>]", "read file content; publishes lastExitCode (0=ok,1=empty,2=error) and stores the trimmed content in the task var given by --save")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NArg filePath = cmdLine.next().orNull();
                if (filePath == null || filePath.isOption()) {
                    task.throwError(NMsg.ofC("missing file path"));
                }
                NRef<Long> from = NRef.of();
                NRef<Long> to = NRef.of();
                NRef<String> saveVar = NRef.of();
                cmdLine.matcher()
                        .when("--from", "from").asEntry(a -> from.set(a.longValue()))
                        .when("--to", "to").asEntry(a -> to.set(a.longValue()))
                        .when("--save").asEntry(a -> saveVar.set(a.stringValue()))
                        .requireAll();
                String result = FileToolHelper.fileRead(task, filePath.toString(),
                        from.get(), to.get()
                );
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                context.task().addHistory(NaruMessage.user(NMsg.ofC("file_read   path=%s  from=%s to=%s\n%s",
                        filePath, from, to, result
                ).toString()));
                publishFileResult(task, saveVar.get(), result,
                        result != null && result.startsWith("ERROR") ? 2
                                : result != null && result.startsWith("File is empty") ? 1 : 0);
            }
        });
        register(new AbstractSubCommand("write", NText.ofPlain("write file content"),
                new SubCommandHelp("<path> [--content=<content>] [--dry]", "write file content")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NArg filePath = cmdLine.next().orNull();
                if (filePath == null || filePath.isOption()) {
                    task.throwError(NMsg.ofC("missing file path"));
                    return;
                }
                NRef<String> content = NRef.of();
                NRef<Boolean> dry = NRef.of();
                cmdLine.matcher()
                        .when("--content", "content").asEntry(a -> content.set(a.stringValue()))
                        .when("--dry").asFlag(a -> dry.set(a.booleanValue()))
                        .whenNonOption().asArg(a -> content.set(a.image()))
                        .requireAll();
                if (content.get() == null) {
                    task.throwError(NMsg.ofC("missing file content"));
                    return;
                }
                String result = FileToolHelper.fileWrite(task, filePath.toString(),
                        content.get(), dry.get()
                );
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                context.task().addHistory(NaruMessage.user(NMsg.ofC("file_write   path=%s  content=%s\n%s",
                        filePath, FileToolHelper.snippet(content.get()), result
                ).toString()));
            }
        });
        register(new AbstractSubCommand("append", NText.ofPlain("append file content"),
                new SubCommandHelp("<path> [--content=<content>] [--dry]", "append file content")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NArg filePath = cmdLine.next().orNull();
                if (filePath == null || filePath.isOption()) {
                    task.throwError(NMsg.ofC("missing file path"));
                    return;
                }
                NRef<Long> from = NRef.of();
                NRef<Long> to = NRef.of();
                NRef<String> content = NRef.of();
                NRef<Boolean> dry = NRef.of();
                cmdLine.matcher()
                        .when("--from", "from").asEntry(a -> from.set(a.longValue()))
                        .when("--to", "to").asEntry(a -> to.set(a.longValue()))
                        .when("--content", "content").asEntry(a -> content.set(a.stringValue()))
                        .when("--dry").asFlag(a -> dry.set(a.booleanValue()))
                        .requireAll();
                String result = FileToolHelper.fileEdit(task, filePath.toString(),
                        from.get(),
                        to.get(),
                        content.get(),
                        dry.get()
                );
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                context.task().addHistory(NaruMessage.user(NMsg.ofC("file_append   path=%s  content=...\n%s",
                        filePath, FileToolHelper.snippet(content.get()), result
                ).toString()));
            }
        });
        register(new AbstractSubCommand("edit", NText.ofPlain("edit file content"),
                new SubCommandHelp("<path> [--from=<from>] [--to=<to>] --content=<content>", "edit file content to replace a portion of lines with anew content to remove or update that part")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NArg filePath = cmdLine.next().orNull();
                if (filePath == null || filePath.isOption()) {
                    task.throwError(NMsg.ofC("missing file path"));
                    return;
                }
                NRef<Long> from = NRef.of();
                NRef<Long> to = NRef.of();
                NRef<String> content = NRef.of();
                NRef<Boolean> dry = NRef.of();
                cmdLine.matcher()
                        .when("--from", "from").asEntry(a -> from.set(a.longValue()))
                        .when("--to", "to").asEntry(a -> to.set(a.longValue()))
                        .when("--content", "content").asEntry(a -> content.set(a.stringValue()))
                        .when("--dry").asFlag(a -> dry.set(a.booleanValue()))
                        .requireAll();

                String result = FileToolHelper.fileEdit(task,
                        filePath.toString(),
                        from.get(),
                        to.get(),
                        content.get(),
                        dry.get()
                );
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                context.task().addHistory(NaruMessage.user(NMsg.ofC("file_edit   path=%s  from=%s  to=%s  dry=%s  content=%s\n%s",
                        filePath, from.get(), to.get(), dry.get(),
                        FileToolHelper.snippet(content.get()), result
                ).toString()));
            }
        });
        register(new AbstractSubCommand("grep", NText.ofPlain("search content within a file"),
                new SubCommandHelp("<path> [--pattern=<pattern>] [--regex] [--context-lines=<n>] [--case-sensitive] [--max-matches=<n>] [--save=<var>]", "search content within a file to match pattern; publishes lastExitCode (0=found,1=no match,2=error) and stores the result text in the task var given by --save")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NArg filePath = cmdLine.next().orNull();
                if (filePath == null || filePath.isOption()) {
                    task.throwError(NMsg.ofC("missing file path"));
                    return;
                }
                NRef<Integer> contextLines = NRef.of();
                NRef<Integer> maxMatches = NRef.of();
                NRef<String> pattern = NRef.of();
                NRef<Boolean> regex = NRef.of();
                NRef<Boolean> caseSensitive = NRef.of();
                NRef<String> saveVar = NRef.of();
                cmdLine.matcher()
                        .when("--context-lines", "context-lines").asEntry(a -> contextLines.set(a.intValue()))
                        .when("--max-matches", "max").asEntry(a -> maxMatches.set(a.intValue()))
                        .when("--pattern", "pattern").asEntry(a -> pattern.set(a.stringValue()))
                        .when("--regex", "-e").asFlag(a -> regex.set(a.booleanValue()))
                        .when("--case-sensitive").asFlag(a -> caseSensitive.set(a.booleanValue()))
                        .when("--save").asEntry(a -> saveVar.set(a.stringValue()))
                        .when("-i").asFlag(a -> caseSensitive.set(!a.booleanValue()))
                        .requireAll();

                String result = FileToolHelper.fileGrep(task,
                        filePath.toString(),
                        pattern.get(),
                        regex.get(),
                        caseSensitive.get(),
                        contextLines.get(),
                        maxMatches.get()
                );
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                context.task().addHistory(NaruMessage.user(NMsg.ofC("file_grep   path=%s  context-lines=%s  max-matches=%s  pattern=%s  regex=%s case-sensitive=%s\n%s",
                        filePath, contextLines.get(), maxMatches.get(), pattern.get(), regex.get(),
                        caseSensitive.get(), result
                ).toString()));
                publishFileResult(task, saveVar.get(), result,
                        result != null && result.startsWith("ERROR") ? 2
                                : result != null && result.startsWith("No matches") ? 1 : 0);
            }
        });
        register(new AbstractSubCommand("find", NText.ofPlain("search for files in a directory"),
                new SubCommandHelp("<path> [--pattern=<pattern>] [--regex|-e] [--case-sensitive|-!i] [--context-lines=<n>] ", "search for files in a directory by content and show context-lines around matches"),
                new SubCommandHelp("<path> [--include=<file_name_pattern>] [--exclude=<file_name_pattern>]", "search for files in a directory by file name glob"),
                new SubCommandHelp("<path> [--max-matches=<n>] [--max-files=<n>] [--recursive|-r]", "search for files in a directory using limits and recursing behaviour"),
                new SubCommandHelp("<path> [--save=<var>] [--dir=<var>]", "also publish lastExitCode (0=found,1=none,2=error), store the first match into the task var --save and its parent directory into --dir"),
                new SubCommandHelp("<path> [--before=<d>] [--after=<d>]", "search for files in a directory using file modification date")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NArg filePath = cmdLine.next().orNull();
                if (filePath == null || filePath.isOption()) {
                    task.throwError(NMsg.ofC("missing file path"));
                    return;
                }
                NRef<Integer> contextLines = NRef.of();
                NRef<Integer> maxMatches = NRef.of();
                NRef<Integer> maxFiles = NRef.of();
                NRef<String> pattern = NRef.of();
                NRef<String> before = NRef.of();
                NRef<String> after = NRef.of();
                NRef<String> includeGlob = NRef.of();
                NRef<String> excludeGlob = NRef.of();
                NRef<String> saveVar = NRef.of();
                NRef<String> dirVar = NRef.of();
                NRef<Boolean> regex = NRef.of();
                NRef<Boolean> recursive = NRef.of();
                NRef<Boolean> caseSensitive = NRef.of();
                cmdLine.matcher()
                        .when("--context-lines", "context-lines").asEntry(a -> contextLines.set(a.intValue()))
                        .when("--max-matches", "max").asEntry(a -> maxMatches.set(a.intValue()))
                        .when("--max-files", "max").asEntry(a -> maxFiles.set(a.intValue()))
                        .when("--pattern", "pattern").asEntry(a -> pattern.set(a.stringValue()))
                        .when("--regex", "-e").asFlag(a -> regex.set(a.booleanValue()))
                        .when("--case-sensitive").asFlag(a -> caseSensitive.set(a.booleanValue()))
                        .when("--recursive","-r").asFlag(a -> recursive.set(a.booleanValue()))
                        .when("--include").asEntry(a -> includeGlob.set(a.stringValue()))
                        .when("--exclude").asEntry(a -> excludeGlob.set(a.stringValue()))
                        .when("--save").asEntry(a -> saveVar.set(a.stringValue()))
                        .when("--dir").asEntry(a -> dirVar.set(a.stringValue()))
                        .when("--before").asEntry(a -> includeGlob.set(a.stringValue()))
                        .when("--after").asEntry(a -> excludeGlob.set(a.stringValue()))
                        .when("-i").asFlag(a -> caseSensitive.set(!a.booleanValue()))
                        .requireAll();

                String result = FileToolHelper.folderFind(task,
                        filePath.toString(),
                        pattern.get(),
                        regex.get(),
                        caseSensitive.get(),
                        contextLines.get(),
                        maxMatches.get(),
                        maxFiles.get(),
                        recursive.get(),
                        includeGlob.get(),
                        excludeGlob.get(),
                        before.get(),
                        after.get()
                );
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                context.task().addHistory(NaruMessage.user(NMsg.ofC("file_find   path=%s  context-lines=%s  max-matches=%s  pattern=%s  regex=%s case-sensitive=%s\n" +
                                "maxMatches=%s maxFiles=%s recursive=%s includeGlob=%s excludeGlob=%s before=%s after=%s\n",
                                "%s",
                        filePath, contextLines.get(), maxMatches.get(), pattern.get(), regex.get(),caseSensitive.get(),
                        maxMatches.get() ,maxFiles.get(),recursive.get(),includeGlob.get(),excludeGlob.get(),before.get(),after.get()
                        ,result
                ).toString()));
                // scriptable outcome: lastExitCode (0=found,1=none,2=error);
                // --save stores the first match, --dir stores its parent directory
                // (relative to the searched root), both usable with {{var}} later.
                int exitCode = result != null && result.startsWith("ERROR") ? 2
                        : result != null && result.startsWith("No files found") ? 1 : 0;
                task.setTaskEnv("lastExitCode", exitCode);
                if (exitCode == 0 && (saveVar.isSet() || dirVar.isSet())) {
                    String first = FileToolHelper.folderFindFirst(task,
                            filePath.toString(), includeGlob.get(), recursive.get());
                    if (saveVar.isSet()) {
                        task.setTaskEnv(saveVar.get(), first == null ? "" : first);
                    }
                    if (dirVar.isSet()) {
                        String dir = ".";
                        if (first != null && !first.isEmpty()) {
                            int idx = first.lastIndexOf('/');
                            if (idx >= 0) {
                                dir = first.substring(0, idx);
                            }
                        }
                        task.setTaskEnv(dirVar.get(), dir);
                    }
                }
            }
        });
    }

    /**
     * Publish the scriptable outcome of a {@code /file} subcommand:
     * {@code lastExitCode} (0 = ok / found / matched, 1 = empty / none,
     * 2 = error) so script control-flow can branch on it, and — when requested
     * through {@code --save=<var>} — the trimmed result text into a task env
     * var so a later step can interpolate it ({@code {{var}}}) or compare it
     * ({@code /if x == "..."}).
     */
    private static void publishFileResult(NaruTask task, String saveVar, String result, int exitCode) {
        task.setTaskEnv("lastExitCode", exitCode);
        if (saveVar != null && !saveVar.isEmpty()) {
            task.setTaskEnv(saveVar, result == null ? "" : result.trim());
        }
    }
}
