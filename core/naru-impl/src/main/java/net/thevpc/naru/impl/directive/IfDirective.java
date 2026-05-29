package net.thevpc.naru.impl.directive;

import net.thevpc.naru.impl.stmt.NaruIfStmt;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;

public class IfDirective extends AbstractDirective {
    public IfDirective() {
        super("if","routine", "start and if condition statement");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        String raw = context.argument();
        String assignment = raw.substring(4).trim();
        NaruIfStmt stmt = new NaruIfStmt();
        stmt.setCondition(assignment);
        context.session().addStatement(stmt);
    }
}
