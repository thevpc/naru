package net.thevpc.naru.impl.registry.directives;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruScriptManager;
import net.thevpc.naru.api.agent.NaruSessionContext;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.text.NMsg;

public class UnloadScriptDirective extends AbstractDirective {
    public UnloadScriptDirective() {
        super("unload-script", "unload current script");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSessionContext sessionContext = context.session();
        NaruScriptManager sm = sessionContext.agentContext().getScriptManager();
        sm.switchScript("main");
        context.session().runner().log(NaruLogMode.PROGRESS, NMsg.ofC("Unloaded script context. Back to main."));
    }
}
