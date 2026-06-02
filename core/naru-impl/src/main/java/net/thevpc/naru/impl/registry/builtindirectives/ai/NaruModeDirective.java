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
import net.thevpc.nuts.text.NTextBuilder;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NNameFormat;
import net.thevpc.nuts.util.NOptional;

import java.util.*;
import java.util.stream.Collectors;

public class NaruModeDirective extends AbstractDirective {
    public NaruModeDirective() {
        super("mode", "ai", "manage AI modes", "modes");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeList(context, cmdLine);
        } else {
            NArg a = cmdLine.next().get();
            switch (a.image()) {
                case "show": {
                    executeShow(context, cmdLine);
                    break;
                }
                case "list": {
                    executeList(context, cmdLine);
                    break;
                }
                case "set": {
                    executeSet(context, cmdLine);
                    break;
                }
                case "--help":
                case "help": {
                    executeHelp(context, cmdLine);
                    break;
                }
                default: {
                    NOptional<NaruPromptMode> mode = task.session().registry().mode(a.image());
                    if (mode.isPresent()) {
                        if (mode.get().equals(task.promptMode())) {
                            task.promptMode(mode.get());
                            context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Changed mode : %s", a.image()));
                            task.addHistory(NaruMessage.user(NMsg.ofC("Changed mode : %s", a.image())));
                        }
                        return;
                    }
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }
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

    public void executeShow(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask session = context.task();
        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("current mode : %s", session.promptMode()).asError());
    }


    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask session = context.task();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary5(name()));
        session.log(NaruLogMode.AGENT_RESPONSE, kk);
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("list")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list modes"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <n1>-<p1>,<n2>-<p2>", kk, NMsg.ofStyledPrimary4("show")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show current"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <name>", kk, NMsg.ofStyledPrimary4("set")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           change mode"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

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
            if (m.get().equals(session.promptMode())) {
                session.promptMode(m.get());
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Changed mode : %s", name));
                session.addHistory(NaruMessage.user(NMsg.ofC("Changed mode : %s", name)));
            }
        } else {
            context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("not found mode : %s", name));
        }
        if (session.loadSkill(name)) {
            context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded skill : %s", name));
//            session.addHistory(NaruMessage.user(NMsg.ofC("Loaded skill : %s", name)));
        } else {
            if (session.session().skillManager().findSkill(name) == null) {
                context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("skill not found : %s", name).asError());
                session.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name)));
            }
        }
    }


    @Override
    public List<NArgCandidate> resolveCandidates(
            NCmdLine cmdLine,
            NCmdLineAutoCompleteResolver.Pos pos,
            NaruSession session) {
        List<NArgCandidate> candidates = new ArrayList<>();
        String[] stringArray = cmdLine.toStringArray();
        int wordIndex = pos.wordIndex();
        String currentArg = wordIndex < stringArray.length ? stringArray[wordIndex] : "";
        if (wordIndex == 1) {
            Set<String> all = new HashSet<>(Arrays.asList("set", "show", "list", "help", "--help"));
            all.addAll(session.registry().modeNames());
            addCandidates(candidates, currentArg, all.toArray(new String[0]));
        } else if (wordIndex == 2) {
                if (cmdLine.get(1).isPresent() && cmdLine.get(1).get().image().equals("set")) {
                if(pos.inWordCursor()==0) {
                    addCandidates(candidates, currentArg, session.registry().modeNames().toArray(new String[0]));
                }else{
                    addCandidates(candidates, currentArg, session.registry().modeNamesAndAliases().toArray(new String[0]));
                }
            }
        }
        return candidates;
    }
}
