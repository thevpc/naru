package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.stmt.NaruWhileStmt;

public class WhileDirective extends AbstractDirective {
    public WhileDirective() {
        super("while","routine", "start a while bloc");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        context.session().addStatement(new NaruWhileStmt(context.argument()));
    }
}
