package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.util.NaruExprs;
import net.thevpc.nuts.cmdline.*;
import net.thevpc.nuts.elem.NElement;
import net.thevpc.nuts.expr.NExprContext;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.util.*;

import java.util.*;
import java.util.stream.Collectors;

public abstract class NaruDirectiveBase implements NaruDirective {
    private final String name;
    private final String group;
    private final String description;
    private final String[] aliases;
    private final Map<String, SubCommand> subCommands = new HashMap<>();
    private String noCommand;

    public NaruDirectiveBase(String name, String group, String description, String... aliases) {
        this.name = name;
        this.group = group;
        this.description = description;
        this.aliases = aliases;
    }

    public String noCommand() {
        return noCommand;
    }

    public NaruDirectiveBase noCommand(String noCommand) {
        this.noCommand = noCommand;
        return this;
    }

    protected void register(SubCommand sc) {
        SubCommand o = subCommands.get(sc.name());
        if (o != null) {
            throw new NIllegalArgumentException(NMsg.ofC("duplicate subcommand " + sc.name()));
        }
        subCommands.put(sc.name(), sc);
    }

    protected NOptional<SubCommand> subCommand(String name) {
        return NOptional.ofNamed(subCommands.get(name), NMsg.ofC("subcommand %s", name));
    }

    /**
     * Every registered subcommand, for a subclass' own {@link #resolveCandidates}
     * to complete subcommand names (with descriptions) before delegating the
     * deeper words to the subcommands themselves.
     */
    protected Collection<SubCommand> registeredSubCommands() {
        return subCommands.values();
    }

    @Override
    public String group() {
        return group;
    }

    @Override
    public String[] getAliases() {
        return aliases;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    protected void addCandidates(List<NArgCompleteCandidate> candidates, String prefix, String... options) {
        for (String option : options) {
            if (option.startsWith(prefix)) {
                candidates.add(NArgCompleteCandidate.of(option));
            }
        }
    }

    @Override
    public NaruStmtResult execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        // Resolve {{var}} moustache templates in the directive argument against the
        // task's variables (frame locals -> task env -> session env) BEFORE any
        // cmdline parsing, so naru scripts can feed the result of one step into the
        // next, e.g. "/system mvn -o -q -f {{pom}} test" or "/file read {{target}}".
        // The moustache syntax deliberately avoids clashing with shell '$' variables
        // inside /system commands. Undefined variables / malformed templates are left
        // untouched so a stray "{{" never breaks a script.
        String argument = context.argument();
        String resolved = resolveTemplates(task, argument);
        if (!Objects.equals(resolved, argument)) {
            final NaruDirectiveCallContext base = context;
            final String resolvedArgument = resolved;
            context = new NaruDirectiveCallContext() {
                @Override
                public String name() {
                    return base.name();
                }

                @Override
                public String argument() {
                    return resolvedArgument;
                }

                @Override
                public NaruTask task() {
                    return base.task();
                }

            };
        }
        SubCommandContext sctx = new AbstractSubCommandContext(context);
        if (subCommands.size() == 1) {
            SubCommand s1 = (SubCommand) (subCommands.values().toArray()[0]);
            if (s1.name().isEmpty()) {
                String arg = context.argument() == null ? "" : context.argument().trim();
                if (/*arg.length() == 0 || */arg.equals("help") || arg.equals("--help")) {
                    NMsg u = executeHelp0(context, NCmdLine.of(""));
                    return prepareExecuteResult(NaruStmtResult.ofSuccess(NElement.of(u.toString()),u), sctx, task);
                }
                NCmdLine cmdLine = NCmdLine.of("");
                try {
                    cmdLine = NCmdLine.parse(context.argument()).get();
                } catch (Exception ignore) {
                    // the argument is raw text (e.g. /sh or /system shell commands
                    // with quotes/operators) that is NOT well-formed NCmdLine; such
                    // directives read context.argument() directly, so an empty
                    // cmdLine is fine and must not fail the whole call.
                }
                return prepareExecuteResult(s1.execute(sctx, cmdLine), sctx, task);
            }
        }
        NCmdLine cmdLine;
        try {
            cmdLine = NCmdLine.parse(context.argument()).get();
        } catch (Exception e) {
            // e.g. unsupported quoting or stray characters in model-generated
            // arguments; report it instead of crashing the whole directive.
            return prepareExecuteResult(NaruStmtResult.ofError(NMsg.ofC("invalid /%s syntax: %s", name(), context.argument())), sctx, task);
        }
        if (cmdLine.isEmpty()) {
            if (!NBlankable.isBlank(noCommand() != null)) {
                NOptional<SubCommand> cmd = subCommand(noCommand());
                if (!cmd.isPresent()) {
                    return prepareExecuteResult(NaruStmtResult.ofError(NMsg.ofC("command not found %s %s", name(), noCommand())), sctx, task);
                }
                return prepareExecuteResult(cmd.get().execute(sctx, cmdLine), sctx, task);
            } else {
                SubCommand emptyCommand = subCommand("").orNull();
                if (emptyCommand != null) {
                    return prepareExecuteResult(emptyCommand.execute(sctx, cmdLine), sctx, task);
                } else {
                    return prepareExecuteResult(NaruStmtResult.ofError(NMsg.ofC("invalid command /%s %s", name(), context.argument())), sctx, task);
                }
            }
        } else {
            NArg a = cmdLine.peek().get();
            SubCommand s = subCommand(a.image()).orNull();
            if (s != null) {
                cmdLine.next().get();
                return prepareExecuteResult(s.execute(sctx, cmdLine), sctx, task);
            }
            if (a.image().equals("help") || a.image().equals("--help")) {
                cmdLine.next().get();
                NMsg u = executeHelp0(context, cmdLine);
                task.addHistory(NaruMessage.directive(u));
                return NaruStmtResult.ofSuccess(NElement.of(u.toString()));
            } else {
                s = subCommand("").orNull();
                if (s != null) {
                    return prepareExecuteResult(s.execute(sctx, cmdLine), sctx, task);
                }
                return prepareExecuteResult(NaruStmtResult.ofError(NMsg.ofC("invalid command /%s %s", name(), context.argument())), sctx, task);
            }
        }
    }

    private NaruStmtResult prepareExecuteResult(NaruStmtResult sub, SubCommandContext sctx, NaruTask task) {
        NTextBuilder other = sctx.out();
        NMsg msg = NMsg.ofC("%s", other.build());
        task.addHistory(NaruMessage.directive(msg));
        return sub;
    }

    private NMsg executeHelp0(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NMsg prefix = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1(name()));
        NTextBuilder sb = NTextBuilder.of();
        sb.append(NMsg.ofC("%s %s%s%s", prefix, NMsg.ofStyledSeparator("["), NMsg.ofStyledPale("options..."), NMsg.ofStyledSeparator("]")));
        sb.append("\n");
        sb.append(NMsg.ofC("    %s", description));
        for (SubCommand value : subCommands.values().stream().sorted(Comparator.comparing(x -> x.name())).collect(Collectors.toList())) {
            NMsg z = value.help(context);
            sb.append("\n");
            sb.append(z);
        }
        sb.append("\n");
        sb.append(NMsg.ofC("  %s %s %s %s %s %s", prefix
                , NMsg.ofStyledSeparator("[")
                , NMsg.ofStyledPrimary4("help")
                , NMsg.ofStyledSeparator("|")
                , NMsg.ofStyledPrimary4("--help")
                , NMsg.ofStyledSeparator("]")
        ));
        sb.append("\n");
        sb.append(NMsg.ofC("           show %s help", name()));
        return NMsg.ofNtf(sb.build());
    }

    /**
     * Resolve {@code {{var}}} moustache templates in {@code arg} against the
     * task's variables (frame locals -&gt; task env -&gt; session env). The original
     * argument is returned unchanged when nothing needs resolving or when a
     * variable is unknown, so a stray "{{" never breaks a script or a shell
     * command.
     */
    private static String resolveTemplates(NaruTask task, String arg) {
        if (arg == null || arg.indexOf("{{") < 0) {
            return arg;
        }
        try {
            NExprContext ctx = NaruExprs.runtimeBuilder(task.varResolver()).build();
            String out = ctx.ofTemplate().withMoustacheStyle().compile(arg).runString();
            return out == null ? arg : out;
        } catch (Exception ignore) {
            return arg;
        }
    }

    @Override
    public NArgCompleteResult resolveCandidates(
            NCmdLine cmdLine,
            NArgCompletePosition pos,
            NaruSession session) {
        List<NArgCompleteCandidate> candidates = new java.util.ArrayList<>();
        String[] stringArray = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        String currentArg = wordIndex < stringArray.length ? stringArray[wordIndex] : "";
        if (wordIndex == 1) {
            addCandidates(candidates, currentArg, subCommands.keySet().stream().sorted().toArray(String[]::new));
        } else if (wordIndex >= 2) {
            // word 0 is the directive itself ("/session"), word 1 is the sub-command
            // the user picked. Looking word 0 up here found no sub-command at all,
            // which is why sub-command completion (and its arguments) never appeared.
            if (stringArray.length > 1) {
                SubCommand s = subCommand(stringArray[1]).orNull();
                if (s == null) {
                    s = subCommand("").orNull();
                }
                if (s != null) {
                    return s.resolveCandidates(cmdLine, pos, session);
                }
            }
        }
        return NArgCompleteResult.ofCandidates(candidates);
    }

    public class SubCommandHelp {
        private final NText syntax;
        private final NText description;

        public SubCommandHelp(String syntax, String description) {
            this.syntax = NText.ofPlain(syntax);
            this.description = NText.ofPlain(description);
        }

        public SubCommandHelp(NText syntax, NText description) {
            this.syntax = syntax;
            this.description = description;
        }
    }

    public abstract class AbstractSubCommand implements SubCommand {
        private final String name;
        private final NText description;
        private final List<SubCommandHelp> helps = new ArrayList<>();

        public AbstractSubCommand(SubCommandHelp... all) {
            this("", NText.ofPlain(NaruDirectiveBase.this.description), all);
        }

        public AbstractSubCommand(String name, NText description, SubCommandHelp... all) {
            this.name = name;
            this.description = description;
            this.helps.addAll(Arrays.asList(all));
        }

        public NText description() {
            return description;
        }

        public String name() {
            return name;
        }

        @Override
        public NMsg help(NaruDirectiveCallContext context) {
            if (helps.isEmpty()) {
                return helpOne(context, NText.ofPlain(""), this.description);
            }
            NTextBuilder sb = NTextBuilder.of();
            for (SubCommandHelp help : helps) {
                NMsg r = helpOne(context, help.syntax, help.description);
                sb.append(r.toString());
                sb.append("\n");
            }
            return NMsg.ofNtf(sb.build());
        }

        public NMsg helpOne(NaruDirectiveCallContext context, NText syntax, NText description) {
            NMsg prefix = NMsg.ofC("  %s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1(NaruDirectiveBase.this.name()));
            NTextBuilder sb = NTextBuilder.of();
            if (name.equals(noCommand)) {
                NMsg msg = NMsg.ofC("%s", prefix);
                sb.append(msg).newLine();
            }
            NMsg msg = NMsg.ofC("%s %s %s", prefix, NMsg.ofStyledPrimary4(name()), syntax);
            sb.append(msg).newLine();
            for (NText line : description.splitLines()) {
                msg = NMsg.ofC("           %s", line);
                sb.append(msg).newLine();
            }
            return NMsg.ofC(sb.toString());
        }

        @Override
        public NArgCompleteResult resolveCandidates(NCmdLine cmdLine, NArgCompletePosition pos, NaruSession session) {
            return NArgCompleteResult.ofBlank();
        }
    }

    public interface SubCommandContext {
        String name();

        String argument();

        NaruTask task();

        NTextBuilder out();

        NTextBuilder errln(String m);
        NTextBuilder errln(NMsg m);
        NTextBuilder outln(NMsg m);
        NTextBuilder outln(String m);

    }

    public class AbstractSubCommandContext implements SubCommandContext {
        private final NaruDirectiveCallContext context;
        NTextBuilder out;

        public AbstractSubCommandContext(NaruDirectiveCallContext context) {
            this.context = context;
            this.out = NTextBuilder.of();
        }

        @Override
        public String name() {
            return context.name();
        }

        @Override
        public String argument() {
            return context.argument();
        }

        @Override
        public NaruTask task() {
            return context.task();
        }

        @Override
        public NTextBuilder out() {
            return out;
        }

        @Override
        public NTextBuilder errln(NMsg m) {
            return out.println(m.asError());
        }

        @Override
        public NTextBuilder outln(NMsg m) {
            return out.println(m);
        }

        @Override
        public NTextBuilder outln(String m) {
            return out.println(m);
        }

        @Override
        public NTextBuilder errln(String m) {
            return out.println(NMsg.ofP(m).asError());
        }
    }

    public interface SubCommand {
        String name();

        NText description();

        NaruStmtResult execute(SubCommandContext context, NCmdLine cmdLine);

        NMsg help(NaruDirectiveCallContext context);

        NArgCompleteResult resolveCandidates(NCmdLine cmdLine, NArgCompletePosition pos, NaruSession session);
    }

}
