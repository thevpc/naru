package net.thevpc.naru.impl.registry.directives;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.agent.NaruScriptManager;
import net.thevpc.naru.api.agent.NaruSessionContext;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.text.NMsg;

public class LoadScriptDirective extends AbstractDirective {
    public LoadScriptDirective() {
        super("load-script", "load new script by name or current");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruSessionContext sessionContext = context.session();
        NaruScriptManager sm = sessionContext.agentContext().getScriptManager();
        String name = context.argument();
        if (name.isEmpty()) {
            name = "main";
        }
        sm.switchScript(name);
        context.session().runner().log(NaruLogMode.AGENT_RESPONSE,NMsg.ofC("Loaded script context: %s", name));
    }
}
