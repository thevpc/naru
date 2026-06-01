package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.api.tool.NaruStructuralDirective;
import net.thevpc.naru.impl.stmt.NaruWhileStmt;

public class NaruWhileDirective extends AbstractDirective implements NaruStructuralDirective {
    public NaruWhileDirective() {
        super("while","routine", "start a while bloc");
    }


    @Override
    public NaruStatement toStatement(String arguments, NaruTask task) {
        return new NaruWhileStmt(arguments);
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        context.task().prependStatement(toStatement(context.argument(), context.task()).injected(true));
    }
}
