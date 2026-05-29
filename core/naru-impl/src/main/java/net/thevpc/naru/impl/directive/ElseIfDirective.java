package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.stmt.NaruElseIfStmt;

public class ElseIfDirective extends AbstractDirective {
    public ElseIfDirective() {
        super("elseif","routine", "elseif statement");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        String raw = context.argument();
        String condition = raw.trim();

        NaruElseIfStmt stmt = new NaruElseIfStmt(condition);
        context.session().addStatement(stmt);
    }
}
