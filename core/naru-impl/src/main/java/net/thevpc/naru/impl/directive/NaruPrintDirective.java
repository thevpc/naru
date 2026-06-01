package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.*;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.cmdline.NArgCandidate;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.cmdline.NCmdLineAutoCompleteResolver;
import net.thevpc.nuts.text.NMsg;

import java.util.ArrayList;
import java.util.List;

public class NaruPrintDirective extends AbstractDirective {
    public NaruPrintDirective() {
        super("print", "general", "print and append to context", "write");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruTask task = context.task();
        Object e = context.task().evalExpression(context.argument());
        String line = String.valueOf(e);
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", line));
        task.addHistory(NaruMessage.user(NMsg.ofC("%s", line)));
    }


    public void executeHelp(NaruDirectiveCallContext context, NCmdLine cmdLine) {
        NaruTask task = context.task();
        NMsg kk = NMsg.ofC("%s%s ", NMsg.ofStyledSeparator("/"), NMsg.ofStyledPrimary5(name()));
        task.log(NaruLogMode.AGENT_RESPONSE, kk);
        task.log(NaruLogMode.AGENT_RESPONSE, kk);
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s <any text>", kk));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           print evaluated text"));

        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s help", kk));
        task.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("           show this help"));

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
