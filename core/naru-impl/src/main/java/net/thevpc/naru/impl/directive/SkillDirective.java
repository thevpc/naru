package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.skills.NaruSkill;
import net.thevpc.naru.api.skills.NaruSkillManager;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.cmd.NaruTerminalFormatter;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;

import java.util.*;
import java.util.stream.Collectors;

public class SkillDirective extends AbstractDirective {
    public SkillDirective() {
        super("skills", "context", "manage skills", "skill");
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
                case "available": {
                    executeAvailable(context, cmdLine);
                    break;
                }
                case "load": {
                    executeLoad(context, cmdLine);
                    break;
                }
                case "unload": {
                    executeUnload(context, cmdLine);
                    break;
                }
                case "--help":
                case "help": {
                    executeHelp(context, cmdLine);
                    break;
                }
                default: {
                    session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }
    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        List<NaruResourceInfo> naruResourceInfos = session.listSkills();
        naruResourceInfos.sort(Comparator.comparing(x -> x.getModificationDate(), Comparator.reverseOrder()));
        int index = 1;
        if (naruResourceInfos.isEmpty()) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("No skills loaded (%s available)", session.skillManager().available().size()));
            return;
        }
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s skills loaded (%s available)", naruResourceInfos.size(),
                session.skillManager().available().size()));
        for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %s %s", index,
                    NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                    NMsg.ofStyledPrimary1(naruResourceInfo.getUuid()),
                    NMsg.ofStyledString(naruResourceInfo.getName()))
            );
            index++;
        }
    }

    public void executeAvailable(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        List<NaruResourceInfo> naruResourceInfos = session.skillManager().available();
        naruResourceInfos.sort(Comparator.comparing(x -> x.getModificationDate(), Comparator.reverseOrder()));
        int index = 1;
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s skills available", naruResourceInfos.size()));
        for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
            session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %s %s", index,
                    NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                    NMsg.ofStyledPrimary1(naruResourceInfo.getUuid()),
                    NMsg.ofStyledString(naruResourceInfo.getName()))
            );
            index++;
        }
    }

    public void executeShow(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();

        String name = cmdLine.next().map(x -> x.image()).orElse("");
        if (name.isEmpty()) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing skill name : %s", name));
            return;
        }
        NaruSkillManager sm = session.skillManager();
        NaruSkill cs = sm.findSkill(name);
        if (cs == null) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("skill not found : %s", name).asError());
            return;
        }

        String context2 = cs.getLines().stream().collect(Collectors.joining("\n"));
        List<NaruUtils.LineRange> lineRanges = NaruUtils.parseRanges(cmdLine);
        NaruUtils.showItemsWithFormat(context2, "markdown", lineRanges, session);
    }


    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary5(name()));
        session.log(NaruLogMode.AGENT_RESPONSE, kk);
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("list")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list loaded skills"));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("available")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list available skills"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <n1>-<p1>,<n2>-<p2>", kk, NMsg.ofStyledPrimary4("show")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show intervals"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <name>", kk, NMsg.ofStyledPrimary4("load")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           load skill (or default 'main')"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s <name>", kk, NMsg.ofStyledPrimary4("unload")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           unload skill"));


        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }

    public void executeLoad(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        String name = cmdLine.next().map(x -> x.image()).orElse("");
        if (name.isEmpty()) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing skill name : %s", name));
            return;
        }
        if (session.loadSkill(name)) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded skill : %s", name));
            session.addHistory(NaruMessage.user(NMsg.ofC("Loaded skill : %s", name).toString()));
        } else {
            if (session.findSkill(name) == null) {
                context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("skill not found : %s", name).asError());
                session.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name).toString()));
            }
        }
    }

    public void executeUnload(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        String name = cmdLine.next().map(x -> x.image()).orElse("");
        if (name.isEmpty()) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing skill name : %s", name));
            return;
        }
        if (session.unloadSkill(name)) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Unloaded skill : %s", name));
            session.addHistory(NaruMessage.user(NMsg.ofC("Unloaded skill : %s", name).toString()));
        } else {
            if (session.findSkill(name) == null) {
                context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("skill not found : %s", name).asError());
                session.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name).toString()));
            }
        }


        context.session().log(NaruLogMode.PROGRESS, NMsg.ofC("Unloaded skill context. Back to main."));
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
            addCandidates(candidates, currentArg, "available", "list", "load", "unload", "show", "help");
        }
        return candidates;
    }
}
