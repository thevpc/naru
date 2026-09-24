package net.thevpc.naru.api.registry;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.*;
import net.thevpc.nuts.expr.NExprContext;
import net.thevpc.nuts.expr.NExprContextBuilder;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NBlankable;
import net.thevpc.nuts.util.NIllegalArgumentException;
import net.thevpc.nuts.util.NOptional;

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
    public void execute(NaruDirectiveCallContext context) {
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
        if (resolved != argument) {
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
        if (subCommands.size() == 1) {
            SubCommand s1 = (SubCommand) (subCommands.values().toArray()[0]);
            if (s1.name().isEmpty()) {
                String arg = context.argument() == null ? "" : context.argument().trim();
                if (arg.length() == 0 || arg.equals("help") || arg.equals("--help")) {
                    executeHelp(context, NCmdLine.of(""));
                    return;
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
                s1.execute(context, cmdLine);
                return;
            }
        }
        NCmdLine cmdLine;
        try {
            cmdLine = NCmdLine.parse(context.argument()).get();
        } catch (Exception e) {
            // e.g. unsupported quoting or stray characters in model-generated
            // arguments; report it instead of crashing the whole directive.
            task.log(NaruLogMode.AGENT_RESPONSE,
                    NMsg.ofC("invalid /%s syntax: %s", name(), context.argument()));
            return;
        }
        if (cmdLine.isEmpty()) {
            if (!NBlankable.isBlank(noCommand() != null)) {
                subCommand(noCommand()).get().execute(context, cmdLine);
            } else {
                SubCommand emptyCommand = subCommand("").orNull();
                if (emptyCommand != null) {
                    emptyCommand.execute(context, cmdLine);
                } else {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        } else {
            NArg a = cmdLine.peek().get();
            SubCommand s = subCommand(a.image()).orNull();
            if (s != null) {
                cmdLine.next().get();
                s.execute(context, cmdLine);
                return;
            }
            if (a.image().equals("help") || a.image().equals("--help")) {
                cmdLine.next().get();
                executeHelp(context, cmdLine);
            } else {
                s = subCommand("").orNull();
                if (s != null) {
                    s.execute(context, cmdLine);
                    return;
                }
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
            }
        }
    }

    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NMsg prefix = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1(name()));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s%s%s", prefix,NMsg.ofStyledSeparator("["),NMsg.ofStyledPale("options..."),NMsg.ofStyledSeparator("]")));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("    %s", description));
        for (SubCommand value : subCommands.values().stream().sorted(Comparator.comparing(x -> x.name())).collect(Collectors.toList())) {
            value.help(context);
        }
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("  %s %s %s %s %s %s", prefix
                ,NMsg.ofStyledSeparator("[")
                ,NMsg.ofStyledPrimary4("help")
                ,NMsg.ofStyledSeparator("|")
                ,NMsg.ofStyledPrimary4("--help")
                ,NMsg.ofStyledSeparator("]")
        ));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show %s help",name()));
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
            NExprContext ctx = NExprContextBuilder.of()
                    .declareBuiltins()
                    .declareMathConstants()
                    .declareMathFunctions()
                    .declarePhysicsConstants()
                    .declareVars(task.varResolver())
                    .build();
            String out = ctx.ofTemplate().withMoustacheStyle().compile(arg).runString();
            return out == null ? arg : out;
        } catch (Exception ignore) {
            return arg;
        }
    }

    @Override
    public List<NArgCompleteCandidate> resolveCandidates(
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
            if (stringArray.length > 0) {
                SubCommand s = subCommand(stringArray[0]).orNull();
                if (s != null) {
                    return s.resolveCandidates(cmdLine, pos, session);
                }
            }
        }
        return candidates;
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
        public void help(NaruDirectiveCallContext context) {
            if(helps.isEmpty()){
                helpOne(context, NText.ofPlain(""), this.description);
            }
            for (SubCommandHelp help : helps) {
                helpOne(context, help.syntax, help.description);
            }
        }

        public void helpOne(NaruDirectiveCallContext context, NText syntax, NText description) {
            NMsg prefix = NMsg.ofC("  %s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1(NaruDirectiveBase.this.name()));
            if (name.equals(noCommand)) {
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", prefix));
            }
            context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s %s", prefix, NMsg.ofStyledPrimary4(name()), syntax));
            for (NText line : description.splitLines()) {
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           %s", line));
            }
        }

        @Override
        public List<NArgCompleteCandidate> resolveCandidates(NCmdLine cmdLine, NArgCompletePosition pos, NaruSession session) {
            return new ArrayList();
        }
    }

    public interface SubCommand {
        String name();

        NText description();

        void execute(NaruDirectiveCallContext context, NCmdLine cmdLine);

        void help(NaruDirectiveCallContext context);

        List<NArgCompleteCandidate> resolveCandidates(NCmdLine cmdLine, NArgCompletePosition pos, NaruSession session);
    }

}
