package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSession;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.util.NaruUtils;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.command.NExec;
import net.thevpc.nuts.core.NSession;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

public class LsDirective extends AbstractDirective {
    public LsDirective() {
        super("ls","general", "list directory");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSession sessionContext = context.session();
        List<String> cmd = new ArrayList<>();
        cmd.addAll(Arrays.asList("nsh","--progress=none", "-c", "ls"));
        if (!NBlankable.isBlank(context.argument())) {
            cmd.addAll(NCmdLine.parse(context.argument()).get().toStringList());
        }
        try(NSession session=NSession.of().copy()){
            session.setLogTermLevel(Level.OFF);
            session.runWith(()->{
                NExec e = NExec.of(cmd.toArray(new String[0])).directory(context.session().workingDir()).failFast(false);
                String result = e
                        .grabbedAll();
                context.session().addHistory(NaruMessage.user(NMsg.ofC("call   : %s", NCmdLine.of(cmd)).toString()));
                context.session().addHistory(NaruMessage.user(NMsg.ofC("exit code %s", e.exitCode()).toString()));
                context.session().addHistory(NaruMessage.user(NMsg.ofC("result : \n%s", NaruUtils.stripAnsi(result)).toString()));
                sessionContext.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s", result));
            });
        }
    }
}
