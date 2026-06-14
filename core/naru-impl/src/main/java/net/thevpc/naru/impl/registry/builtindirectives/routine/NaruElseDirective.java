//package net.thevpc.naru.impl.registry.builtindirectives.routine;
//
//import net.thevpc.naru.api.task.NaruTask;
//import net.thevpc.naru.api.stmt.NaruStatement;
//import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
//import net.thevpc.naru.api.registry.NaruStructuralDirective;
//import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
//import net.thevpc.naru.impl.engine.stmt.NaruElseStmt;
//import net.thevpc.nuts.cmdline.NCmdLine;
//
//public class NaruElseDirective extends AbstractDirective implements NaruStructuralDirective {
//    public NaruElseDirective() {
//        super("else","routine", "else statement");
//        register(new AbstractSubCommand() {
//            @Override
//            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
//                context.task().prependStatement(toStatement(context.argument(), context.task()).injected(true));
//            }
//        });
//    }
//
//    @Override
//    public NaruStatement toStatement(String arguments, NaruTask task) {
//        return new NaruElseStmt();
//    }
//
//}
