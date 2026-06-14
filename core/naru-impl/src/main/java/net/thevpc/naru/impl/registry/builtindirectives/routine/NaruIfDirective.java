//package net.thevpc.naru.impl.registry.builtindirectives.routine;
//
//import net.thevpc.naru.api.task.NaruTask;
//import net.thevpc.naru.api.stmt.NaruStatement;
//import net.thevpc.naru.api.registry.NaruStructuralDirective;
//import net.thevpc.naru.impl.registry.builtindirectives.AbstractDirective;
//import net.thevpc.naru.impl.engine.stmt.NaruIfStmt;
//import net.thevpc.naru.api.registry.NaruDirectiveCallContext;
//import net.thevpc.nuts.cmdline.NCmdLine;
//import net.thevpc.nuts.text.NText;
//
//public class NaruIfDirective extends AbstractDirective implements NaruStructuralDirective {
//    public NaruIfDirective() {
//        super("if","routine", "start if statement");
//        register(new AbstractSubCommand(new SubCommandHelp(
//                NText.ofPlain("<condition>"),NText.ofPlain("if statement with as any valid expression as condition.\nex:\n/if n==1\n/print 'one'\n/elseif n==2\n/print 'two'\n/else\n/print 'else'\n/end")
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
//        return new NaruIfStmt(arguments);
//    }
//
//}
