package net.thevpc.naru.impl.registry.builtindirectives.ai;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.mode.NaruPromptMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NNameFormat;
import net.thevpc.nuts.util.NOptional;

import java.util.*;
import java.util.stream.Collectors;

public class NaruModeDirective extends AbstractDirective {
    public NaruModeDirective() {
        super("mode", "ai", "manage AI modes", "modes");
        noCommand("list");
        register(new AbstractSubCommand("list", NText.ofPlain("list loaded modes"),
                new SubCommandHelp("","list available modes."
                        +"\ndefault modes include :"
                        +"\n  ask"
                        +"\n  planning"
                        +"\n  implement"
                        +"\n  audit"
                        +"\n  debug"
                        +"\n  debug"
                )
                ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeList(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("current", NText.ofPlain("show active mode")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeCurrent(context, cmdLine);
            }
        });
        register(new AbstractSubCommand("set", NText.ofPlain("set active mode")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                executeSet(context, cmdLine);
            }

            @Override
            public List<NArgCandidate> resolveCandidates(NCmdLine cmdLine, NCmdLineAutoCompleteResolver.Pos pos, NaruSession session) {
                List<NArgCandidate> candidates = new ArrayList<>();
                String[] stringArray = cmdLine.toStringArray();
                int wordIndex = pos.wordIndex();
                if (wordIndex == 2) {
                    String currentArg = wordIndex < stringArray.length ? stringArray[wordIndex] : "";
                    if (cmdLine.get(1).isPresent() && cmdLine.get(1).get().image().equals("set")) {
                        if (pos.inWordCursor() == 0) {
                            addCandidates(candidates, currentArg, session.registry().modeNames().toArray(new String[0]));
                        } else {
                            addCandidates(candidates, currentArg, session.registry().modeNamesAndAliases().toArray(new String[0]));
                        }
                    }
                }
                return candidates;
            }
        });
        register(new AbstractSubCommand("", NText.ofPlain("change active mode by name"),
                new SubCommandHelp("<name>","change active mode by name.")
                ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                for (NArg a : cmdLine) {
                    NOptional<NaruPromptMode> mode = task.session().registry().mode(a.image());
                    if (mode.isPresent()) {
                        if (mode.get().equals(task.promptMode())) {
                            task.promptMode(mode.get());
                            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Changed mode : %s", a.image()));
                            task.addHistory(NaruMessage.user(NMsg.ofC("Changed mode : %s", a.image())));
                        }
                        return;
                    }
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                    return;
                }
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
            }
        });
    }


    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        List<NaruPromptMode> modes = task.session().registry().modes().stream().sorted(Comparator.comparing(NaruPromptMode::name)).collect(Collectors.toList());

        for (NaruPromptMode m : modes) {
            NTextBuilder b = NTextBuilder.of();
            if (m.aliases().length > 0) {
                TreeSet<String> aliases = new TreeSet<>();
                Collections.addAll(aliases, m.aliases());
                b.append(" ");
                b.append("(", NTextStyle.separator());
                b.appendJoined(NMsg.ofStyledSeparator(", "),
                        aliases.stream().map(NMsg::ofStyledPrimary3).collect(Collectors.toList())
                );
                b.append(")", NTextStyle.separator());
            }
            task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s",
                    NMsg.ofStyledKeyword(NNameFormat.LOWER_KEBAB_CASE.format(m.name())),
                    b
            ));
        }
    }

    public void executeCurrent(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask session = context.task();
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("current mode : %s", session.promptMode()).asError());
    }


    public void executeSet(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask session = context.task();
        String name = cmdLine.next().map(x -> x.image()).orElse("");
        if (name.isEmpty()) {
            context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing mode : %s", name));
            return;
        }
        NOptional<NaruPromptMode> m = session.session().registry().mode(name);
        if (!m.isEmpty()) {
            if (!m.get().equals(session.promptMode())) {
                session.promptMode(m.get());
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Changed mode : %s", name));
                session.addHistory(NaruMessage.user(NMsg.ofC("Changed mode : %s", name)));
            }
        } else {
            context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("not found mode : %s", name));
        }
    }



}
