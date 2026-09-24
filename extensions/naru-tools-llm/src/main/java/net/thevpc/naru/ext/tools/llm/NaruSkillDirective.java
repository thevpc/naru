package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.skills.NaruSkill;
import net.thevpc.naru.api.skills.NaruSkillManager;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NStringBuilder;

import java.util.*;
import java.util.stream.Collectors;

public class NaruSkillDirective extends NaruDirectiveBase {
    public NaruSkillDirective() {
        super("skills", "ai", "manage AI skills", "skill");
        noCommand("list");
        register(new AbstractSubCommand("list", NText.ofPlain("list loaded skills")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                List<NaruResourceInfo> naruResourceInfos = task.skills();
                naruResourceInfos.sort(Comparator.comparing(x -> x.getModificationInstant(), Comparator.reverseOrder()));
                int index = 1;
                NStringBuilder sb = NStringBuilder.of();
                if (naruResourceInfos.isEmpty()) {
                    NMsg msg = NMsg.ofC("No skills loaded (%s available)", task.session().skillManager().available().size());
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    sb.println(msg.toString());
                    return NaruStmtResult.ofSuccess(sb.toString());
                }
                NMsg msg = NMsg.ofC("%s skills loaded (%s available)", naruResourceInfos.size(),
                        task.session().skillManager().available().size());
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                sb.println(msg.toString());
                for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
                    NMsg row = NMsg.ofC("[%s] %s %s %s", index,
                            NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                            NMsg.ofStyledPrimary1(naruResourceInfo.getUuid()),
                            NMsg.ofStyledString(naruResourceInfo.getName())
                    );
                    task.log(NaruLogMode.AGENT_RESPONSE, row);
                    sb.println(row.toString());
                    index++;
                }
                return NaruStmtResult.ofSuccess(sb.toString());
            }
        });
        register(new AbstractSubCommand("show", NText.ofPlain("list skill content"),
                new SubCommandHelp("<name> [<n1>-<n2>]", "show skill named <name> content wile listing only the selected files (or all if no filter)")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();

                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing skill name : %s", name);
                    context.task().log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruSkillManager sm = task.session().skillManager();
                NaruSkill cs = sm.findSkill(name);
                if (cs == null) {
                    NMsg msg = NMsg.ofC("skill not found : %s", name).asError();
                    context.task().log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }

                String context2 = cs.getLines().stream().collect(Collectors.joining("\n"));
                List<NaruUtils.LineRange> lineRanges = NaruUtils.parseRanges(cmdLine);
                NaruUtils.showItemsWithFormat(context2, "markdown", lineRanges, task);
                return NaruStmtResult.ofSuccess(null);
            }
        });
        register(new AbstractSubCommand("available", NText.ofPlain("list available skills")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                List<NaruResourceInfo> naruResourceInfos = task.session().skillManager().available();
                naruResourceInfos.sort(Comparator.comparing(x -> x.getModificationInstant(), Comparator.reverseOrder()));
                int index = 1;
                NStringBuilder sb = NStringBuilder.of();
                NMsg msg = NMsg.ofC("%s skills available", naruResourceInfos.size());
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                sb.println(msg.toString());
                for (NaruResourceInfo naruResourceInfo : naruResourceInfos) {
                    NMsg row = NMsg.ofC("[%s] %s %s %s", index,
                            NMsg.ofStyledKeyword(naruResourceInfo.getMode().name().toLowerCase()),
                            NMsg.ofStyledPrimary1(naruResourceInfo.getUuid()),
                            NMsg.ofStyledString(naruResourceInfo.getName())
                    );
                    task.log(NaruLogMode.AGENT_RESPONSE, row);
                    sb.println(row.toString());
                    index++;
                }
                return NaruStmtResult.ofSuccess(sb.toString());
            }
        });
        register(new AbstractSubCommand("load", NText.ofPlain("load skill by name"),
                new SubCommandHelp("<name>", "load skill named <name>")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing skill name : %s", name);
                    context.task().log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                if (task.loadSkill(name)) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded skill : %s", name));
                    task.addHistory(NaruMessage.user(NMsg.ofC("Loaded skill : %s", name).toString()));
                } else {
                    if (task.session().skillManager().findSkill(name) == null) {
                        NMsg msg = NMsg.ofC("skill not found : %s", name).asError();
                        context.task().log(NaruLogMode.AGENT_RESPONSE, msg);
                        task.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name).toString()));
                        return NaruStmtResult.ofError(msg.toString());
                    }
                }
                return NaruStmtResult.ofSuccess(null);
            }
        });
        register(new AbstractSubCommand("unload", NText.ofPlain("unload skill by name"),
                new SubCommandHelp("<name>", "unload skill named <name>")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing skill name : %s", name);
                    context.task().log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                if (task.unloadSkill(name)) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Unloaded skill : %s", name));
                    task.addHistory(NaruMessage.user(NMsg.ofC("Unloaded skill : %s", name).toString()));
                } else {
                    if (task.session().skillManager().findSkill(name) == null) {
                        NMsg msg = NMsg.ofC("skill not found : %s", name).asError();
                        context.task().log(NaruLogMode.AGENT_RESPONSE, msg);
                        task.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name).toString()));
                        return NaruStmtResult.ofError(msg.toString());
                    }
                }
                context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("Unloaded skill context. Back to main."));
                return NaruStmtResult.ofSuccess(null);
            }
        });
    }
}