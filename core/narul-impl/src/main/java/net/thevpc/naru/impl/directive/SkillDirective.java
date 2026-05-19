package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.skills.NaruSkill;
import net.thevpc.naru.api.skills.NaruSkillManager;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.cmd.NAruTerminalFormatter;
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
        super("skill", "list, available, show, load and unload skills", "skills");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
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
                    sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command %s", a.image()));
                }
            }
        }
    }

    public void executeList(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        List<NaruResourceInfo> naruResourceInfos = sessionContext.listSkills();
        naruResourceInfos.sort(Comparator.comparing(x -> x.getModificationDate(), Comparator.reverseOrder()));
        int index = 1;
        if (naruResourceInfos.isEmpty()) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("No skills loaded (%s available)", sessionContext.skillManager().available().size()));
            return;
        }
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s skills loaded (%s available)", naruResourceInfos.size(),
                sessionContext.skillManager().available().size()));
        for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %s %s", index,
                    NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                    NMsg.ofStyledPrimary1(naruResourceInfo.getUuid()),
                    NMsg.ofStyledString(naruResourceInfo.getName()))
            );
            index++;
        }
    }

    public void executeAvailable(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        List<NaruResourceInfo> naruResourceInfos = sessionContext.skillManager().available();
        naruResourceInfos.sort(Comparator.comparing(x -> x.getModificationDate(), Comparator.reverseOrder()));
        int index = 1;
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s skills available", naruResourceInfos.size()));
        for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
            sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %s %s", index,
                    NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                    NMsg.ofStyledPrimary1(naruResourceInfo.getUuid()),
                    NMsg.ofStyledString(naruResourceInfo.getName()))
            );
            index++;
        }
    }

    public void executeShow(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();

        String name = cmdLine.next().map(x->x.image()).orElse("");
        if (name.isEmpty()) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing skill name : %s", name));
            return;
        }
        NaruSkillManager sm = sessionContext.skillManager();
        NaruSkill cs = sm.findSkill(name);
        if (cs == null) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("skill not found : %s", name).asError());
            return;
        }

        String context2 = cs.getLines().stream().collect(Collectors.joining("\n"));
        List<NText> linesOk = NAruTerminalFormatter.formatMarkdown(context2, null).splitLines();
        Set<Integer> toShow = NaruUtils.parseLineIndicesToShow(linesOk.size(),cmdLine);
        NaruUtils.showItems(linesOk, toShow, sessionContext);
    }




    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1("skill"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, kk);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s list", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list loaded skills"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s available", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           list available skills"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s show <n1>-<p1>,<n2>-<p2>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show intervals"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s load <name>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           load skill (or default 'main')"));

        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s unload <name>", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           unload skill"));


        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }

    public void executeLoad(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        String name = cmdLine.next().map(x->x.image()).orElse("");
        if (name.isEmpty()) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing skill name : %s", name));
            return;
        }
        if (sessionContext.loadSkill(name)) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded skill : %s", name));
            sessionContext.addHistory(NaruMessage.user(NMsg.ofC("Loaded skill : %s", name).toString()));
        } else {
            if (sessionContext.findSkill(name) == null) {
                context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("skill not found : %s", name).asError());
                sessionContext.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name).toString()));
            }
        }
    }

    public void executeUnload(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        String name = cmdLine.next().map(x->x.image()).orElse("");
        if (name.isEmpty()) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing skill name : %s", name));
            return;
        }
        if (sessionContext.unloadSkill(name)) {
            context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Unloaded skill : %s", name));
            sessionContext.addHistory(NaruMessage.user(NMsg.ofC("Unloaded skill : %s", name).toString()));
        } else {
            if (sessionContext.findSkill(name) == null) {
                context.session().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("skill not found : %s", name).asError());
                sessionContext.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name).toString()));
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
            addCandidates(candidates, currentArg, "available", "list", "load", "unload","show", "help");
        }
        return candidates;
    }
}
