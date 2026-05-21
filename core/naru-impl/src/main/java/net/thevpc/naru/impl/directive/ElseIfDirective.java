package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruLogMode;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.impl.stmt.NaruElseIfStmt;
import net.thevpc.naru.impl.stmt.NaruIfStmt;
import net.thevpc.nuts.text.NMsg;

public class ElseIfDirective extends AbstractDirective {
    public ElseIfDirective() {
        super("elseif", "else statement");
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        String raw = context.argument();
        String assignment = raw.trim();

        NaruElseIfStmt stmt = new NaruElseIfStmt(assignment);
        context.session().pushStatement(stmt);
    }
}
