package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.api.tool.NaruStructuralDirective;
import net.thevpc.naru.impl.stmt.NaruEndStmt;

public class NaruEndDirective extends AbstractDirective implements NaruStructuralDirective {
    public NaruEndDirective() {
        super("end", "routine", "end statement");
    }

    @Override
    public NaruStatement toStatement(String arguments, NaruTask task) {
        return new NaruEndStmt();
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        context.task().prependStatement(toStatement(context.argument(), context.task()).injected(true));
    }
}
