package net.thevpc.naru.impl.registry.directives;

import net.thevpc.naru.api.agent.NaruScriptManager;
import net.thevpc.naru.api.agent.NaruSessionContext;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;

public class RunDirective extends AbstractDirective {
    public RunDirective() {
        super("run", "run current script");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSessionContext sessionContext = context.session();
        NaruScriptManager sm = sessionContext.agentContext().getScriptManager();
        sessionContext.runner().invokeScript(sessionContext, sm.getCurrentScriptName());
    }
}
