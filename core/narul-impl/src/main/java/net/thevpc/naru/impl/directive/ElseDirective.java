package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.tool.NaruDirectiveCallContext;

public class ElseDirective extends AbstractDirective {
    public ElseDirective() {
        super("else", "else statement");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {

    }
}
