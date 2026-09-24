package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.registry.NaruToolTag;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.util.NStringBuilder;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages which tool capabilities a task may use: {@code enable|add} opts the
 * task into a tool TAG (making the tools wearing that tag visible to the model)
 * and {@code disable|remove} puts a tool into the task's EXCLUSION set. This is
 * the script-friendly twin of {@code /tools add-tagged / exclude / ...}:
 *
 * <pre>
 * /tags enable fs            (alias: /tags add fs)
 * /tags disable cd web       (alias: /tags remove cd web)
 * /tags list                 (enabled tags + excluded tools)
 * /tags tags                 (all tags known to the registry)
 * </pre>
 */
public class NaruTagsDirective extends NaruDirectiveBase {

    public NaruTagsDirective() {
        super("tags", "ai", "enable/disable tool tags and tool exclusions", "tag");
        this.noCommand("list");

        register(new AbstractSubCommand("enable", NText.ofPlain("enable tools tagged with the given tags"),
                new SubCommandHelp("<tag-name>... [<tag-name>...]", "add the given tags to the task's enabled tag set")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                if (cmdLine.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing tag");
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                int count = 0;
                while (!cmdLine.isEmpty()) {
                    String tag = cmdLine.next().get().image();
                    task.addToolTag(tag);
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("tag %s enabled", NMsg.ofStyledPrimary1(tag)));
                    count++;
                }
                return NaruStmtResult.ofSuccess(count);
            }
        });
        register(new AbstractSubCommand("add", NText.ofPlain("alias of enable"),
                new SubCommandHelp("<tag-name>... [<tag-name>...]", "same as 'enable': add the given tags to the task's enabled tag set")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                if (cmdLine.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing tag");
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                int count = 0;
                while (!cmdLine.isEmpty()) {
                    String tag = cmdLine.next().get().image();
                    task.addToolTag(tag);
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("tag %s enabled", NMsg.ofStyledPrimary1(tag)));
                    count++;
                }
                return NaruStmtResult.ofSuccess(count);
            }
        });
        register(new AbstractSubCommand("disable", NText.ofPlain("exclude tools by name"),
                new SubCommandHelp("<tool-name>... [<tool-name>...]", "add the given tools to the task's exclusion set")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                if (cmdLine.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing tool");
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                int count = 0;
                while (!cmdLine.isEmpty()) {
                    String name = cmdLine.next().get().image();
                    task.addToolExclusion(name);
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("tool %s disabled", NMsg.ofStyledPrimary1(name)));
                    count++;
                }
                return NaruStmtResult.ofSuccess(count);
            }
        });
        register(new AbstractSubCommand("remove", NText.ofPlain("alias of disable"),
                new SubCommandHelp("<tool-name>... [<tool-name>...]", "same as 'disable': add the given tools to the task's exclusion set")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                if (cmdLine.isEmpty()) {
                    NMsg msg = NMsg.ofC("missing tool");
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    return NaruStmtResult.ofError(msg.toString());
                }
                int count = 0;
                while (!cmdLine.isEmpty()) {
                    String name = cmdLine.next().get().image();
                    task.addToolExclusion(name);
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("tool %s disabled", NMsg.ofStyledPrimary1(name)));
                    count++;
                }
                return NaruStmtResult.ofSuccess(count);
            }
        });
        register(new AbstractSubCommand("list", NText.ofPlain("list enabled tags and excluded tools")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                List<NaruToolTag> tags = task.findToolTags();
                NStringBuilder sb = NStringBuilder.of();
                NMsg msg = NMsg.ofC("%s enabled tags:", tags.size());
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                sb.println(msg.toString());
                for (NaruToolTag t : tags.stream().sorted((a, b) -> a.name().compareTo(b.name())).collect(Collectors.toList())) {
                    NMsg row = NMsg.ofC("  %s - %s",
                            NMsg.ofStyledPrimary1(t.name()), t.description());
                    task.log(NaruLogMode.AGENT_RESPONSE, row);
                    sb.println(row.toString());
                }
                List<String> excluded = task.findToolExclusions().stream().sorted().collect(Collectors.toList());
                NMsg msg2 = NMsg.ofC("%s excluded tools:", excluded.size());
                task.log(NaruLogMode.AGENT_RESPONSE, msg2);
                sb.println(msg2.toString());
                for (String e : excluded) {
                    NMsg row = NMsg.ofC("  %s", NMsg.ofStyledPrimary1(e));
                    task.log(NaruLogMode.AGENT_RESPONSE, row);
                    sb.println(row.toString());
                }
                return NaruStmtResult.ofSuccess(sb.toString());
            }
        });
        register(new AbstractSubCommand("tags", NText.ofPlain("list all available tags")) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                NaruTask task = context.task();
                Map<String, NaruToolTag> tags = task.session().registry().availableTags();
                NStringBuilder sb = NStringBuilder.of();
                NMsg msg = NMsg.ofC("%s available tags:", tags.size());
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                sb.println(msg.toString());
                for (Map.Entry<String, NaruToolTag> e : tags.entrySet().stream().sorted(Map.Entry.comparingByKey()).collect(Collectors.toList())) {
                    NMsg row = NMsg.ofC("  %s - %s",
                            NMsg.ofStyledPrimary1(e.getKey()), e.getValue().description());
                    task.log(NaruLogMode.AGENT_RESPONSE, row);
                    sb.println(row.toString());
                }
                return NaruStmtResult.ofSuccess(sb.toString());
            }
        });
    }
}