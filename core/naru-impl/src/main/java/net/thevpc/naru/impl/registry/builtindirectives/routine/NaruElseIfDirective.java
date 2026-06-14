//package net.thevpc.naru.impl.registry.builtindirectives.routine;
//
//import net.thevpc.naru.api.task.NaruTask;
//import net.thevpc.naru.api.stmt.NaruStatement;
//import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
//import net.thevpc.naru.api.registry.NaruStructuralDirective;
//import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
//import net.thevpc.naru.impl.engine.stmt.NaruElseIfStmt;
//import net.thevpc.nuts.cmdline.NCmdLine;
//import net.thevpc.nuts.text.NText;
//
//public class NaruElseIfDirective extends AbstractDirective implements NaruStructuralDirective {
//    public NaruElseIfDirective() {
//        super("elseif", "routine", "elseif statement");
//        register(new AbstractSubCommand(new SubCommandHelp(
//                NText.ofPlain("<condition>"),NText.ofPlain("elseif branch with condition as any valid expression")
//        )) {
//            @Override
//            public void execute(NaruDirectiveCallContext context, NCmdLine cmdLine) {
//                context.task().prependStatement(toStatement(context.argument(), context.task()).injected(true));
//            }
//        });
//    }
//
//    @Override
//    public NaruStatement toStatement(String arguments, NaruTask task) {
//        return new NaruElseIfStmt(arguments);
//    }
//
//
//}
