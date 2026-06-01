package net.thevpc.naru.impl.directive;

import net.thevpc.naru.api.agent.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.tool.NaruDirectiveCallContext;
import net.thevpc.naru.api.tool.NaruStructuralDirective;
import net.thevpc.naru.impl.stmt.NaruElseStmt;

public class NaruElseDirective extends AbstractDirective implements NaruStructuralDirective {
    public NaruElseDirective() {
        super("else","routine", "else statement");
    }

    @Override
    public NaruStatement toStatement(String arguments, NaruTask task) {
        return new NaruElseStmt();
    }

    @Override
    public void execute(NaruDirectiveCallContext context) {
        context.task().prependStatement(toStatement(context.argument(), context.task()).injected(true));
    }
}
