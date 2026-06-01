package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruStructuralDirective;
import net.thevpc.naru.impl.stmt.NaruIfStmt;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;

public class NaruIfDirective extends AbstractDirective implements NaruStructuralDirective {
    public NaruIfDirective() {
        super("if","routine", "start and if condition statement");
    }

    @Override
    public NaruStatement toStatement(String arguments, NaruTask task) {
        return new NaruIfStmt().setCondition(arguments);
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        context.task().prependStatement(toStatement(context.argument(), context.task()).injected(true));
    }
}
