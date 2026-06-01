package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NArg;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.List;

public class NaruNewDirective extends AbstractDirective {
    public NaruNewDirective() {
        super("new","session", "start a new session");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        NCmdLine cmdLine = NCmdLine.parse(context.argument()).get();
        if (cmdLine.isEmpty()) {
            executeNew(context, cmdLine);
        } else {
            NArg a = cmdLine.next().get();
            switch (a.image()) {
                case "--help":
                case "help": {
                    executeHelp(context, cmdLine);
                    break;
                }
                default: {
                    task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("invalid command /%s %s", name(), context.argument()));
                }
            }
        }

    }


    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary1(name()));
        task.log(NaruLogMode.AGENT_RESPONSE, kk);
        task.log(NaruLogMode.AGENT_RESPONSE, kk);
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", kk));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           new session"));

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

    }


    public void executeNew(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        task.session().terminate();
        task.session().reset(false);
        context.task().log(NaruLogMode.PROGRESS, NMsg.ofC("reset session."));
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
