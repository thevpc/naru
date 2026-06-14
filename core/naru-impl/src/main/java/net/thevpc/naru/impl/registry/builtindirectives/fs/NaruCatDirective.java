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
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NBooleanRef;
import net.thevpc.nuts.util.NRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

public class NaruCatDirective extends AbstractDirective {
    public NaruCatDirective() {
        super("cat","fs", "show file content with optional syntax highlighting");
        register(new AbstractSubCommand(
                new SubCommandHelp("-n --line-numbers", "show file number"),
                new SubCommandHelp("-l=<lang> --lang=<lang>", "syntax highlighting "),
                new SubCommandHelp("-L", "syntax highlighting with inferred language")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();

                NBooleanRef optLineNumbers = NBooleanRef.of(false); // -n
                NRef<String> optLang = NRef.of(null);           // -l <lang>
                List<String> targets = new ArrayList<>();

                NCmdLine args = NCmdLine.of(NBlankable.isBlank(context.argument())
                        ? new String[0]
                        : NCmdLine.parse(context.argument()).get().toStringArray());

                args.matcher()
                        .with("-n", "--line-numbers").matchTrueFlag(a -> optLineNumbers.set())
                        .with("-l", "--lang").matchEntry(a -> optLang.set(a.stringValue()))
                        .with("-L").matchFlag(a -> optLang.set("auto"))
                        .withNonOption().matchAny(a -> targets.add(a.image()))
                        .requireAll();

                NPath baseDir = task.workingDir();

                if (targets.isEmpty()) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofStyled("view: no file specified", NTextStyle.error()));
                    return;
                }

                NTextBuilder result = NTextBuilder.of();
                for (String t : targets) {
                    NPath p = NPath.of(t);
                    if (!p.isAbsolute()) p = baseDir.resolve(t);

                    if (!p.exists()) {
                        result.append(NMsg.ofC("view: cannot access '%s': No such file or directory\n", p),NTextStyle.danger());
                        continue;
                    }
                    if (p.isDirectory()) {
                        result.append(NMsg.ofC("view: '%s' is a directory\n", p),NTextStyle.danger());
                        continue;
                    }
                    if (targets.size() > 1) {
                        result.append(NMsg.ofC("==> %s <==\n", p.name()),NTextStyle.primary1());
                    }

                    String content = p.readString();
                    String lang = optLang.get() != null ? optLang.get() : inferLang(p.name());

                    if (lang != null) {
                        // syntax-colored block, optionally with line numbers
                        String body = optLineNumbers.get() ? withLineNumbers(content) : content;
                        result.append(NMsg.ofCode(lang, body));
                    } else {
                        // plain text — still optionally line-numbered
                        if (optLineNumbers.get()) {
                            result.append(withLineNumbersPlain(content));
                        } else {
                            result.append(content);
                        }
                    }
                    result.append("\n");
                }

                NText resultText = result.build();
                task.addHistory(NaruMessage.user(NMsg.ofC("call   : view %s", context.argument()).toString()));
                task.addHistory(NaruMessage.user(NMsg.ofC("result :\n%s", NaruUtils.stripAnsi(resultText.toString())).toString()));
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", resultText));
            }

            private String withLineNumbers(String content) {
                String[] lines = content.split("\n", -1);
                int width = String.valueOf(lines.length).length();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lines.length; i++) {
                    sb.append(String.format("%" + width + "d  %s\n", i + 1, lines[i]));
                }
                return sb.toString();
            }

            // plain variant returns NMsg so line numbers can be styled differently
            private NMsg withLineNumbersPlain(String content) {
                String[] lines = content.split("\n", -1);
                int width = String.valueOf(lines.length).length();
                NTextBuilder sb = NTextBuilder.of();
                for (int i = 0; i < lines.length; i++) {
                    sb.append(NMsg.ofStyledNumber(String.format("%" + width + "d", i + 1)));
                    sb.append(NMsg.ofC("  %s\n", lines[i]));
                }
                return NMsg.ofC("%s", sb.build());
            }

            private String inferLang(String name) {
                int dot = name.lastIndexOf('.');
                if (dot < 0) return null;
                switch (name.substring(dot + 1).toLowerCase()) {
                    case "java":                      return "java";
                    case "py":                        return "python";
                    case "js": case "mjs":            return "javascript";
                    case "ts":                        return "typescript";
                    case "c": case "h":               return "c";
                    case "cpp": case "hpp": case "cc":return "cpp";
                    case "rs":                        return "rust";
                    case "go":                        return "go";
                    case "rb":                        return "ruby";
                    case "sh": case "bash": case "zsh":return "bash";
                    case "sql":                       return "sql";
                    case "json":                      return "json";
                    case "xml": case "pom":           return "xml";
                    case "yaml": case "yml":          return "yaml";
                    case "toml":                      return "toml";
                    case "html": case "htm":          return "html";
                    case "css":                       return "css";
                    case "kt":                        return "kotlin";
                    case "scala":                     return "scala";
                    case "md":                        return "markdown";
                    case "properties": case "ini":    return "properties";
                    default:                          return null;
                }
            }
        });
    }
}
