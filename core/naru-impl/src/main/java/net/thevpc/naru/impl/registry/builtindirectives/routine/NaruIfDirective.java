package net.thevpc.naru.impl.registry.builtindirectives.routine;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.registry.NaruStructuralDirective;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.stmt.NaruIfStmt;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;

public class NaruIfDirective extends AbstractDirective implements NaruStructuralDirective {
    public NaruIfDirective() {
        super("if","routine", "start if statement");
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
