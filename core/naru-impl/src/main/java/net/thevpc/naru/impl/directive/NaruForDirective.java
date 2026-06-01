package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.api.tool.NaruStructuralDirective;
import net.thevpc.naru.impl.stmt.NaruForStmt;

public class NaruForDirective extends AbstractDirective implements NaruStructuralDirective {
    public NaruForDirective() {
        super("for","routine", "start for bloc");
    }

    @Override
    public NaruStatement toStatement(String arguments, NaruTask task) {
        return new NaruForStmt(arguments);
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        context.task().addStatement(new NaruForStmt(context.argument()));
    }
}
