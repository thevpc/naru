package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NTextStyle;

import java.util.*;

public class ContextDirective extends AbstractDirective {
    public ContextDirective() {
        super("context", "context", "show context");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeShow(NaruSource.values(),context, cmdLine);
        } else {
            NArg a = cmdLine.next().get();
            switch (a.image()) {
                case "agents": {
                    executeShow(new NaruSource[]{
                            NaruSource.USER_HOME,
                            NaruSource.CLASSPATH,
                            NaruSource.PROJECT,
                            NaruSource.FOLDER,
                    }, context, cmdLine);
                    break;
                }
                case "system": {
                    executeShow(new NaruSource[]{
                            NaruSource.SYSTEM,
                    }, context, cmdLine);
                    break;
                }
                case "skills": {
                    executeShow(new NaruSource[]{NaruSource.SKILL}, context, cmdLine);
                    break;
                }
                case "user": {
                    executeShow(new NaruSource[]{NaruSource.USER_HOME}, context, cmdLine);
                    break;
                }
                case "classpath": {
                    executeShow(new NaruSource[]{NaruSource.CLASSPATH}, context, cmdLine);
                    break;
                }
                case "project": {
                    executeShow(new NaruSource[]{NaruSource.PROJECT}, context, cmdLine);
                    break;
                }
                case "folder": {
                    executeShow(new NaruSource[]{NaruSource.FOLDER}, context, cmdLine);
                    break;
                }
                case "all": {
                    executeShow(NaruSource.values(),context, cmdLine);
                    break;
                }
                case "--help":
                case "help": {
                    executeHelp(context, cmdLine);
                    break;
                }
                default: {
                    sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }
    }

    public void executeShow(NaruSource[] sources,NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        List<NaruUtils.LineRange> lineRanges = NaruUtils.parseRanges(cmdLine);
        sessionContext.context(sources).stream().forEach(a -> {
            if(a.getSource()==NaruSource.SYSTEM){
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %-9s %s",
                        NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                        NMsg.ofStyled(":", agentStyle(a.getRole()))
                ));
            }else {
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %-9s  [%s] %s %s",
                        NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getSource().id(), agentStyle(a.getRole())),
                        NMsg.ofStyled(":", agentStyle(a.getRole())),
                        a.getSourceName()
                ));
            }
            String content = a.getContent();
            NaruUtils.showItemsWithFormat(content,"markdown",lineRanges, sessionContext);
        });
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


    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession sessionContext = context.session();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1(name()));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, kk);
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s classpath", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           classpath agents"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s user", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           user home agents"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s project", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           project dir agents"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s folder", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           agent files from working directory up to project root (excluded), (empty when working directory equals project root)"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s all", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           all of the above combined"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s system", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show system prompt"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s skills", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           active loaded skills content"));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));
    }


}
