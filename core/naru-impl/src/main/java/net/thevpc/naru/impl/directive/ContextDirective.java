package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruRole;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruSource;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.text.NTextStyle;
import net.thevpc.nuts.util.NBlankable;

import java.util.*;

public class ContextDirective extends AbstractDirective {
    public ContextDirective() {
        super("context", "context", "show context");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession session = context.session();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeShow(NaruSource.values(), context, cmdLine);
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
                    executeShow(NaruSource.values(), context, cmdLine);
                    break;
                }
                case "files": {
                    executeShowSources(NaruSource.values(), context, cmdLine);
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

    public void executeShowSources(NaruSource[] sources, NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        session.context(sources).messages().stream().forEach(a -> {
            if (a.getSource() == NaruSource.SYSTEM) {
                if (a.getSourceName().contains("/") || a.getSourceName().contains("\\")) {
                    session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %-9s %s %s %s %s",
                            NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                            NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                            NMsg.ofStyled(":", agentStyle(a.getRole())),
                            a.getSource().id(),
                            NMsg.ofStyledPath(a.getSourceName()),
                            NMsg.ofStyledPale(snippet(a.getContent()))
                    ));
                }
            }else if (a.getSource() == NaruSource.MODE) {
                if (a.getSourceName().contains("/") || a.getSourceName().contains("\\")) {
                    session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %-9s [%s] %s %s %s",
                            NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                            NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                            NMsg.ofStyled(a.getSource().id(), agentStyle(a.getRole())),
                            NMsg.ofStyled(":", agentStyle(a.getRole())),
                            NMsg.ofStyledPath(a.getSourceName()),
                            NMsg.ofStyledPale(snippet(a.getContent()))
                    ));
                }
            } else {
                session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %-9s  [%s] %s %s %s",
                        NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getSource().id(), agentStyle(a.getRole())),
                        NMsg.ofStyled(":", agentStyle(a.getRole())),
                        NMsg.ofStyledPath(a.getSourceName()),
                        NMsg.ofStyledPale(snippet(a.getContent()))
                ));
            }
        });
    }

    private static String snippet(String content) {
        if (NBlankable.isBlank(content)) {
            return "";
        }
        String cc = content.substring(0, Math.min(content.length(), 40));
        return cc.replace("\r\n", " ").replace("\n", " ");
    }

    public void executeShow(NaruSource[] sources, NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        List<NaruUtils.LineRange> lineRanges = NaruUtils.parseRanges(cmdLine);
        session.context(sources).messages().stream().forEach(a -> {
            if (a.getSource() == NaruSource.SYSTEM) {
                session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %-9s %s",
                        NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                        NMsg.ofStyled(":", agentStyle(a.getRole()))
                ));
                String content = a.getContent();
                NaruUtils.showItemsWithFormat(content, "markdown", lineRanges, session);
            } else {
                session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %-9s  [%s] %s %s",
                        NMsg.ofStyled(agentIcon(a.getRole()), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getRole().name(), agentStyle(a.getRole())),
                        NMsg.ofStyled(a.getSource().id(), agentStyle(a.getRole())),
                        NMsg.ofStyled(":", agentStyle(a.getRole())),
                        a.getSourceName()
                ));
                String content = a.getContent();
                NaruUtils.showItemsWithFormat(content, "markdown", lineRanges, session);
            }
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
        NaruSession session = context.session();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary5(name()));
        session.log(NaruLogMode.AGENT_RESPONSE, kk);
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("agents")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           all agents"));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("classpath")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           classpath agents"));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("user")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           user home agents"));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("project")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           project dir agents"));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("folder")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           agent files from working directory up to project root (excluded), (empty when working directory equals project root)"));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("all")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           all of the above combined"));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("system")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show system prompt"));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("skills")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           active loaded skills content"));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s %s", kk, NMsg.ofStyledPrimary4("files")));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show loaded files"));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));
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
            Set<String> all = new HashSet<>(Arrays.asList("agents", "system", "skills","user","classpath","project","folder","folder","all","files", "help", "--help"));
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
