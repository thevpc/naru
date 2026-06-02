package net.thevpc.naru.impl.registry.builtindirectives.routine;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruStructuralDirective;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.stmt.NaruElseIfStmt;

public class NaruElseIfDirective extends AbstractDirective implements NaruStructuralDirective {
    public NaruElseIfDirective() {
        super("elseif", "routine", "elseif statement");
    }

    @Override
    public NaruStatement toStatement(String arguments, NaruTask task) {
        return new NaruElseIfStmt(arguments);
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        context.task().prependStatement(toStatement(context.argument(), context.task()).injected(true));
    }
}
