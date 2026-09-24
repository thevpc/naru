package net.thevpc.naru.ext.tools.llm;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruDirectiveBase;
import net.thevpc.naru.api.routine.NaruStmtResult;
import net.thevpc.naru.api.util.NaruUtils;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NText;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NStringBuilder;

import java.util.*;

public class NaruContextDirective extends NaruDirectiveBase {
    public NaruContextDirective() {
        super("context", "ai", "show AI context");
        noCommand("all");
        register(new AbstractSubCommand("all", NText.ofPlain("show AI context"),
                new SubCommandHelp("[<n1>-<n2>]", "show AI full context history (all sources).\nwhen providing lines filter, only selected lines are shown from each source. when no filter, all lines are displayed.\nex:" +
                        "\n /context all -2..-1" +
                        "\n show last two lines of all sources" +
                        "\n /context all 1-2" +
                        "\n show first two lines of all sources" +
                        "\n /context all 1-2,4,-1" +
                        "\n show first two lines, 4th line and last line of all sources"
                )
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeShow(NaruSource.values(), context, cmdLine);
            }
        });
        register(new AbstractSubCommand("agents", NText.ofPlain("show AI agent context"),
                new SubCommandHelp("[<n1>-<n2>]", "show AI context agents selected lines. this includes user-home, classpath, project and folder naru files.\nfor each only selected lines are shown")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeShow(new NaruSource[]{
                        NaruSource.USER_HOME,
                        NaruSource.CLASSPATH,
                        NaruSource.PROJECT,
                        NaruSource.FOLDER,
                }, context, cmdLine);
            }
        });
        register(new AbstractSubCommand("system", NText.ofPlain("show AI system context"),
                new SubCommandHelp("[<n1>-<n2>]", "show AI system context selected lines")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeShow(new NaruSource[]{
                        NaruSource.SYSTEM,
                }, context, cmdLine);
            }
        });
        register(new AbstractSubCommand("skills", NText.ofPlain("show AI skills context"),
                new SubCommandHelp("[<n1>-<n2>]", "show AI context skills selected lines")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeShow(new NaruSource[]{NaruSource.SKILL}, context, cmdLine);
            }
        });
        register(new AbstractSubCommand("user", NText.ofPlain("show AI user-home context"),
                new SubCommandHelp("[<n1>-<n2>]", "show AI context user-home selected lines")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeShow(new NaruSource[]{NaruSource.USER_HOME}, context, cmdLine);
            }
        });
        register(new AbstractSubCommand("classpath", NText.ofPlain("show AI classpath context"),
                new SubCommandHelp("[<n1>-<n2>]", "show AI context classpath selected lines")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeShow(new NaruSource[]{NaruSource.CLASSPATH}, context, cmdLine);
            }
        });
        register(new AbstractSubCommand("project", NText.ofPlain("show AI project context"),
                new SubCommandHelp("[<n1>-<n2>]", "show AI context project selected lines")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeShow(new NaruSource[]{NaruSource.PROJECT}, context, cmdLine);
            }
        });
        register(new AbstractSubCommand("folder", NText.ofPlain("show AI folder context"),
                new SubCommandHelp("[<n1>-<n2>]", "show AI context folder selected lines")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeShow(new NaruSource[]{NaruSource.FOLDER}, context, cmdLine);
            }
        });
        register(new AbstractSubCommand("files", NText.ofPlain("show AI agent files (not content lines)")
        ) {
            @Override
            public NaruStmtResult execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                return executeShowSources(NaruSource.values(), context, cmdLine);
            }
        });
    }


    public NaruStmtResult executeShowSources(NaruSource[] sources, NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NStringBuilder sb = NStringBuilder.of();
        task.context(sources).messages().stream().forEach(a -> {
            if (a.getSource() == NaruSource.SYSTEM) {
                if (a.getSourceName().contains("/") || a.getSourceName().contains("\\")) {
                    NMsg msg = NMsg.ofC("%s %-9s %s %s %s %s",
                            NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                            NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                            NMsg.ofStyled(":", agentStyle(a.getRole())),
                            a.getSource().id(),
                            NMsg.ofStyledPath(a.getSourceName()),
                            NMsg.ofStyledPale(NaruUtils.snippet(a.getContent()))
                    );
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    sb.println(msg.toString());
                }
            } else if (a.getSource() == NaruSource.MODE) {
                if (a.getSourceName().contains("/") || a.getSourceName().contains("\\")) {
                    NMsg msg = NMsg.ofC("%s %-9s [%s] %s %s %s",
                            NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                            NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                            NMsg.ofStyled(a.getSource().id(), agentStyle(a.getRole())),
                            NMsg.ofStyled(":", agentStyle(a.getRole())),
                            NMsg.ofStyledPath(a.getSourceName()),
                            NMsg.ofStyledPale(NaruUtils.snippet(a.getContent()))
                    );
                    task.log(NaruLogMode.AGENT_RESPONSE, msg);
                    sb.println(msg.toString());
                }
            } else {
                NMsg msg = NMsg.ofC("%s %-9s  [%s] %s %s %s",
                        NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getSource().id(), agentStyle(a.getRole())),
                        NMsg.ofStyled(":", agentStyle(a.getRole())),
                        NMsg.ofStyledPath(a.getSourceName()),
                        NMsg.ofStyledPale(NaruUtils.snippet(a.getContent()))
                );
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                sb.println(msg.toString());
            }
        });
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    public NaruStmtResult executeShow(NaruSource[] sources, NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        List<NaruUtils.LineRange> lineRanges = NaruUtils.parseRanges(cmdLine);
        NStringBuilder sb = NStringBuilder.of();
        task.context(sources).messages().stream().forEach(a -> {
            if (a.getSource() == NaruSource.SYSTEM) {
                NMsg msg = NMsg.ofC("%s %-9s %s",
                        NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                        NMsg.ofStyled(":", agentStyle(a.getRole()))
                );
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                sb.println(msg.toString());
                String content = a.getContent();
                NaruUtils.showItemsWithFormat(content, "markdown", lineRanges, task);
            } else {
                NMsg msg = NMsg.ofC("%s %-9s  [%s] %s %s",
                        NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getSource().id(), agentStyle(a.getRole())),
                        NMsg.ofStyled(":", agentStyle(a.getRole())),
                        a.getSourceName()
                );
                task.log(NaruLogMode.AGENT_RESPONSE, msg);
                sb.println(msg.toString());
                String content = a.getContent();
                NaruUtils.showItemsWithFormat(content, "markdown", lineRanges, task);
            }
        });
        return NaruStmtResult.ofSuccess(sb.toString());
    }

    private NTextStyle agentStyle(NaruRole a) {
        switch (a) {
            case assistant:
                return NTextStyle.primary2();
            case user:
                return NTextStyle.primary3();
            case system:
                return NTextStyle.primary4();
            case tool:
                return NTextStyle.primary5();
        }
        return null;
    }

    private String agentIcon(NaruRole a) {
        switch (a) {
            case assistant:
                return "\uD83E\uDDE0";
            case user:
                return "\uD83D\uDE4D";
            case system:
                return "\uD83D\uDE80";
            case tool:
                return "🔧";
        }
        return null;
    }

}