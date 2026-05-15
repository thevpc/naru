package net.thevpc.naru.impl.registry.directives;

import net.thevpc.naru.api.agent.NaruAgent;
import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.nuts.text.NMsg;

public class ModelDirective extends AbstractDirective {
    public ModelDirective() {
        super("model", "show models");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        NaruAgent r = context.session().runner();
        r.log(NaruLogMode.AGENT_RESPONSE, NMsg.ofC("%s (%s)",
                NMsg.ofStyledPrimary1(r.model())
                , NMsg.ofStyledPrimary2(r.provider().getName())));
    }
}
