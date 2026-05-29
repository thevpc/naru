package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.mode.NaruMode;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
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

public class ModeDirective extends AbstractDirective {
    public ModeDirective() {
        super("mode", "context", "manage modes", "modes");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession session = context.session();
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
                    NOptional<NaruMode> mode = session.registry().mode(a.image());
                    if (mode.isPresent()) {
                        if (mode.get().equals(session.mode())) {
                            session.mode(mode.get());
                            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Changed mode : %s", a.image()));
                            session.addHistory(NaruMessage.user(NMsg.ofC("Changed mode : %s", a.image())));
                        }
                        return;
                    }
                    session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }
    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        List<NaruMode> modes = session.registry().modes().stream().sorted(Comparator.comparing(NaruMode::name)).collect(Collectors.toList());

        for (NaruMode m : modes) {
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
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s",
                    NMsg.ofStyledKeyword(NNameFormat.LOWER_KEBAB_CASE.format(m.name())),
                    b
            ));
        }
    }

    public void executeShow(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("current mode : %s", session.mode()).asError());
    }


    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
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
        NaruSession session = context.session();
        String name = cmdLine.next().map(x -> x.image()).orElse("");
        if (name.isEmpty()) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing mode : %s", name));
            return;
        }
        NOptional<NaruMode> m = session.registry().mode(name);
        if (!m.isEmpty()) {
            if (m.get().equals(session.mode())) {
                session.mode(m.get());
                context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Changed mode : %s", name));
                session.addHistory(NaruMessage.user(NMsg.ofC("Changed mode : %s", name)));
            }
        } else {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("not found mode : %s", name));
        }
        if (session.loadSkill(name)) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded skill : %s", name));
//            session.addHistory(NaruMessage.user(NMsg.ofC("Loaded skill : %s", name)));
        } else {
            if (session.findSkill(name) == null) {
                context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("skill not found : %s", name).asError());
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
