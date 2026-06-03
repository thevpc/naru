package net.thevpc.naru.impl.registry.builtindirectives.routine;

import net.thevpc.naru.api.task.NaruTask;
import net.thevpc.naru.api.stmt.NaruStatement;
import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
import net.thevpc.naru.api.registry.NaruStructuralDirective;
import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
import net.thevpc.naru.impl.stmt.NaruForStmt;
import net.thevpc.nuts.cmdline.NCmdLine;
import net.thevpc.nuts.text.NText;

public class NaruForDirective extends AbstractDirective implements NaruStructuralDirective {
    public NaruForDirective() {
        super("for","routine", "start for bloc");
        register(new AbstractSubCommand() {
            @Override
            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
                context.task().addStatement(new NaruForStmt(context.argument()));
            }
        });
    }

    @Override
    public NaruStatement toStatement(String arguments, NaruTask task) {
        return new NaruForStmt(arguments);
    }

}
