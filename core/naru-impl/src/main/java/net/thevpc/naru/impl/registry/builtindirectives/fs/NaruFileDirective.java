package net.thevpc.naru.impl.registry.builtindirectives.fs;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.naru.impl.util.ToolHelper;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NRef;

public class NaruFileDirective extends AbstractDirective {
    public NaruFileDirective() {
        super("file", "fs", "manipulate a single file");
        register(new AbstractSubCommand("read", NText.ofPlain("read file content"),
                new SubCommandHelp("<path> [--from=<from>] [--to=<to>]", "read file content")
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
                cmdLine.matcher()
                        .with("--from", "from").matchEntry(a -> from.set(a.longValue()))
                        .with("--to", "to").matchEntry(a -> to.set(a.longValue()))
                        .requireAll();
                String result = ToolHelper.fileRead(task, filePath.toString(),
                        from.get(), to.get()
                );
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                context.task().addHistory(NaruMessage.user(NMsg.ofC("file_read   path=%s  from=%s to=%s\n%s",
                        filePath, from, to, result
                ).toString()));
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
                        .with("--content", "content").matchEntry(a -> content.set(a.image()))
                        .with("--dry").matchFlag(a -> dry.set(a.booleanValue()))
                        .withNonOption().matchAny(a -> content.set(a.image()))
                        .requireAll();
                if (content.get() == null) {
                    task.throwError(NMsg.ofC("missing file content"));
                    return;
                }
                String result = ToolHelper.fileWrite(task, filePath.toString(),
                        content.get(), dry.get()
                );
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                context.task().addHistory(NaruMessage.user(NMsg.ofC("file_write   path=%s  content=%s\n%s",
                        filePath, NaruUtils.snippet(content.get()), result
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
                        .with("--from", "from").matchEntry(a -> from.set(a.longValue()))
                        .with("--to", "to").matchEntry(a -> to.set(a.longValue()))
                        .with("--content", "content").matchEntry(a -> content.set(a.image()))
                        .with("--dry").matchFlag(a -> dry.set(a.booleanValue()))
                        .requireAll();
                String result = ToolHelper.fileEdit(task, filePath.toString(),
                        from.get(),
                        to.get(),
                        content.get(),
                        dry.get()
                );
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                context.task().addHistory(NaruMessage.user(NMsg.ofC("file_append   path=%s  content=...\n%s",
                        filePath, NaruUtils.snippet(content.get()), result
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
                        .with("--from", "from").matchEntry(a -> from.set(a.longValue()))
                        .with("--to", "to").matchEntry(a -> to.set(a.longValue()))
                        .with("--content", "content").matchEntry(a -> content.set(a.image()))
                        .with("--dry").matchFlag(a -> dry.set(a.booleanValue()))
                        .requireAll();

                String result = ToolHelper.fileEdit(task,
                        filePath.toString(),
                        from.get(),
                        to.get(),
                        content.get(),
                        dry.get()
                );
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
                context.task().addHistory(NaruMessage.user(NMsg.ofC("file_edit   path=%s  from=%s  to=%s  dry=%s  content=%s\n%s",
                        filePath, from.get(), to.get(), dry.get(),
                        NaruUtils.snippet(content.get()), result
                ).toString()));
            }
        });
        register(new AbstractSubCommand("grep", NText.ofPlain("search content within a file"),
                new SubCommandHelp("<path> [--pattern=<pattern>] [--regex] [--context-lines=<n>] [--case-sensitive] [--max-matches=<n>]", "search content within a file to match pattern")
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
                cmdLine.matcher()
                        .with("--context-lines", "context-lines").matchEntry(a -> contextLines.set(a.intValue()))
                        .with("--max-matches", "max").matchEntry(a -> maxMatches.set(a.intValue()))
                        .with("--pattern", "pattern").matchEntry(a -> pattern.set(a.image()))
                        .with("--regex", "-e").matchFlag(a -> regex.set(a.booleanValue()))
                        .with("--case-sensitive").matchFlag(a -> regex.set(a.booleanValue()))
                        .with("-i").matchFlag(a -> caseSensitive.set(!a.booleanValue()))
                        .requireAll();

                String result = ToolHelper.fileGrep(task,
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
            }
        });
        register(new AbstractSubCommand("find", NText.ofPlain("search for files in a directory"),
                new SubCommandHelp("<path> [--pattern=<pattern>] [--regex|-e] [--case-sensitive|-!i] [--context-lines=<n>] ", "search for files in a directory by content and show context-lines around matches"),
                new SubCommandHelp("<path> [--include=<file_name_pattern>] [--exclude=<file_name_pattern>]", "search for files in a directory by file name glob"),
                new SubCommandHelp("<path> [--max-matches=<n>] [--max-files=<n>] [--recursive|-r]", "search for files in a directory using limits and recursing behaviour"),
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
                NRef<Boolean> regex = NRef.of();
                NRef<Boolean> recursive = NRef.of();
                NRef<Boolean> caseSensitive = NRef.of();
                cmdLine.matcher()
                        .with("--context-lines", "context-lines").matchEntry(a -> contextLines.set(a.intValue()))
                        .with("--max-matches", "max").matchEntry(a -> maxMatches.set(a.intValue()))
                        .with("--max-files", "max").matchEntry(a -> maxFiles.set(a.intValue()))
                        .with("--pattern", "pattern").matchEntry(a -> pattern.set(a.image()))
                        .with("--regex", "-e").matchFlag(a -> regex.set(a.booleanValue()))
                        .with("--case-sensitive").matchFlag(a -> regex.set(a.booleanValue()))
                        .with("--recursive","-r").matchFlag(a -> recursive.set(a.booleanValue()))
                        .with("--include").matchEntry(a -> includeGlob.set(a.stringValue()))
                        .with("--exclude").matchEntry(a -> excludeGlob.set(a.stringValue()))
                        .with("--before").matchEntry(a -> includeGlob.set(a.stringValue()))
                        .with("--after").matchEntry(a -> excludeGlob.set(a.stringValue()))
                        .with("-i").matchFlag(a -> caseSensitive.set(!a.booleanValue()))
                        .requireAll();

                String result = ToolHelper.folderFind(task,
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
            }
        });
    }
}
