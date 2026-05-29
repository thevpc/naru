package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.stmt.NaruForStmt;

public class ForDirective extends AbstractDirective {
    public ForDirective() {
        super("for","routine", "start for bloc");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        context.session().addStatement(new NaruForStmt(context.argument()));
    }
}
