package net.thevpc.naru.impl.registry.directives;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruScriptManager;
import net.thevpc.naru.api.agent.NaruSessionContext;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.text.NMsg;

public class ListDirective extends AbstractDirective {
    public ListDirective() {
        super("list", "list current script");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSessionContext sessionContext = context.session();
        NaruScriptManager sm = sessionContext.agentContext().getScriptManager();
        context.session().runner().log(NaruLogMode.AGENT_RESPONSE,NMsg.ofC(sm.listCurrent()));
    }
}
