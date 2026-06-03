package net.thevpc.naru.impl.registry.builtindirectives.ai;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.skills.NaruSkill;
import net.thevpc.naru.api.skills.NaruSkillManager;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;

import java.util.*;
import java.util.stream.Collectors;

public class NaruSkillDirective extends AbstractDirective {
    public NaruSkillDirective() {
        super("skills", "ai", "manage AI skills", "skill");
        noCommand("list");
        register(new AbstractSubCommand("list", NText.ofPlain("list loaded skills")) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                List<NaruResourceInfo> naruResourceInfos = task.skills();
                naruResourceInfos.sort(Comparator.comparing(x -> x.getModificationDate(), Comparator.reverseOrder()));
                int index = 1;
                if (naruResourceInfos.isEmpty()) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("No skills loaded (%s available)", task.session().skillManager().available().size()));
                    return;
                }
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s skills loaded (%s available)", naruResourceInfos.size(),
                        task.session().skillManager().available().size()));
                for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %s %s", index,
                            NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                            NMsg.ofStyledPrimary1(naruResourceInfo.getUuid()),
                            NMsg.ofStyledString(naruResourceInfo.getName()))
                    );
                    index++;
                }
            }
        });
        register(new AbstractSubCommand("show", NText.ofPlain("list skill content"),
                new SubCommandHelp("<name> [<n1>-<n2>]", "show skill named <name> content wile listing only the selected files (or all if no filter)")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();

                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing skill name : %s", name));
                    return;
                }
                NaruSkillManager sm = task.session().skillManager();
                NaruSkill cs = sm.findSkill(name);
                if (cs == null) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("skill not found : %s", name).asError());
                    return;
                }

                String context2 = cs.getLines().stream().collect(Collectors.joining("\n"));
                List<NaruUtils.LineRange> lineRanges = NaruUtils.parseRanges(cmdLine);
                NaruUtils.showItemsWithFormat(context2, "markdown", lineRanges, task);
            }
        });
        register(new AbstractSubCommand("available", NText.ofPlain("list available skills")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                List<NaruResourceInfo> naruResourceInfos = task.session().skillManager().available();
                naruResourceInfos.sort(Comparator.comparing(x -> x.getModificationDate(), Comparator.reverseOrder()));
                int index = 1;
                task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s skills available", naruResourceInfos.size()));
                for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("[%s] %s %s %s", index,
                            NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                            NMsg.ofStyledPrimary1(naruResourceInfo.getUuid()),
                            NMsg.ofStyledString(naruResourceInfo.getName()))
                    );
                    index++;
                }
            }
        });
        register(new AbstractSubCommand("load", NText.ofPlain("load skill by name"),
                new SubCommandHelp("<name>", "load skill named <name>")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing skill name : %s", name));
                    return;
                }
                if (task.loadSkill(name)) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded skill : %s", name));
                    task.addHistory(NaruMessage.user(NMsg.ofC("Loaded skill : %s", name).toString()));
                } else {
                    if (task.session().skillManager().findSkill(name) == null) {
                        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("skill not found : %s", name).asError());
                        task.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name).toString()));
                    }
                }
            }
        });
        register(new AbstractSubCommand("unload", NText.ofPlain("unload skill by name"),
                new SubCommandHelp("<name>", "unload skill named <name>")
        ) {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("missing skill name : %s", name));
                    return;
                }
                if (task.unloadSkill(name)) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Unloaded skill : %s", name));
                    task.addHistory(NaruMessage.user(NMsg.ofC("Unloaded skill : %s", name).toString()));
                } else {
                    if (task.session().skillManager().findSkill(name) == null) {
                        context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("skill not found : %s", name).asError());
                        task.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name).toString()));
                    }
                }
                context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("Unloaded skill context. Back to main."));
            }
        });
    }
}
