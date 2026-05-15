package net.thevpc.naru.impl.registry.directives;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruSessionContext;
import net.thevpc.naru.api.model.NaruMessage;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.io.NPath;
import net.thevpc.nuts.text.NMsg;
import net.thevpc.nuts.util.NBlankable;

public class CdDirective extends AbstractDirective {
    public CdDirective() {
        super("cd", "change directory");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSessionContext sessionContext = context.session();
        sessionContext.setWorkingDir(NBlankable.isBlank(context.argument()) ? context.session().projectDir() : NPath.of(context.argument()));
        context.session().addHistory(NaruMessage.user(NMsg.ofC("change working directory to %s", sessionContext.workingDir()).toString()));
        context.session().runner().log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("change directory to : %s", sessionContext.workingDir()));
    }
}
