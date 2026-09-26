package net.thevpc.naru.ext.skills;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruResourceInfo;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NStringBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code /skill} — manage the skills active for the current task.
 * <p>
 * Ships in the {@code naru-skills} jar alongside the extension that owns the state, so the
 * command disappears together with the feature.
 */
public class NaruSkillDirective extends NaruDirectiveBase {
    public NaruSkillDirective() {
        super("skills", "ai", "manage AI skills", "skill");
        noCommand("list");
        register(new AbstractSubCommand("list", NText.ofPlain("list loaded skills")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                NaruSkillsExtension ext = NaruSkillsExtension.skills(task.session());
                List<NaruResourceInfo> loaded = infosOf(ext.activeNames(task), ext);
                loaded.sort(Comparator.comparing(NaruResourceInfo::getName));
                int available = ext.skills().available().size();
                if (loaded.isEmpty()) {
                    NMsg msg = NMsg.ofC("No skills loaded (%s available)", available);
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofSuccess(msg + "");
                }
                NStringBuilder sb = NStringBuilder.of();
                NMsg msg = NMsg.ofC("%s skills loaded (%s available)", loaded.size(), available);
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                sb.println(msg.toString());
                int index = 1;
                for (NaruResourceInfo info : loaded) {
                    sb.println(row(index++, info));
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
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruSkill skill = NaruSkillsExtension.skills(task.session()).skills().findSkill(name);
                if (skill == null) {
                    return notFound(task, name);
                }
                String content = String.join("\n", skill.getLines());
                List<NaruUtils.LineRange> lineRanges = NaruUtils.parseRanges(cmdLine);
                NaruUtils.showItemsWithFormat(content, "markdown", lineRanges, task);
                return NaruStmtResult.ofSuccess(null);
            }
        });
        register(new AbstractSubCommand("available", NText.ofPlain("list available skills")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                List<NaruResourceInfo> infos = new ArrayList<>(
                        NaruSkillsExtension.skills(task.session()).skills().available());
                infos.sort(Comparator.comparing(NaruResourceInfo::getName));
                NStringBuilder sb = NStringBuilder.of();
                NMsg msg = NMsg.ofC("%s skills available", infos.size());
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                sb.println(msg.toString());
                int index = 1;
                for (NaruResourceInfo info : infos) {
                    sb.println(row(index++, info));
                }
                return NaruStmtResult.ofSuccess(sb.toString());
            }
        });
        register(new AbstractSubCommand("load", NText.ofPlain("load skill by name"),
                new SubCommandHelp("<name>", "load skill named <name>")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing skill name : %s", name);
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruSkillsExtension ext = NaruSkillsExtension.skills(task.session());
                if (ext.load(task, name)) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Loaded skill : %s", name));
                    task.addHistory(NaruMessage.user(NMsg.ofC("Loaded skill : %s", name).toString()));
                    return NaruStmtResult.ofSuccess(null);
                }
                if (!ext.exists(name)) {
                    return notFound(task, name);
                }
                // already active
                NMsg msg = NMsg.ofC("skill already loaded : %s", name);
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                return NaruStmtResult.ofSuccess(msg.toString());
            }
        });
        register(new AbstractSubCommand("unload", NText.ofPlain("unload skill by name"),
                new SubCommandHelp("<name>", "unload skill named <name>")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                String name = cmdLine.next().map(x -> x.image()).orElse("");
                if (name.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing skill name : %s", name);
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                NaruSkillsExtension ext = NaruSkillsExtension.skills(task.session());
                if (ext.unload(task, name)) {
                    context.task().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("Unloaded skill : %s", name));
                    task.addHistory(NaruMessage.user(NMsg.ofC("Unloaded skill : %s", name).toString()));
                    task.log(NaruLogMode.PROGRESS, NMsg.ofC("Unloaded skill context. Back to main."));
                    return NaruStmtResult.ofSuccess(null);
                }
                if (!ext.exists(name)) {
                    return notFound(task, name);
                }
                NMsg msg = NMsg.ofC("skill not loaded : %s", name);
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                return NaruStmtResult.ofSuccess(msg.toString());
            }
        });
    }

    private static NaruStmtResult notFound(NaruTask task, String name) {
        NMsg msg = NMsg.ofC("skill not found : %s", name).asError();
        task.log(NaruLogMode.AGENT_RESPONSE, msg);
        task.addHistory(NaruMessage.user(NMsg.ofC("Error : skill not found : %s", name).toString()));
        return NaruStmtResult.ofError(msg.toString());
    }

    private static String row(int index, NaruResourceInfo info) {
        NMsg row = NMsg.ofC("[%s] %s %s %s", index,
                NMsg.ofStyledKeyword(info.getMode().name().toLowerCase()),
                NMsg.ofStyledPrimary1(info.getUuid()),
                NMsg.ofStyledString(info.getName())
        );
        return row.toString();
    }

    private static List<NaruResourceInfo> infosOf(Set<String> names, NaruSkillsExtension ext) {
        return names.stream()
                .map(ext.skills()::findSkillInfo)
                .filter(x -> x != null)
                .collect(Collectors.toList());
    }
}
