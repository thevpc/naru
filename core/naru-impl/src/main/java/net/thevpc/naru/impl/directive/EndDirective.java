package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.tool.NaruDirectiveCallContext;

public class EndDirective extends AbstractDirective {
    public EndDirective() {
        super("end","routine", "end statement");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
    }
}
