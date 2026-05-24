package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.List;

public class ReloadDirective extends AbstractDirective {
    public ReloadDirective() {
        super("reload","session", "reload from last saved");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession session = context.session();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeReload(context, cmdLine);
        } else {
            NArg a = cmdLine.next().get();
            switch (a.image()) {
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


    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1(name()));
        session.log(NaruLogMode.AGENT_RESPONSE, kk);
        session.log(NaruLogMode.AGENT_RESPONSE, kk);
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", kk));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           reload session"));

        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        session.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }


    public void executeReload(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruSession session = context.session();
        session.sessionManager().reload();
        context.session().log(NaruLogMode.PROGRESS, NMsg.ofC("reloaded session."));
    }

    @Override
    public List<NArgCandidate> resolveCandidates(
            NCmdLine cmdLine,
            NCmdLineAutoCompleteResolver.Pos pos,
            NaruSession session) {
        List<NArgCandidate> candidates = new ArrayList<>();
        return candidates;
    }
}
